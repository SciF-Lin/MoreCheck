/*
 * 知识问答 Agent — 应用主逻辑
 *
 * 关键链路：
 *   云端知识库 -> 本地检索（硬门禁）-> 有依据才调用大模型 -> 流式输出
 *   -> 引用编号校验（无引用拦截）-> 依据卡片 + 原文字符位置定位
 * 检索不到依据时，链路在第二步就终止，不会请求大模型。
 */
(function () {
  'use strict';

  var E = window.KbEngine;
  var esc = E.escapeHtml;
  var SDK_CDN = 'https://cdn.jsdelivr.net/npm/@tencent-ai/workbuddy-cloud-sdk@dev/lib/index.global.js';
  var SDK_LOCAL = 'vendor/workbuddy-cloud-sdk.global.js';

  var SAMPLES = [
    '访客可以删除文档吗？',
    'P1 级故障多久要响应？',
    '文档命名有什么规则？',
    '草稿会进入检索范围吗？',
    '单次最多能导入多少个文件？',
    '月度可用性低于 99% 怎么补偿？'
  ];

  var state = {
    cloud: null,
    models: [],
    model: null,
    docs: [],
    chunks: [],
    busy: false,
    controller: null,
    mid: 0,
    topK: 5
  };

  var $ = function (id) { return document.getElementById(id); };

  /* ---------- 基础工具 ---------- */
  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement('script');
      s.src = src;
      s.onload = resolve;
      s.onerror = function () { reject(new Error('SDK script failed to load')); };
      document.head.appendChild(s);
    });
  }

  async function ensureSdk() {
    // 端内改造（2026-11-09 接入 MoreCheck 实验室时改）：
    // SDK 已随包内置在 vendor/ 下，**优先加载本地副本**，CDN 只作兜底。
    // 原因：loadScript 没有超时，移动网络下 jsdelivr 若一直挂起，promise 永不 settle，
    // 页面就永久停在「正在连接云端知识库…」——在 App 里表现为白屏卡死。
    try {
      await loadScript(SDK_LOCAL);
    } catch (e) {
      await loadScript(SDK_CDN);
    }
    if (!window.WorkBuddyCloud || !window.WorkBuddyCloud.createWorkBuddyCloud) {
      await loadScript(SDK_CDN);
    }
    if (!window.WorkBuddyCloud) throw new Error('云服务 SDK 加载失败，请检查网络后刷新页面');
  }

  function setBoot(text, kind) {
    $('bootStatus').textContent = text;
    var dot = document.querySelector('.stat-dot');
    dot.className = 'stat-dot' + (kind === 'loading' ? ' is-loading' : kind === 'error' ? ' is-error' : '');
  }

  function showAlert(text) {
    var el = $('globalAlert');
    if (!text) { el.hidden = true; el.textContent = ''; return; }
    el.hidden = false;
    el.textContent = text;
  }

  function dbError(label, error) {
    var msg = (error && error.message) || '未知错误';
    if (error && error.code === '42501') msg = '没有该数据表的访问权限（RLS 拒绝）';
    if (error && error.code === '42P01') msg = '数据表尚未创建';
    return label + '：' + msg;
  }

  /* ---------- 云服务初始化 ---------- */
  async function boot() {
    setBoot('正在连接云端知识库…', 'loading');
    try {
      await ensureSdk();

      var cfg = window.KB_PUBLIC_CONFIG;
      if (!cfg || !cfg.endpoint || !cfg.publishableKey) {
        throw new Error('缺少云服务公开配置');
      }
      state.cloud = window.WorkBuddyCloud.createWorkBuddyCloud({
        endpoint: cfg.endpoint,
        oauthRelayBaseUrl: cfg.oauthRelayBaseUrl,
        publishableKey: cfg.publishableKey
      });

      // 模型目录：只用服务端返回的可用模型，不硬编码模型 id
      try {
        var models = await state.cloud.llm.models.list();
        state.models = (models || []).filter(function (m) {
          return m && m.id && m.disabled !== true && m.enabled !== false &&
            !/image|video|embed/i.test(m.id);
        });
      } catch (e) {
        state.models = [];
      }
      renderModels();

      await loadKnowledge(true);
      setBoot('云端知识库已连接 · 数据持久化', 'ok');
      showAlert('');
    } catch (err) {
      setBoot('连接失败 · ' + err.message, 'error');
      showAlert(err.message + '。请刷新页面重试；若持续失败，请确认是通过已发布的正式链接访问。');
      renderModels();
    }
  }

  function renderModels() {
    var sel = $('modelSelect');
    sel.innerHTML = '';
    if (!state.models.length) {
      var o = document.createElement('option');
      o.textContent = '暂无可用模型';
      o.value = '';
      sel.appendChild(o);
      sel.disabled = true;
      return;
    }
    state.models.forEach(function (m) {
      var opt = document.createElement('option');
      opt.value = m.id;
      opt.textContent = (m.name || m.id) + (m.id !== m.name ? ' · ' + m.id : '');
      sel.appendChild(opt);
    });
    var preferred = state.models.find(function (m) { return m.isDefault === true; }) || state.models[0];
    sel.value = preferred.id;
    state.model = preferred;
  }

  /* ---------- 知识库读取 / 首次写入示例 ---------- */
  async function loadKnowledge(withSeed) {
    var db = state.cloud.database;

    if (withSeed) {
      setBoot('正在准备示例知识库…', 'loading');
      await ensureSeed();
    }

    var res = await Promise.all([
      db.from('kb_documents').select('*').order('id', { ascending: true }).limit(2000),
      db.from('kb_chunks').select('*').order('doc_id', { ascending: true }).order('seq', { ascending: true }).limit(10000)
    ]);
    if (res[0].error) throw new Error(dbError('读取文档失败', res[0].error));
    if (res[1].error) throw new Error(dbError('读取片段失败', res[1].error));

    state.docs = res[0].data || [];
    state.chunks = res[1].data || [];
    renderStats();
    renderDocList();
    renderSamples();
  }

  async function ensureSeed() {
    var seeds = window.KB_SEED_DOCS || [];
    if (!seeds.length) return;
    var db = state.cloud.database;

    var existing = await db.from('kb_documents').select('id, slug, content');
    if (existing.error) throw new Error(dbError('检查知识库失败', existing.error));

    var rows = existing.data || [];
    var bySlug = {};
    rows.forEach(function (r) { if (r.slug) bySlug[r.slug] = r; });

    for (var i = 0; i < seeds.length; i++) {
      var doc = seeds[i];
      var slug = 'sample-' + (i + 1);
      var row = bySlug[slug];

      if (!row) {
        var ins = await db.from('kb_documents').insert({
          slug: slug, title: doc.title, category: doc.category,
          source: doc.source, content: doc.content
        }).select('id');
        if (ins.error) {
          if (ins.error.code === '23505') continue; // 并发下别的访客已写入
          throw new Error(dbError('写入示例文档失败', ins.error));
        }
        row = { id: ins.data && ins.data[0] && ins.data[0].id, content: doc.content };
      }
      if (!row || !row.id) continue;

      var count = await db.from('kb_chunks').select('id', { count: 'exact', head: true }).eq('doc_id', row.id);
      if (!count.error && count.count === 0) {
        var pieces = E.splitDocument(row.content || doc.content).map(function (c) {
          return {
            doc_id: row.id, seq: c.seq, heading: c.heading,
            start_offset: c.start_offset, end_offset: c.end_offset, text: c.text
          };
        });
        var ce = await db.from('kb_chunks').insert(pieces);
        if (ce.error) throw new Error(dbError('写入文档片段失败', ce.error));
      }
    }
  }

  function renderStats() {
    $('statDocs').textContent = state.docs.length;
    $('statChunks').textContent = state.chunks.length;
  }

  function renderDocList() {
    var box = $('docList');
    if (!state.docs.length) {
      box.innerHTML = '<div class="side-desc">知识库为空，请添加文档。</div>';
      return;
    }
    box.innerHTML = state.docs.map(function (d) {
      var n = state.chunks.filter(function (c) { return c.doc_id === d.id; }).length;
      return '<button type="button" class="doc-item" data-doc="' + d.id + '">' +
        '<span class="doc-dot" aria-hidden="true"></span>' +
        '<span><b>' + esc(d.title) + '</b>' +
        '<small>' + esc(d.category || '未分类') + ' · ' + n + ' 个片段' +
        (d.slug ? ' · 示例' : '') + '</small></span></button>';
    }).join('');
  }

  function renderSamples() {
    $('samples').innerHTML = SAMPLES.map(function (s) {
      return '<button type="button" class="sample-chip">' + esc(s) + '</button>';
    }).join('');
  }

  /* ---------- 依据卡片 ---------- */
  function highlightTerms(text, terms) {
    var html = esc(text);
    (terms || []).filter(function (t) { return t && t.length >= 2; })
      .sort(function (a, b) { return b.length - a.length; })
      .slice(0, 8)
      .forEach(function (t) {
        var safe = esc(t);
        html = html.split(safe).join('<em>' + safe + '</em>');
      });
    return html;
  }

  function evidenceHtml(hits, mid) {
    var cards = hits.map(function (h) {
      return '<div class="ev-card" id="ev-' + mid + '-' + h.citeIndex + '" data-cite="' + h.citeIndex + '">' +
        '<div class="ev-top">' +
          '<span class="ev-num">' + h.citeIndex + '</span>' +
          '<div class="ev-meta"><b>《' + esc(h.docTitle) + '》</b>' +
          '<span>' + esc(h.category || '未分类') + ' · 第 ' + h.seq + ' 段 · ' + esc(h.heading || '正文') + '</span></div>' +
        '</div>' +
        '<blockquote class="ev-quote">' + highlightTerms(h.text, h.matched) + '</blockquote>' +
        '<div class="ev-foot">' +
          '<span class="ev-loc">原文字符 ' + h.startOffset + '–' + h.endOffset + '</span>' +
          '<button type="button" class="ev-open" data-doc="' + h.docId + '" data-start="' + h.startOffset +
            '" data-end="' + h.endOffset + '">定位原文</button>' +
        '</div>' +
      '</div>';
    }).join('');

    return '<div class="evidence">' +
      '<div class="ev-head"><span class="ev-count">' + hits.length + '</span> 依据片段' +
        '<span class="ev-note">点击答案里的圆形编号，或「定位原文」查看原文位置</span></div>' +
      '<div class="ev-list">' + cards + '</div></div>';
  }

  /* ---------- 消息渲染 ---------- */
  function appendUserMessage(question) {
    $('welcome') && ($('welcome').style.display = 'none');
    var wrap = document.createElement('div');
    wrap.className = 'chat-inner';
    var html = '<div class="msg-q"><div class="bubble-q">' + esc(question) + '</div>' +
      '<span class="q-avatar">Q</span></div>';
    wrap.innerHTML = html;
    $('chatScroll').appendChild(wrap);
    return wrap;
  }

  function appendAnswerShell(searchResult) {
    var mid = ++state.mid;
    var el = document.createElement('article');
    el.className = 'msg';
    el.dataset.mid = mid;

    var tone = searchResult.hits.length ? '' : 'warn';
    var barText = searchResult.hits.length
      ? '已检索 ' + searchResult.totalChunks + ' 个片段，命中 ' + searchResult.hits.length + ' 条依据 · 仅将这些片段交给模型'
      : '检索完成 · 未找到可用依据';

    el.innerHTML =
      '<div class="msg-a">' +
        '<div class="retrieval-bar"><span class="r-dot ' + tone + '"></span>' +
          '<span>' + esc(barText) + '</span><span class="r-spacer"></span>' +
          '<code>coverage ' + (searchResult.coverage || 0).toFixed(2) + '</code></div>' +
        '<div class="a-body"></div>' +
        '<div class="a-meta"></div>' +
      '</div>';

    var holder = $('chatScroll').lastElementChild;
    if (holder && holder.classList.contains('chat-inner')) holder.appendChild(el);
    else $('chatScroll').appendChild(el);
    scrollBottom();
    return { el: el, mid: mid, body: el.querySelector('.a-body'), meta: el.querySelector('.a-meta') };
  }

  function scrollBottom() {
    var s = $('chatScroll');
    requestAnimationFrame(function () { s.scrollTop = s.scrollHeight; });
  }

  function renderRefusal(shell, result) {
    var reasons = {
      empty_query: '问题中没有可检索的实义词。',
      no_match: '知识库中没有任何片段命中该问题的关键词。',
      below_threshold: '命中的片段相关度不足，未达到启用大模型的门槛。',
      weak_intent: '问题的主题在知识库中找不到对应内容。',
      no_citation: '', no_evidence: ''
    };
    var why = reasons[result.reason] || '未找到可用依据。';
    shell.el.querySelector('.msg-a').innerHTML =
      '<div class="retrieval-bar"><span class="r-dot warn"></span>' +
        '<span>未调用大模型 · 检索阶段即终止</span><span class="r-spacer"></span>' +
        '<code>0 / ' + result.totalChunks + ' 命中</code></div>' +
      '<div class="refusal">' +
        '<span class="refusal-mark" aria-hidden="true"></span>' +
        '<div><h4>没查到依据，无法回答</h4>' +
          '<p>我在知识库的 ' + result.totalChunks + ' 个片段中没有检索到足以回答这个问题的内容，因此不会让大模型凭记忆作答。可以换个问法，或先把相关资料添加到知识库。</p>' +
          '<div class="detail">' + esc(why) + ' 检索词：' + esc((result.terms || []).join(' / ') || '（空）') + '</div>' +
        '</div>' +
      '</div>';
    scrollBottom();
  }

  function llmErrorMessage(err) {
    var code = (err && err.error && err.error.code) || '';
    var msg = (err && err.error && err.error.message) || err && err.message || '未知错误';
    if (code.indexOf('auth_') === 0) return '云服务凭据或访问域名不匹配，请通过已发布的正式链接打开本应用。';
    if (code.indexOf('quota_') === 0) return '大模型调用额度已用尽或触发限流，请稍后再试。';
    if (code.indexOf('request_') === 0) return '模型参数不匹配，请切换模型后重试。';
    if (code.indexOf('gateway_') === 0 || code.indexOf('model_') === 0) return '模型服务暂时不可用，请稍后重新提问。';
    return '大模型调用失败：' + msg;
  }

  /* ---------- 主问答流程 ---------- */
  async function ask(question) {
    var q = String(question || '').trim();
    if (!q || state.busy) return;

    appendUserMessage(q);
    var result = E.search(q, state.docs, state.chunks, { topK: state.topK });
    var shell = appendAnswerShell(result);

    // 硬门禁：无依据 -> 直接说明没查到，绝不请求大模型
    if (!result.hits.length) {
      renderRefusal(shell, result);
      return;
    }

    if (!state.model || !state.cloud) {
      shell.body.innerHTML = '<div class="blocked">模型未就绪，无法生成回答。以下为本次检索到的原文片段，可直接查看。</div>';
      shell.el.querySelector('.msg-a').insertAdjacentHTML('beforeend', evidenceHtml(result.hits, shell.mid));
      return;
    }

    state.busy = true;
    state.controller = new AbortController();
    setSending(true);
    shell.body.classList.add('streaming');
    shell.meta.textContent = '生成中 · 完成后会校验引用编号';

    var raw = '';
    try {
      var stream = state.cloud.llm.chat.completions.create({
        model: state.model.id,
        messages: [
          { role: 'system', content: E.SYSTEM_PROMPT },
          { role: 'user', content: E.buildUserPrompt(q, result.hits) }
        ],
        stream: true,
        stream_options: { include_usage: true },
        temperature: 0.1,
        signal: state.controller.signal
      });

      for await (var chunk of stream) {
        var choice = chunk && chunk.choices && chunk.choices[0];
        var delta = choice && choice.delta;
        if (delta && delta.content) {
          raw += delta.content;
          shell.body.textContent = raw;
          scrollBottom();
        }
      }
    } catch (err) {
      if (state.controller.signal.aborted || (err && err.name === 'AbortError')) {
        raw = '';
      } else {
        shell.body.classList.remove('streaming');
        shell.body.innerHTML = '<div class="blocked">' + esc(llmErrorMessage(err)) +
          ' 本次检索到的原文片段仍列在下方，可直接核对。</div>';
        shell.meta.textContent = '回答生成失败 · 依据已保留';
        shell.el.querySelector('.msg-a').insertAdjacentHTML('beforeend', evidenceHtml(result.hits, shell.mid));
        state.busy = false;
        setSending(false);
        scrollBottom();
        return;
      }
    }

    shell.body.classList.remove('streaming');
    state.busy = false;
    setSending(false);

    if (!raw.trim()) {
      var stopped = state.controller.signal.aborted;
      shell.body.innerHTML = '<div class="blocked">' +
        (stopped ? '已停止生成。' : '模型没有返回内容，已拦截。') +
        '以下为本次检索到的原文片段，可直接核对。</div>';
      shell.meta.textContent = stopped ? '已停止 · 依据已保留' : '无有效回答';
    } else {
      var check = E.validateAnswer(raw, result.hits);
      if (check.ok) {
        shell.body.innerHTML = E.renderAnswerHtml(raw, result.hits);
        shell.meta.innerHTML = '<span>已核验引用 <b>' + check.citations.length + '</b> 处</span>' +
          '<span>依据片段 ' + result.hits.length + ' 条</span>' +
          '<span>模型 ' + esc(state.model.name || state.model.id) + '</span>' +
          (check.fabricated && check.fabricated.length ? '<span>已过滤无效编号 ' + esc(check.fabricated.join(',')) + '</span>' : '');
      } else if (check.reason === 'no_evidence') {
        shell.body.innerHTML = '<div class="refusal" style="padding:16px 20px">' +
          '<span class="refusal-mark" aria-hidden="true"></span>' +
          '<div><h4>片段不足以回答，模型未给出结论</h4>' +
          '<p>模型判定本次检索到的片段无法回答该问题，因此没有输出答案。下方仍列出这些片段，可自行核对。</p></div></div>';
        shell.meta.textContent = '未生成结论 · 依据已保留';
      } else {
        shell.body.innerHTML = '<div class="blocked">模型返回的内容没有标注可核验的引用编号（或编号不存在），已拦截、不予展示。请以下方原文片段为准。</div>';
        shell.meta.textContent = '引用校验未通过 · 回答已拦截';
      }
    }

    shell.el.querySelector('.msg-a').insertAdjacentHTML('beforeend', evidenceHtml(result.hits, shell.mid));
    scrollBottom();
  }

  function setSending(on) {
    $('btnSend').disabled = on;
    $('btnStop').hidden = !on;
    $('question').disabled = on;
  }

  /* ---------- 原文定位 ---------- */
  function openSource(docId, start, end) {
    var doc = state.docs.find(function (d) { return d.id === docId; });
    if (!doc) return;
    $('sourceTitle').textContent = doc.title;
    $('sourceMeta').textContent = (doc.category || '未分类') + ' · ' + (doc.source || '来源未标注') +
      ' · 共 ' + (doc.content || '').length + ' 字符';

    var hasLoc = typeof start === 'number' && typeof end === 'number' && end > start;
    $('sourceBody').innerHTML = (hasLoc
        ? '<span class="hit-tip">高亮部分即本次回答引用的原文片段（字符 ' + start + '–' + end + '）</span>' +
          E.renderSourceHtml(doc.content, start, end)
        : esc(doc.content || ''));

    $('sourceOverlay').hidden = false;
    $('sourceDrawer').hidden = false;
    if (hasLoc) {
      setTimeout(function () {
        var hit = $('source-hit');
        if (hit) hit.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }, 80);
    }
  }

  function closeSource() {
    $('sourceOverlay').hidden = true;
    $('sourceDrawer').hidden = true;
  }

  function openDocModal() {
    $('docOverlay').hidden = false;
    $('docModal').hidden = false;
    $('docFormMsg').textContent = '';
  }

  function closeDocModal() {
    $('docOverlay').hidden = true;
    $('docModal').hidden = true;
  }

  async function saveDocument(ev) {
    ev.preventDefault();
    var title = $('docTitle').value.trim();
    var content = $('docContent').value.replace(/\r\n/g, '\n').trim();
    var category = $('docCategory').value.trim();
    var source = $('docSource').value.trim();
    var msg = $('docFormMsg');

    var pieces = E.splitDocument(content);
    if (!title || !content) { msg.textContent = '标题和正文不能为空'; return; }
    if (!pieces.length) { msg.textContent = '正文太短，无法生成片段'; return; }

    var btn = $('btnSaveDoc');
    btn.disabled = true;
    msg.textContent = '正在写入…';

    try {
      var db = state.cloud.database;
      var ins = await db.from('kb_documents').insert({
        title: title, category: category || null, source: source || null, content: content
      }).select('id');
      if (ins.error) throw new Error(dbError('写入文档失败', ins.error));

      var docId = ins.data && ins.data[0] && ins.data[0].id;
      var ce = await db.from('kb_chunks').insert(pieces.map(function (c) {
        return {
          doc_id: docId, seq: c.seq, heading: c.heading,
          start_offset: c.start_offset, end_offset: c.end_offset, text: c.text
        };
      }));
      if (ce.error) throw new Error(dbError('写入片段失败', ce.error));

      await loadKnowledge(false);
      $('docForm').reset();
      closeDocModal();
      showAlert('');
    } catch (err) {
      msg.textContent = err.message;
    } finally {
      btn.disabled = false;
    }
  }

  /* ---------- 事件绑定 ---------- */
  function bind() {
    $('composer').addEventListener('submit', function (e) {
      e.preventDefault();
      var v = $('question').value;
      if (!v.trim() || state.busy) return;
      $('question').value = '';
      autoGrow();
      ask(v);
    });

    var ta = $('question');
    function autoGrow() {
      ta.style.height = 'auto';
      ta.style.height = Math.min(ta.scrollHeight, 130) + 'px';
    }
    ta.addEventListener('input', autoGrow);
    ta.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        $('composer').requestSubmit();
      }
    });

    $('samples').addEventListener('click', function (e) {
      var chip = e.target.closest('.sample-chip');
      if (chip) ask(chip.textContent);
    });

    $('btnStop').addEventListener('click', function () {
      if (state.controller) state.controller.abort();
    });

    $('modelSelect').addEventListener('change', function (e) {
      state.model = state.models.find(function (m) { return m.id === e.target.value; }) || null;
    });

    $('topKSelect').addEventListener('change', function (e) {
      state.topK = parseInt(e.target.value, 10) || 5;
    });

    $('chatScroll').addEventListener('click', function (e) {
      var badge = e.target.closest('.cite-badge');
      if (badge) {
        var msg = badge.closest('.msg');
        var card = $('ev-' + msg.dataset.mid + '-' + badge.dataset.cite);
        if (card) {
          card.scrollIntoView({ block: 'center', behavior: 'smooth' });
          card.classList.add('flash');
          setTimeout(function () { card.classList.remove('flash'); }, 1400);
        }
        return;
      }
      var open = e.target.closest('.ev-open');
      if (open) {
        openSource(Number(open.dataset.doc), Number(open.dataset.start), Number(open.dataset.end));
      }
    });

    $('docList').addEventListener('click', function (e) {
      var item = e.target.closest('.doc-item');
      if (item) openSource(Number(item.dataset.doc));
    });

    $('btnAddDoc').addEventListener('click', openDocModal);
    $('docForm').addEventListener('submit', saveDocument);
    $('btnCloseDoc').addEventListener('click', closeDocModal);
    $('docOverlay').addEventListener('click', closeDocModal);
    $('btnCloseSource').addEventListener('click', closeSource);
    $('sourceOverlay').addEventListener('click', closeSource);
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { closeSource(); closeDocModal(); }
    });

    $('btnToggleKb').addEventListener('click', function () {
      toggleSidebar();
    });
  }

  function toggleSidebar() {
    var sb = $('sidebar');
    sb.classList.toggle('open');
    var bd = document.querySelector('.sidebar-backdrop');
    if (window.innerWidth <= 1000 && sb.classList.contains('open')) {
      if (!bd) {
        bd = document.createElement('div');
        bd.className = 'sidebar-backdrop';
        bd.addEventListener('click', function () {
          sb.classList.remove('open');
          bd.remove();
        });
        document.body.appendChild(bd);
      }
    } else if (bd) {
      bd.remove();
    }
  }

  /* ---------- 启动 ---------- */
  bind();
  boot();
})();
