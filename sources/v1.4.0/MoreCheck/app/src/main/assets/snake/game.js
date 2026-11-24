/**
 * 贪吃蛇 —— 原生 JavaScript 实现（零依赖 / 零构建 / 双击 index.html 即玩）
 *
 * 架构说明：
 *   - 状态机：READY → RUNNING ⇄ PAUSED → GAME_OVER / WIN → (重开) → RUNNING
 *   - 主循环：requestAnimationFrame + 时间累加驱动固定步长的逻辑 tick（逻辑与渲染分离）
 *   - 输入：方向按键写入缓冲队列，每个 tick 只消费一个，避免「同一帧连按导致 180° 自杀」
 */

(function () {
  'use strict';

  /* ============================================================
   * 一、常量定义
   * ============================================================ */

  /** 全局配置常量 */
  const CONFIG = {
    GRID_COUNT: 20,          // 棋盘边长（格）
    CELL_SIZE: 24,           // 单个格子的逻辑像素
    MAX_QUEUED_INPUTS: 3,    // 方向输入缓冲队列最大长度
    MAX_FRAME_MS: 200,       // 单帧最大计入时间，防止切后台回来后瞬间追帧
    SWIPE_THRESHOLD: 24,     // 触摸滑动的最小位移（px）
    // 死亡后的重开冷却（ms），避免连续点击误触新开一局。
    // v1.1.76 需求 4：由 3000 改为 1500——3 秒在手感上偏长，1.5 秒仍足够拦住
    // 死亡瞬间的连点（实测死亡后本能连点集中在 1 秒内）。
    RESTART_LOCK_MS: 1500,
    STORAGE_KEY_SCORE: 'snake_high_score',
    STORAGE_KEY_LEVEL: 'snake_difficulty',
  };

  /** 棋盘逻辑边长（像素） */
  const LOGICAL_SIZE = CONFIG.GRID_COUNT * CONFIG.CELL_SIZE;

  /** 游戏状态枚举 */
  const STATE = {
    READY: 'READY',
    RUNNING: 'RUNNING',
    PAUSED: 'PAUSED',
    GAME_OVER: 'GAME_OVER',
    WIN: 'WIN',
  };

  /** 状态对应的中文展示文案 */
  const STATE_LABEL = {
    READY: '准备开始',
    RUNNING: '游戏中',
    PAUSED: '已暂停',
    GAME_OVER: '游戏结束',
    WIN: '通关胜利',
  };

  /** 方向 -> 位移向量（画布坐标系：x 向右为正，y 向下为正） */
  const DIRECTION_VECTORS = {
    up: { x: 0, y: -1 },
    down: { x: 0, y: 1 },
    left: { x: -1, y: 0 },
    right: { x: 1, y: 0 },
  };

  /** 难度档位：tick 间隔越小速度越快 */
  const DIFFICULTIES = {
    easy: { key: 'easy', label: '简单', tickMs: 150, points: 10 },
    normal: { key: 'normal', label: '普通', tickMs: 100, points: 15 },
    hard: { key: 'hard', label: '困难', tickMs: 60, points: 25 },
  };
  const DEFAULT_LEVEL = 'normal';

  /** 渲染主题色（深色版，默认）。取值与 style.css 的深色变量一一对应。 */
  const COLORS_DARK = {
    boardTop: '#151c2c',
    boardBottom: '#0d1220',
    gridLine: 'rgba(255, 255, 255, 0.045)',
    snakeHead: '#5ef2b0',
    snakeTail: '#2b8f7c',
    snakeDead: '#7b8798',
    snakeShadow: 'rgba(94, 242, 176, 0.35)',
    foodCore: '#ffe3ea',
    foodEdge: '#ff5f7e',
    foodGlow: 'rgba(255, 95, 126, 0.55)',
    eyeWhite: '#ffffff',
    eyePupil: '#0b1020',
  };

  /**
   * 渲染主题色（浅色版，本轮需求 4：「按照原配色做一个浅色的版本」）。
   *
   * 原则：**强调色（蛇 / 食物）保持原配色不动**——需求说的是"按原配色做浅色版本"，
   * 即换的是背景与文字这类中性色，蛇头的荧光绿、食物的粉红是游戏 identity，不动。
   * 只把深色底（棋盘渐变、页面背景）换成同色相的浅色，网格线反向（浅底上用深色线）。
   * 页面背景 / 面板 / 边框 / 文字的浅色取值在 style.css 的 `html[data-theme="light"]` 段，
   * 两边同源。
   */
  const COLORS_LIGHT = {
    boardTop: '#e9edf7',
    boardBottom: '#dde3f0',
    gridLine: 'rgba(15, 23, 42, 0.06)',
    snakeHead: '#5ef2b0',
    snakeTail: '#2b8f7c',
    snakeDead: '#7b8798',
    snakeShadow: 'rgba(43, 143, 124, 0.25)',
    foodCore: '#ffe3ea',
    foodEdge: '#ff5f7e',
    foodGlow: 'rgba(255, 95, 126, 0.45)',
    eyeWhite: '#ffffff',
    eyePupil: '#0b1020',
  };

  /** 当前生效的渲染主题（'dark' | 'light'）。由 setTheme 切换，init 时读存档。 */
  let theme = 'dark';
  /** 当前渲染色（theme 的派生值，切换时一次性替换，渲染循环只读它）。 */
  let COLORS = COLORS_DARK;

  /** 主题的 localStorage 键。独立于难度 / 最高分，三者互不覆盖。 */
  const STORAGE_KEY_THEME = 'snake_theme';

  /**
   * 切换深浅色主题。
   *
   * @param {string} next 'dark' | 'light'
   * @param {boolean} persist 是否写入存档（init 恢复时传 false，避免每次启动都写一遍）。
   */
  function setTheme(next, persist) {
    if (next !== 'dark' && next !== 'light') return;
    theme = next;
    COLORS = next === 'light' ? COLORS_LIGHT : COLORS_DARK;
    // 页面级颜色（背景 / 面板 / 文字）全在 CSS 变量里：在 <html> 上换 data-theme，
    // style.css 的 `[data-theme="light"]` 覆盖段整体生效，JS 不逐个改元素样式。
    document.documentElement.setAttribute('data-theme', next);
    // 状态栏 theme-color 跟随（浏览器不渲染它，但 WebView 里对系统栏无影响；
    // 保留赋值是为将来浏览器直接打开时观感一致）。
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', next === 'light' ? '#f2f4fa' : '#0d1220');
    // 棋盘背景渐变缓存必须重算（resizeCanvas 里按 COLORS 生成）。
    renderer.backgroundGradient = null;
    if (persist) writeStorage(STORAGE_KEY_THEME, next);
    syncUi();
  }

  /** 在深浅两档之间切换（切换按钮的回调）。 */
  function toggleTheme() {
    setTheme(theme === 'dark' ? 'light' : 'dark', true);
  }

  function loadTheme() {
    const raw = readStorage(STORAGE_KEY_THEME);
    return raw === 'light' ? 'light' : 'dark';
  }

  /* ============================================================
   * 二、DOM 引用
   * ============================================================ */

  const dom = {
    canvas: document.getElementById('canvas'),
    board: document.getElementById('board'),
    overlay: document.getElementById('overlay'),
    overlayTitle: document.getElementById('overlayTitle'),
    overlayDesc: document.getElementById('overlayDesc'),
    overlayBtn: document.getElementById('overlayBtn'),
    scoreValue: document.getElementById('scoreValue'),
    highScoreValue: document.getElementById('highScoreValue'),
    statusValue: document.getElementById('statusValue'),
    statusPill: document.querySelector('.stat--pill'),
    levelLabel: document.getElementById('levelLabel'),
    levelGroup: document.getElementById('levelGroup'),
    btnPause: document.getElementById('btnPause'),
    btnRestart: document.getElementById('btnRestart'),
    btnTheme: document.getElementById('btnTheme'),
    btnExit: document.getElementById('btnExit'),
    cooldown: document.getElementById('cooldown'),
    cooldownBar: document.getElementById('cooldownBar'),
    dpad: document.getElementById('dpad'),
  };

  /**
   * 「退出游戏」回调（本轮需求 3）。
   *
   * 由宿主注入（SnakeScreen 通过 `addJavascriptInterface` 桥接后调用
   * `window.__MORECHECK__.exitGame()`，见 index.html 底部与 SnakeScreen 的说明）；
   * 网页直接双击打开（无宿主）时该回调不存在，退出按钮隐藏——这是兜底而不是主路径。
   */
  let exitHandler = null;

  /** 宿主（SnakeScreen）注入的退出回调入口。 */
  window.__MORECHECK__ = {
    /** 宿主调它来注册"退出 = 结束 Activity"；传 null 恢复为无宿主状态。 */
    setExitHandler: function (fn) { exitHandler = typeof fn === 'function' ? fn : null; },
  };

  const ctx = dom.canvas.getContext('2d');

  /* ============================================================
   * 三、工具函数
   * ============================================================ */

  /** 读取 localStorage，失败（如隐私模式 / file:// 受限）时返回 null */
  function readStorage(key) {
    try {
      return window.localStorage.getItem(key);
    } catch (error) {
      return null;
    }
  }

  /** 写入 localStorage，失败时静默降级为仅内存保存 */
  function writeStorage(key, value) {
    try {
      window.localStorage.setItem(key, value);
    } catch (error) {
      /* 忽略：存储不可用时不影响游戏进行 */
    }
  }

  /** 将 #RRGGBB 解析为 [r, g, b] */
  function hexToRgb(hex) {
    const normalized = hex.replace('#', '');
    return [
      parseInt(normalized.substring(0, 2), 16),
      parseInt(normalized.substring(2, 4), 16),
      parseInt(normalized.substring(4, 6), 16),
    ];
  }

  /** 在两个 #RRGGBB 颜色之间线性插值，ratio 取值 [0, 1] */
  function lerpColor(fromHex, toHex, ratio) {
    const from = hexToRgb(fromHex);
    const to = hexToRgb(toHex);
    const t = Math.min(1, Math.max(0, ratio));
    const r = Math.round(from[0] + (to[0] - from[0]) * t);
    const g = Math.round(from[1] + (to[1] - from[1]) * t);
    const b = Math.round(from[2] + (to[2] - from[2]) * t);
    return 'rgb(' + r + ', ' + g + ', ' + b + ')';
  }

  /** 绘制圆角矩形路径 */
  function roundRectPath(context, x, y, width, height, radius) {
    const r = Math.min(radius, width / 2, height / 2);
    context.beginPath();
    context.moveTo(x + r, y);
    context.lineTo(x + width - r, y);
    context.quadraticCurveTo(x + width, y, x + width, y + r);
    context.lineTo(x + width, y + height - r);
    context.quadraticCurveTo(x + width, y + height, x + width - r, y + height);
    context.lineTo(x + r, y + height);
    context.quadraticCurveTo(x, y + height, x, y + height - r);
    context.lineTo(x, y + r);
    context.quadraticCurveTo(x, y, x + r, y);
    context.closePath();
  }

  /** 判断两个方向是否互为 180° 反向 */
  function isOpposite(a, b) {
    const va = DIRECTION_VECTORS[a];
    const vb = DIRECTION_VECTORS[b];
    if (!va || !vb) return false;
    return va.x + vb.x === 0 && va.y + vb.y === 0;
  }

  /* ============================================================
   * 四、游戏状态
   * ============================================================ */

  const game = {
    state: STATE.READY,
    snake: [],              // 蛇身坐标数组，index 0 为蛇头
    direction: 'right',     // 当前前进方向
    pendingDirs: [],        // 方向输入缓冲队列
    food: null,             // 食物坐标 { x, y }，棋盘填满时为 null
    score: 0,
    highScore: 0,
    level: DEFAULT_LEVEL,   // 难度 key
    tickMs: DIFFICULTIES[DEFAULT_LEVEL].tickMs,
    accumulator: 0,         // 逻辑步长时间累加器
    lastFrameTs: null,      // 上一帧时间戳（null 表示尚未初始化）
    endReason: '',          // 死亡原因描述
    lockRemaining: 0,       // 重开冷却剩余时间（ms），> 0 时禁止任何方式开始新局
    lockShownSecond: -1,    // 上一次渲染到界面上的冷却秒数，用于避免每帧重复写 DOM
    /**
     * 退出按钮是否处于「游戏中置灰」状态（本轮需求 3）。
     *
     * 需求原文：「游戏中时灰色无法点击」。"游戏中"= RUNNING（暂停与结束态都可退出——
     * 暂停时用户可能就是想走，结束态更是天然允许）。状态变化集中在 syncUi 里判定，
     * 与其它按钮的置灰共用同一时机。
     */
    exitLocked: false,
  };

  /** 是否处于死亡后的重开冷却中 */
  function isRestartLocked() {
    return game.lockRemaining > 0;
  }

  /** 冷却剩余秒数（向上取整，用于界面展示） */
  function lockRemainingSeconds() {
    return Math.ceil(game.lockRemaining / 1000);
  }

  /** 获取当前难度配置 */
  function currentLevel() {
    return DIFFICULTIES[game.level] || DIFFICULTIES[DEFAULT_LEVEL];
  }

  /* ============================================================
   * 五、存档读写
   * ============================================================ */

  function loadHighScore() {
    const raw = readStorage(CONFIG.STORAGE_KEY_SCORE);
    const value = parseInt(raw, 10);
    return Number.isFinite(value) && value > 0 ? value : 0;
  }

  function saveHighScore() {
    writeStorage(CONFIG.STORAGE_KEY_SCORE, String(game.highScore));
  }

  function loadLevel() {
    const raw = readStorage(CONFIG.STORAGE_KEY_LEVEL);
    return raw && Object.prototype.hasOwnProperty.call(DIFFICULTIES, raw) ? raw : DEFAULT_LEVEL;
  }

  function saveLevel() {
    writeStorage(CONFIG.STORAGE_KEY_LEVEL, game.level);
  }

  /* ============================================================
   * 六、游戏逻辑
   * ============================================================ */

  /**
   * 重置并初始化一局游戏（回到中心位置、长度 3、向右前进）
   * 重置后状态为 READY，等待玩家开始。
   */
  function resetGame() {
    const mid = Math.floor(CONFIG.GRID_COUNT / 2);
    game.snake = [
      { x: mid, y: mid },
      { x: mid - 1, y: mid },
      { x: mid - 2, y: mid },
    ];
    game.direction = 'right';
    game.pendingDirs = [];
    game.food = null;
    game.score = 0;
    game.accumulator = 0;
    game.endReason = '';
    game.state = STATE.READY;
    spawnFood();
    syncUi();
  }

  /**
   * 随机生成食物，必须避开蛇身占据的格子。
   * @returns {boolean} 生成成功返回 true；棋盘已被蛇填满（通关）返回 false。
   */
  function spawnFood() {
    const total = CONFIG.GRID_COUNT * CONFIG.GRID_COUNT;
    if (game.snake.length >= total) {
      game.food = null;
      return false;
    }

    const occupied = new Set();
    for (let i = 0; i < game.snake.length; i++) {
      occupied.add(game.snake[i].x + ',' + game.snake[i].y);
    }

    const freeCells = [];
    for (let y = 0; y < CONFIG.GRID_COUNT; y++) {
      for (let x = 0; x < CONFIG.GRID_COUNT; x++) {
        if (!occupied.has(x + ',' + y)) {
          freeCells.push({ x: x, y: y });
        }
      }
    }

    if (freeCells.length === 0) {
      game.food = null;
      return false;
    }

    game.food = freeCells[Math.floor(Math.random() * freeCells.length)];
    return true;
  }

  /**
   * 缓冲一个方向输入。
   * 规则：忽略与「队列末尾方向（或当前方向）」相同或 180° 反向的输入，
   * 从而同时杜绝「直接反向自杀」与「同一帧连按两次导致自杀」。
   * @param {string} dir - 'up' | 'down' | 'left' | 'right'
   */
  function pushDirection(dir) {
    if (!DIRECTION_VECTORS[dir]) return;

    const tail = game.pendingDirs.length > 0
      ? game.pendingDirs[game.pendingDirs.length - 1]
      : game.direction;

    if (dir === tail || isOpposite(dir, tail)) return;
    if (game.pendingDirs.length >= CONFIG.MAX_QUEUED_INPUTS) return;

    game.pendingDirs.push(dir);
  }

  /** 推进一个逻辑步：移动蛇头、判定碰撞与进食 */
  function step() {
    // 每个 tick 只消费一个缓冲方向
    if (game.pendingDirs.length > 0) {
      const next = game.pendingDirs.shift();
      if (!isOpposite(next, game.direction)) {
        game.direction = next;
      }
    }

    const head = game.snake[0];
    const vector = DIRECTION_VECTORS[game.direction];
    const newHead = { x: head.x + vector.x, y: head.y + vector.y };

    // 1) 撞墙判定
    if (
      newHead.x < 0 || newHead.x >= CONFIG.GRID_COUNT ||
      newHead.y < 0 || newHead.y >= CONFIG.GRID_COUNT
    ) {
      endGame('撞到墙壁了');
      return;
    }

    // 2) 是否吃到食物
    const ate = game.food !== null && newHead.x === game.food.x && newHead.y === game.food.y;

    // 3) 撞自身判定：没吃到食物时蛇尾会移开，因此最后一节不参与判定
    const limit = ate ? game.snake.length : game.snake.length - 1;
    for (let i = 0; i < limit; i++) {
      if (game.snake[i].x === newHead.x && game.snake[i].y === newHead.y) {
        endGame('咬到自己了');
        return;
      }
    }

    game.snake.unshift(newHead);

    if (ate) {
      game.score += currentLevel().points;
      if (game.score > game.highScore) {
        game.highScore = game.score;
        saveHighScore();
      }
      if (!spawnFood()) {
        winGame();
        return;
      }
    } else {
      game.snake.pop();
    }

    syncUi();
  }

  /* ============================================================
   * 七、状态切换
   * ============================================================ */

  /** 开始 / 继续游戏 */
  function startGame() {
    if (game.state === STATE.RUNNING) return;
    // 死亡冷却期内一律不接受开新局，避免连点误触
    if (isRestartLocked()) return;
    if (game.state === STATE.READY || game.state === STATE.GAME_OVER || game.state === STATE.WIN) {
      resetGame();
    }
    game.state = STATE.RUNNING;
    game.accumulator = 0;
    game.lockRemaining = 0;
    game.lockShownSecond = -1;
    syncUi();
  }

  /** 暂停游戏（仅 RUNNING 状态可暂停） */
  function pauseGame() {
    if (game.state !== STATE.RUNNING) return;
    game.state = STATE.PAUSED;
    syncUi();
  }

  /** 继续游戏（仅 PAUSED 状态可继续） */
  function resumeGame() {
    if (game.state !== STATE.PAUSED) return;
    game.state = STATE.RUNNING;
    game.accumulator = 0;
    syncUi();
  }

  /** 暂停 / 继续切换 */
  function togglePause() {
    if (game.state === STATE.RUNNING) {
      pauseGame();
    } else if (game.state === STATE.PAUSED) {
      resumeGame();
    } else {
      startGame();
    }
  }

  /** 重新开始一局 */
  function restartGame() {
    // 死亡冷却期内忽略「重新开始」，避免玩家连点直接跳进新局
    if (isRestartLocked()) return;
    resetGame();
    game.state = STATE.RUNNING;
    game.accumulator = 0;
    game.lockRemaining = 0;
    game.lockShownSecond = -1;
    syncUi();
  }

  /** 结束游戏 */
  function endGame(reason) {
    game.state = STATE.GAME_OVER;
    game.endReason = reason || '游戏结束';
    // 开启重开冷却：3 秒内任何开新局的操作都会被忽略
    game.lockRemaining = CONFIG.RESTART_LOCK_MS;
    game.lockShownSecond = lockRemainingSeconds();
    syncUi();
  }

  /** 通关胜利 */
  function winGame() {
    game.state = STATE.WIN;
    syncUi();
  }

  /** 设置难度档位 */
  function setLevel(key) {
    if (!DIFFICULTIES[key]) return;
    // 本轮需求 4：「游戏中时应无法切换难度，让按钮发灰」。
    // 按钮层已通过 disabled 拦截，这里再挡一次是防御第二层：
    // 键盘 / 触摸可能绕过按钮（例如辅助功能的点击注入直接派发 click 之外的路径）。
    // 暂停态允许切——暂停时改难度是玩家的合理操作（下一 tick 生效），只禁 RUNNING。
    if (game.state === STATE.RUNNING) return;
    game.level = key;
    game.tickMs = DIFFICULTIES[key].tickMs;
    game.accumulator = 0;
    saveLevel();
    syncUi();
  }

  /* ============================================================
   * 八、UI 同步
   * ============================================================ */

  /** 根据当前状态刷新 HUD、遮罩层与按钮文案 */
  function syncUi() {
    dom.scoreValue.textContent = String(game.score);
    dom.highScoreValue.textContent = String(game.highScore);
    dom.statusValue.textContent = STATE_LABEL[game.state];
    dom.levelLabel.textContent = currentLevel().label;
    dom.btnPause.textContent = game.state === STATE.PAUSED ? '继续' : '暂停';

    dom.statusPill.classList.remove('is-paused', 'is-over', 'is-win');
    if (game.state === STATE.PAUSED) dom.statusPill.classList.add('is-paused');
    if (game.state === STATE.GAME_OVER) dom.statusPill.classList.add('is-over');
    if (game.state === STATE.WIN) dom.statusPill.classList.add('is-win');

    const levelButtons = dom.levelGroup.querySelectorAll('.seg-btn');
    for (let i = 0; i < levelButtons.length; i++) {
      const isActive = levelButtons[i].getAttribute('data-level') === game.level;
      levelButtons[i].classList.toggle('is-active', isActive);
      // 本轮需求 4：游戏中三个难度按钮整体置灰（disabled + CSS 发灰）。
      // 暂停 / 结束 / 准备态都可切——只锁 RUNNING 这一态（见 setLevel 里的同一条规则）。
      levelButtons[i].disabled = game.state === STATE.RUNNING;
    }

    // 本轮需求 3：「重新开始」按钮的形态切换 + 「退出游戏」按钮的可用性。
    //
    // - 有宿主退出回调（exitHandler 非 null）时：标题栏被隐藏 → 用户没有返回键可点，
    //   底部「重新开始」改为「退出游戏」（文案与行为都换），游戏中置灰、其余态可点；
    // - 没有宿主（浏览器直接打开）：退出没有意义，隐藏退出按钮、保留原「重新开始」。
    // 两个按钮是**同一位置的两态**（HTML 里都渲染、用 hidden 切换），
    // 大小与位置由同一套 CSS 类（.btn-ghost）保证完全一致。
    const hasExit = exitHandler !== null;
    const running = game.state === STATE.RUNNING;
    if (dom.btnExit) {
      dom.btnExit.hidden = !hasExit;
      if (hasExit) {
        dom.btnExit.disabled = running;
        dom.btnExit.classList.toggle('is-locked', running);
      }
    }
    if (dom.btnRestart) dom.btnRestart.hidden = hasExit;
    if (dom.btnTheme) {
      // 主题切换按钮的显示时机：**准备开始 / 游戏结束 / 通关**三种"静止态"显示
      // （v1.1.80 需求 2 把 READY 也纳入——准备页是用户第一次看到棋盘的地方，
      //   此时切换深浅色最自然，不必先开一局再死一次）。
      // - 游戏中 / 暂停态仍隐藏：按钮叠在棋盘右上角，会挡视线、易误触；
      // - "点它会自动开局"的问题：按钮是 .board 的子元素，触摸事件会冒泡到
      //   board 的 touchend 处理器（那里把"位移过小的点击"当作开始新局）。
      //   双管齐下修掉：① 按钮自身 stopPropagation()（见 bindEvents），
      //   ② onTouchEnd 忽略来自按钮的触摸（见该函数）。
      // - 准备页上遮罩层（.overlay）覆盖棋盘：按钮的 z-index: 2 高于遮罩层的
      //   z-index: auto，绘制与命中都在遮罩之上，因此仍可见、可点。
      dom.btnTheme.hidden = !(
        game.state === STATE.READY ||
        game.state === STATE.GAME_OVER ||
        game.state === STATE.WIN
      );
    }

    renderOverlay();
  }

  /** 渲染遮罩层内容（开始 / 暂停 / 结束 / 通关 四态） */
  function renderOverlay() {
    if (game.state === STATE.RUNNING) {
      dom.overlay.classList.remove('is-visible');
      return;
    }

    let title = '';
    let desc = '';
    let buttonText = '';

    if (game.state === STATE.READY) {
      title = '准备开始';
      desc = '当前难度：' + currentLevel().label + '，每吃到一个食物 +' + currentLevel().points +
        ' 分。<br />按 空格 / 回车 开始，或用方向键直接出发。';
      buttonText = '开始游戏';
    } else if (game.state === STATE.PAUSED) {
      title = '已暂停';
      desc = '当前得分 ' + game.score + '，最高分 ' + game.highScore + '。<br />按 空格 继续游戏。';
      buttonText = '继续游戏';
    } else if (game.state === STATE.GAME_OVER) {
      title = '游戏结束';
      if (isRestartLocked()) {
        const seconds = lockRemainingSeconds();
        desc = game.endReason + '，本次得分 ' + game.score + '，最高分 ' + game.highScore +
          '。<br />防误触：<b>' + seconds + '</b> 秒后才能开始下一局。';
        buttonText = '请等待 ' + seconds + ' 秒';
      } else {
        desc = game.endReason + '，本次得分 ' + game.score + '，最高分 ' + game.highScore +
          '。<br />按 R 或点击下方按钮再来一局。';
        buttonText = '再来一局';
      }
    } else {
      title = '恭喜通关';
      desc = '你填满了整个 ' + CONFIG.GRID_COUNT + '×' + CONFIG.GRID_COUNT + ' 棋盘！<br />最终得分 ' +
        game.score + '，最高分 ' + game.highScore + '。';
      buttonText = '再来一局';
    }

    dom.overlayTitle.textContent = title;
    dom.overlayDesc.innerHTML = desc;
    dom.overlayBtn.textContent = buttonText;
    dom.overlay.classList.add('is-visible');

    renderCooldown();
  }

  /** 渲染死亡冷却进度条，并把「再来一局 / 重新开始」两个入口置灰 */
  function renderCooldown() {
    const locked = isRestartLocked();
    const show = locked && game.state === STATE.GAME_OVER;

    dom.overlayBtn.disabled = show;
    dom.overlayBtn.classList.toggle('is-locked', show);
    dom.btnRestart.disabled = show;
    dom.btnRestart.classList.toggle('is-locked', show);

    if (!dom.cooldown || !dom.cooldownBar) return;

    dom.cooldown.hidden = !show;
    if (show) {
      const progress = Math.min(1, game.lockRemaining / CONFIG.RESTART_LOCK_MS);
      dom.cooldownBar.style.transform = 'scaleX(' + progress.toFixed(4) + ')';
    }
  }

  /* ============================================================
   * 九、渲染
   * ============================================================ */

  const renderer = {
    scale: 1,
    backgroundGradient: null,
  };

  /** 依据容器实际尺寸与设备像素比重算画布分辨率，保证高清且自适应 */
  function resizeCanvas() {
    const rect = dom.canvas.getBoundingClientRect();
    const cssWidth = rect.width > 0 ? rect.width : LOGICAL_SIZE;
    const dpr = window.devicePixelRatio || 1;

    dom.canvas.width = Math.round(cssWidth * dpr);
    dom.canvas.height = Math.round(cssWidth * dpr);
    renderer.scale = dom.canvas.width / LOGICAL_SIZE;

    renderer.backgroundGradient = ctx.createLinearGradient(0, 0, LOGICAL_SIZE, LOGICAL_SIZE);
    renderer.backgroundGradient.addColorStop(0, COLORS.boardTop);
    renderer.backgroundGradient.addColorStop(1, COLORS.boardBottom);
  }

  /**
   * 仅重算棋盘背景渐变（不动画布分辨率）。
   * 本轮需求 4：切主题时画布尺寸没变，但 COLORS 换了一套——
   * 若不重算，渐变里还缓存着旧主题的色标，棋盘会保持深色。
   * setTheme 已把缓存置 null，这里在下一帧渲染前兜一层。
   */
  function refreshBackgroundGradient() {
    if (renderer.backgroundGradient) return;
    renderer.backgroundGradient = ctx.createLinearGradient(0, 0, LOGICAL_SIZE, LOGICAL_SIZE);
    renderer.backgroundGradient.addColorStop(0, COLORS.boardTop);
    renderer.backgroundGradient.addColorStop(1, COLORS.boardBottom);
  }

  /** 绘制棋盘底色 */
  function drawBackground() {
    refreshBackgroundGradient();
    ctx.fillStyle = renderer.backgroundGradient || COLORS.boardBottom;
    ctx.fillRect(0, 0, LOGICAL_SIZE, LOGICAL_SIZE);
  }

  /** 绘制弱化的网格线 */
  function drawGrid() {
    ctx.save();
    ctx.strokeStyle = COLORS.gridLine;
    ctx.lineWidth = 1 / renderer.scale;
    ctx.beginPath();
    for (let i = 1; i < CONFIG.GRID_COUNT; i++) {
      const pos = i * CONFIG.CELL_SIZE;
      ctx.moveTo(pos, 0);
      ctx.lineTo(pos, LOGICAL_SIZE);
      ctx.moveTo(0, pos);
      ctx.lineTo(LOGICAL_SIZE, pos);
    }
    ctx.stroke();
    ctx.restore();
  }

  /** 绘制食物（带呼吸动画与光晕） */
  function drawFood(now) {
    if (!game.food) return;

    const pulse = 0.5 + 0.5 * Math.sin((now / 1000) * 3.2);
    const centerX = game.food.x * CONFIG.CELL_SIZE + CONFIG.CELL_SIZE / 2;
    const centerY = game.food.y * CONFIG.CELL_SIZE + CONFIG.CELL_SIZE / 2;
    const radius = CONFIG.CELL_SIZE * (0.30 + pulse * 0.06);

    ctx.save();
    ctx.shadowColor = COLORS.foodGlow;
    ctx.shadowBlur = CONFIG.CELL_SIZE * (0.45 + pulse * 0.5);

    const gradient = ctx.createRadialGradient(
      centerX - radius * 0.3, centerY - radius * 0.3, radius * 0.15,
      centerX, centerY, radius
    );
    gradient.addColorStop(0, COLORS.foodCore);
    gradient.addColorStop(1, COLORS.foodEdge);

    ctx.fillStyle = gradient;
    ctx.beginPath();
    ctx.arc(centerX, centerY, radius, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }

  /** 绘制蛇头眼睛（朝向随方向变化） */
  function drawEyes(centerX, centerY, direction) {
    const v = DIRECTION_VECTORS[direction];
    const normalX = -v.y;
    const normalY = v.x;
    const sideOffset = CONFIG.CELL_SIZE * 0.20;
    const forwardOffset = CONFIG.CELL_SIZE * 0.12;
    const eyeRadius = CONFIG.CELL_SIZE * 0.115;

    const sides = [1, -1];
    for (let i = 0; i < sides.length; i++) {
      const sign = sides[i];
      const eyeX = centerX + v.x * forwardOffset + normalX * sideOffset * sign;
      const eyeY = centerY + v.y * forwardOffset + normalY * sideOffset * sign;

      ctx.fillStyle = COLORS.eyeWhite;
      ctx.beginPath();
      ctx.arc(eyeX, eyeY, eyeRadius, 0, Math.PI * 2);
      ctx.fill();

      ctx.fillStyle = COLORS.eyePupil;
      ctx.beginPath();
      ctx.arc(eyeX + v.x * eyeRadius * 0.45, eyeY + v.y * eyeRadius * 0.45, eyeRadius * 0.5, 0, Math.PI * 2);
      ctx.fill();
    }
  }

  /** 绘制蛇身（从头到尾渐变 + 圆角） */
  function drawSnake() {
    const isDead = game.state === STATE.GAME_OVER;
    const inset = 1.5;
    const size = CONFIG.CELL_SIZE - inset * 2;
    const radius = size * 0.34;

    ctx.save();
    if (!isDead) {
      ctx.shadowColor = COLORS.snakeShadow;
      ctx.shadowBlur = CONFIG.CELL_SIZE * 0.4;
    }

    for (let i = game.snake.length - 1; i >= 0; i--) {
      const segment = game.snake[i];
      const x = segment.x * CONFIG.CELL_SIZE + inset;
      const y = segment.y * CONFIG.CELL_SIZE + inset;

      let color;
      if (isDead) {
        color = COLORS.snakeDead;
      } else {
        const ratio = game.snake.length > 1 ? i / (game.snake.length - 1) : 0;
        color = lerpColor(COLORS.snakeHead, COLORS.snakeTail, ratio);
      }

      ctx.fillStyle = color;
      roundRectPath(ctx, x, y, size, size, radius);
      ctx.fill();
    }

    ctx.restore();

    // 蛇头眼睛单独绘制（不受阴影影响）
    const head = game.snake[0];
    drawEyes(
      head.x * CONFIG.CELL_SIZE + CONFIG.CELL_SIZE / 2,
      head.y * CONFIG.CELL_SIZE + CONFIG.CELL_SIZE / 2,
      game.direction
    );
  }

  /** 渲染一帧 */
  function render(now) {
    refreshBackgroundGradient();
    ctx.setTransform(renderer.scale, 0, 0, renderer.scale, 0, 0);
    ctx.clearRect(0, 0, LOGICAL_SIZE, LOGICAL_SIZE);
    drawBackground();
    drawGrid();
    drawFood(now);
    drawSnake();
  }

  /* ============================================================
   * 十、主循环
   * ============================================================ */

  function loop(timestamp) {
    const ts = typeof timestamp === 'number' ? timestamp : 0;
    if (game.lastFrameTs === null) game.lastFrameTs = ts;

    let delta = ts - game.lastFrameTs;
    game.lastFrameTs = ts;
    if (delta < 0) delta = 0;
    if (delta > CONFIG.MAX_FRAME_MS) delta = CONFIG.MAX_FRAME_MS;

    if (game.state === STATE.RUNNING) {
      game.accumulator += delta;
      while (game.accumulator >= game.tickMs) {
        game.accumulator -= game.tickMs;
        step();
        if (game.state !== STATE.RUNNING) break;
      }
    }

    // 死亡冷却倒计时：按真实帧间隔递减，归零后自动解锁并恢复按钮文案
    if (game.lockRemaining > 0) {
      game.lockRemaining = Math.max(0, game.lockRemaining - delta);
      const seconds = lockRemainingSeconds();
      if (seconds !== game.lockShownSecond) {
        game.lockShownSecond = seconds;
        syncUi();
      } else if (dom.cooldownBar) {
        const progress = Math.min(1, game.lockRemaining / CONFIG.RESTART_LOCK_MS);
        dom.cooldownBar.style.transform = 'scaleX(' + progress.toFixed(4) + ')';
      }
    }

    render(ts);
    window.requestAnimationFrame(loop);
  }

  /* ============================================================
   * 十一、输入处理
   * ============================================================ */

  /** 键盘方向键与字母键映射 */
  const KEY_DIRECTIONS = {
    ArrowUp: 'up',
    ArrowDown: 'down',
    ArrowLeft: 'left',
    ArrowRight: 'right',
    w: 'up',
    a: 'left',
    s: 'down',
    d: 'right',
  };

  function handleDirectionInput(dir) {
    if (game.state === STATE.READY || game.state === STATE.GAME_OVER || game.state === STATE.WIN) {
      // 冷却期内方向键不触发开新局，也不写入方向缓冲
      if (isRestartLocked()) return;
      startGame();
    } else if (game.state !== STATE.RUNNING) {
      return;
    }
    pushDirection(dir);
  }

  function onKeyDown(event) {
    const key = event.key;

    // 方向键与空格会滚动页面，统一阻止默认行为
    if (
      key === ' ' || key === 'Spacebar' ||
      key === 'ArrowUp' || key === 'ArrowDown' || key === 'ArrowLeft' || key === 'ArrowRight'
    ) {
      event.preventDefault();
    }

    let direction = null;
    if (KEY_DIRECTIONS[key]) {
      direction = KEY_DIRECTIONS[key];
    } else if (KEY_DIRECTIONS[String(key).toLowerCase()]) {
      direction = KEY_DIRECTIONS[String(key).toLowerCase()];
    }

    if (direction) {
      handleDirectionInput(direction);
      return;
    }

    if (key === ' ' || key === 'Spacebar' || key === 'Enter') {
      togglePause();
      return;
    }

    if (key === 'r' || key === 'R') {
      restartGame();
    }
  }

  /** 触摸滑动：记录起点并在结束时判断主方向 */
  const touchState = { startX: 0, startY: 0, tracking: false };

  /** 这次触摸是否落在主题切换按钮上（是则 board 的"点击开局"逻辑必须忽略它）。 */
  function isThemeToggleTouch(event) {
    const t = event.target;
    if (!t || !t.closest) return false;
    return t.closest('#btnTheme') !== null;
  }

  function onTouchStart(event) {
    if (isThemeToggleTouch(event)) { touchState.tracking = false; return; }
    if (!event.touches || event.touches.length !== 1) return;
    touchState.startX = event.touches[0].clientX;
    touchState.startY = event.touches[0].clientY;
    touchState.tracking = true;
  }

  function onTouchEnd(event) {
    if (isThemeToggleTouch(event)) { touchState.tracking = false; return; }
    if (!touchState.tracking || !event.changedTouches || event.changedTouches.length === 0) return;
    touchState.tracking = false;

    const dx = event.changedTouches[0].clientX - touchState.startX;
    const dy = event.changedTouches[0].clientY - touchState.startY;
    if (Math.abs(dx) < CONFIG.SWIPE_THRESHOLD && Math.abs(dy) < CONFIG.SWIPE_THRESHOLD) {
      // 位移过小视为点击：未开始/已结束时点击棋盘即开始新局（冷却期内无效）
      if (game.state === STATE.READY || game.state === STATE.GAME_OVER || game.state === STATE.WIN) {
        if (isRestartLocked()) return;
        startGame();
      }
      return;
    }

    const dir = Math.abs(dx) > Math.abs(dy)
      ? (dx > 0 ? 'right' : 'left')
      : (dy > 0 ? 'down' : 'up');
    handleDirectionInput(dir);
  }

  /** 绑定所有事件 */
  function bindEvents() {
    window.addEventListener('keydown', onKeyDown, { passive: false });

    dom.board.addEventListener('touchstart', onTouchStart, { passive: true });
    dom.board.addEventListener('touchend', onTouchEnd, { passive: true });

    dom.overlayBtn.addEventListener('click', function () {
      if (isRestartLocked()) return;
      if (game.state === STATE.PAUSED) {
        resumeGame();
      } else {
        startGame();
      }
      dom.overlayBtn.blur();
    });

    dom.btnPause.addEventListener('click', function () {
      togglePause();
      dom.btnPause.blur();
    });

    dom.btnRestart.addEventListener('click', function () {
      restartGame();
      dom.btnRestart.blur();
    });

    // 本轮需求 3：退出按钮 → 调宿主注册的回调（= 标题栏返回键的 finish()）。
    // 置灰态由 disabled 拦住，这里不必再判；exitHandler 为 null 时按钮整体隐藏，
    // 走不到这里。
    if (dom.btnExit) {
      dom.btnExit.addEventListener('click', function () {
        if (exitHandler) exitHandler();
        dom.btnExit.blur();
      });
    }

    // 本轮需求 4：深浅色切换按钮（棋盘右上角，见 index.html 的 board）。
    //
    // ⚠️ stopPropagation 必须加：按钮是 #board 的子元素，click/touch 会冒泡到
    // board 的 touchend 处理器——那里把"位移过小的点击"当作开始新局，
    // 这正是"点切换底色会自动开启游戏"的根因。touchstart 与 touchend 两段
    // 都要拦（board 挂的是这两个事件，click 拦截不影响它们）。
    if (dom.btnTheme) {
      dom.btnTheme.addEventListener('click', function (event) {
        event.stopPropagation();
        toggleTheme();
        dom.btnTheme.blur();
      });
      dom.btnTheme.addEventListener('touchstart', function (event) {
        event.stopPropagation();
      });
      dom.btnTheme.addEventListener('touchend', function (event) {
        event.stopPropagation();
      });
    }

    dom.levelGroup.addEventListener('click', function (event) {
      const target = event.target;
      if (!target || !target.getAttribute) return;
      const level = target.getAttribute('data-level');
      if (level) setLevel(level);
    });

    dom.dpad.addEventListener('pointerdown', function (event) {
      const target = event.target;
      if (!target || !target.getAttribute) return;
      const dir = target.getAttribute('data-dir');
      if (!dir) return;
      event.preventDefault();
      handleDirectionInput(dir);
    });

    window.addEventListener('resize', resizeCanvas);
    if (typeof window.ResizeObserver === 'function') {
      new window.ResizeObserver(resizeCanvas).observe(dom.canvas);
    }

    // 切到后台自动暂停，避免回来时被瞬间追帧
    document.addEventListener('visibilitychange', function () {
      if (document.hidden && game.state === STATE.RUNNING) {
        pauseGame();
      }
      game.lastFrameTs = null;
    });
  }

  /* ============================================================
   * 十二、初始化
   * ============================================================ */

  function init() {
    // 主题先于一切 UI 恢复：syncUi / resizeCanvas 都按当前 COLORS 走，
    // 顺序错了首帧会闪一次深色再跳浅色。persist=false：恢复不该重写存档。
    setTheme(loadTheme(), false);
    game.highScore = loadHighScore();
    setLevel(loadLevel());
    resetGame();
    resizeCanvas();
    bindEvents();
    window.requestAnimationFrame(loop);
  }

  init();
})();
