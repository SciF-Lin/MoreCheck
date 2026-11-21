package com.morecheck.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一个发布版本的更新内容。
 *
 * @param isFormal `true` = 正式版，`false` = 预发布（beta）。
 */
private data class ReleaseNote(
    val version: String,
    val date: String,
    val isFormal: Boolean,
    val items: List<String>
)

/**
 * 更新日志的数据。
 *
 * 列**已发布出去的版本** + **当前正在运行的这个版本**（与 `dist/` 的交付记录、README 的
 * 版本号演进表一致）：中间那些只在本机构建、从未发布的 beta（如 `1.1.4 beta`、`1.1.43 beta`）不列入。
 *
 * **写法约定**（这是这一页最要紧的事）：条目要**面向用户**，只讲「能感知到什么变化」，
 * 不写实现细节、不写文件名、不写「改了哪个类」。每个版本 **2~5 条**足矣，
 * 琐碎的修复统一合并成一句「修复了已知问题」，不要逐条罗列。
 * 反例（曾经的写法，已废弃）：「计时器：滚轮支持循环滚动，数字滑出时渐变模糊；按钮按状态切换」——
 * 这是提交信息，不是更新日志。
 */
private val RELEASES: List<ReleaseNote> = listOf(

    ReleaseNote(
        version = "v1.1.70",
        date = "2026-10-14",
        isFormal = true,
        items = listOf(
            "修复：智能体对话页键盘弹起时，输入框位置异常（与键盘之间隔一大段空白）的问题",
            "修复了已知问题，优化了用户体验"
        )
    ),
    ReleaseNote(
        version = "v1.1.69 beta",
        date = "2026-10-13",
        isFormal = false,
        items = listOf(
            "智能体：AI 回复现在支持 Markdown 格式显示（加粗、列表、代码块等），并修复了回答开头出现乱码「null」的问题",
            "智能体：加号菜单新增「拍照」，可以拍照发送图片；键盘弹起时输入框显示异常已修复",
            "记事本悬浮窗：可收成侧边小球，拖动会自动吸附到屏幕边缘；点小球展开，点标题栏图标可跳回记事本页",
            "实验室背景改为跟随深浅色模式轮换：浅色模式用蓝/红/绿等艳色渐变，深色模式用灰/黑/深蓝等深色渐变",
            "修复：同意协议弹窗在打开 / 关闭协议全文时，圆角处显示异常",
            "计时器：设置时间时只显示滚轮，开始计时后只显示剩余时间；暂停 / 继续按钮新增按压反馈与点击间隔（0.3 秒）",
            "关于作者页左下角新增「支持作者」入口",
            "个人信息页新增「选择默认头像」，提供 8 个内置头像，不想上传照片时可一键选用",
            "底部导航栏「日历」更名为「统计」，标题栏右侧新增「记录」入口直达打卡记录"
        )
    ),
    ReleaseNote(
        version = "v1.1.68 beta",
        date = "2026-10-12",
        isFormal = false,
        items = listOf(
            "实验室「智能体」正式上线：AI 对话（支持发送图片），仿微信聊天界面，流式回答可中途停止",
            "智能体内置 DeepSeek、通义千问、小米 MiMo 三个服务商，也支持自定义任何 OpenAI 兼容接口（含本地 Ollama）",
            "智能体：填好密钥后可一键获取可用模型列表，点选即用；对话记录保存在本机",
            "修复：同意 / 撤回协议弹窗点协议返回时的一瞬间闪白",
            "开发者选项的「通知测试」按钮改为固定大小；冷却时显示剩余秒数",
            "计时器：删掉中间横线、改用大号等宽数字并显示「共N分钟」，开始计时后不可再滚动",
            "实验室：长按功能卡片可添加桌面快捷方式（点击直达该功能）",
            "实验室背景改为按时间轮换：白天用浅色渐变、晚上用深色渐变（共 65 套）",
            "记事本：「添加图片」移到标题栏，右上角新增三个点菜单（悬浮窗入口）",
            "记事本：图片可点击全屏预览、上限提到 9 张、长按删除并二次确认",
            "通知类别新增「验证码（测试）」"
        )
    ),
    ReleaseNote(
        version = "v1.1.67 beta",
        date = "2026-10-11",
        isFormal = false,
        items = listOf(
            "修复：在同意弹窗点开《用户协议》后按手机返回键，弹窗会消失、应用卡在空白页无法进入",
            "修复：点《用户协议》的一瞬间整屏闪白（阅读层滑入期间不该改变弹窗底色）"
        )
    ),
    ReleaseNote(
        version = "v1.1.66 beta",
        date = "2026-10-11",
        isFormal = false,
        items = listOf(
            "开发者选项新增「图标更改(实验性)」：四套内置桌面图标可切换、可一键恢复默认，也可上传图片裁切后用于应用内显示",
            "「性能测试」新增开屏「Hello World」显示时长滑块（0~10 秒，0.1 秒一档，可恢复默认）",
            "实验室新增「记事本」：一条笔记，支持文字与图片，返回时自动保存",
            "修复：开屏同意弹窗点《用户协议》短暂白屏；撤回弹窗里《用户协议》点不动",
            "同意与撤回弹窗的正文改为单一样式（去掉「附注块」的备注感）；撤回弹窗禁止上下滑动、也不能下滑关闭"
        )
    ),
    ReleaseNote(
        version = "v1.1.65 beta",
        date = "2026-10-11",
        isFormal = false,
        items = listOf(
            "登录页新增「使用密码登录」：先填手机号、再填密码；另新增「找回密码」页（通过短信验证码）",
            "实验室新增「登录提示功能」，可关闭打开软件时的登录引导",
            "修复计时器反复暂停 / 继续后时间不再推进的问题，计时精度提高到毫秒级",
            "「我的」页会显示你设置的头像，未设置时用灰色头像",
            "撤回隐私同意弹窗的说明更完整；实验室背景新增两套渐变（每次进入轮换）"
        )
    ),
    ReleaseNote(
        version = "v1.1.64 beta",
        date = "2026-10-10",
        isFormal = false,
        items = listOf(
            "实验室新增「开发者选项」（原「登录测试选项」），可调试「验证码测试」等功能",
            "「验证码测试」开启后，点「发送验证码」会随机生成六位验证码并以通知发送，填写正确才能登录",
            "注册后获得 8 位用户 ID，可在「我的信息」中查看",
            "实验室改为渐变背景；「打卡」按钮移到底部；天气显示所在城市与未来 5 天天气图标，定位失败可手动选择城市",
            "同意协议弹窗加高两成并重排文字层次（说明另起一块），同时去掉顶部滑块、增大按钮文字",
            "修复天气搜索：连写整个地址（如「广东省佛山市」「广州市荔湾区」）也能搜到，并补上区县级地名",
            "天气里的地名改为「省-市-区」顺序显示（如「广东-广州-荔湾」）",
            "「支持作者」更名为「关于作者」"
        )
    ),
    ReleaseNote(
        version = "v1.1.63 beta",
        date = "2026-10-10",
        isFormal = false,
        items = listOf(
            "《用户协议》与《隐私政策》弹窗加高，与其它底部弹窗体量一致"
        )
    ),
    ReleaseNote(
        version = "v1.1.62 beta",
        date = "2026-10-10",
        isFormal = false,
        items = listOf(
            "底部弹窗改为从下往上出现，且不再被系统导航键遮挡",
            "修复计时器按钮闪动、倒计时结束不响铃，以及计时页底部内容被裁切的问题",
            "「搜一搜」改为只允许访问 https 并给出风险提示，点标题可复制网址",
            "个人信息页头像支持上传照片并裁切更换",
            "打卡按钮改为横条样式；实验室、登录与设置页体验优化，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.61 beta",
        date = "2026-10-09",
        isFormal = false,
        items = listOf(
            "修复深色模式下启动应用、以及开屏进入主界面时短暂闪白的问题",
            "修复切换二级、三级页面时底部导航栏闪现的问题",
            "《用户协议》与《隐私政策》改为从右侧滑入，不再有残影",
            "统一「撤回隐私同意」倒计时前后的提示文字"
        )
    ),
    ReleaseNote(
        version = "v1.1.60",
        date = "2026-10-09",
        isFormal = true,
        items = listOf(
            "首次启动的《用户协议》与《隐私政策》改为可点击查看全文",
            "同意弹窗与撤回弹窗改为底部弹窗",
            "设置页与二级设置页的文字观感优化",
            "登录、发现与个人信息页体验优化，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.55 beta",
        date = "2026-10-08",
        isFormal = false,
        items = listOf(
            "新增「撤回隐私同意」设置",
            "首次启动需同意《用户协议》与《隐私政策》",
            "登录与注册页体验优化，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.54 beta",
        date = "2026-10-08",
        isFormal = false,
        items = listOf(
            "日历页新增「全年打卡」视图，点击年月即可查看",
            "撤销打卡新增二次确认",
            "计时器按钮适配主题色，新增按压反馈",
            "优化了进度条与底部导航栏的过渡动画，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.53 beta",
        date = "2026-10-08",
        isFormal = false,
        items = listOf(
            // 计时器是 v1.1.52 引入的，这里只能写「升级」，
            // 写成「新增计时器」会让用户以为它第一次出现在 1.1.53。
            "计时器升级：支持循环滚动，新增铃声设置与最近使用时长",
            "新增「权限管理」设置",
            "「搜一搜」支持打开 http 网页",
            "日历页与登录注册页体验优化，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.52 beta",
        date = "2026-10-06",
        isFormal = false,
        items = listOf(
            "主页新增「计时」功能",
            "实验室新增「天气」",
            "新增「数据备份」，支持导出与导入",
            "「看一看」更名为「搜一搜」，优化了底部导航栏动画，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.51 beta",
        date = "2026-10-04",
        isFormal = false,
        items = listOf(
            "新增「扫一扫」功能",
            "实装登录与注册页",
            "新增「更新日志」与「支持作者」入口",
            "完善了《用户协议》，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.50",
        date = "2026-10-03",
        isFormal = true,
        items = listOf(
            "界面风格升级，新增“主题色”设置",
            "修复了已知问题，优化了用户体验"
        )
    ),
    ReleaseNote(
        version = "v1.1.42 beta",
        date = "2026-10-02",
        isFormal = false,
        items = listOf(
            "界面结构调整，标题栏下移到各页面",
            "发现页新增朋友圈入口，修复了已知问题"
        )
    ),
    ReleaseNote(
        version = "v1.1.2",
        date = "2026-09-29",
        isFormal = true,
        items = listOf(
            "优化了页面切换动画的流畅度",
            "首个对外发布的正式版本"
        )
    )
)

/**
 * 更新日志页。
 *
 * 内容**全部内置、不联网**——本应用没有网络能力，所以更新日志随包发布，
 * 由 [RELEASES] 这份数据驱动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更新日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "以下为各版本的更新内容，按时间倒序排列（含当前正在使用的版本）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            RELEASES.forEach { release ->
                ReleaseCard(release)
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ReleaseCard(release: ReleaseNote) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = release.version,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(10.dp))
                ReleaseTag(isFormal = release.isFormal)
                Spacer(Modifier.weight(1f))
                Text(
                    text = release.date,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(12.dp))

            release.items.forEach { item ->
                Row(Modifier.padding(bottom = 6.dp)) {
                    Text(
                        text = "•",
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item,
                        fontSize = 13.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/** 版本类型标签：正式版 / 预发布。 */
@Composable
private fun ReleaseTag(isFormal: Boolean) {
    val container = if (isFormal) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (isFormal) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = if (isFormal) "正式版" else "预发布",
            fontSize = 11.sp,
            color = content
        )
    }
}
