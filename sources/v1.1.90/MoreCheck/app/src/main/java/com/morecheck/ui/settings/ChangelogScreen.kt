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
 * ## 写法（用户多次要求，**先读 HANDOVER 第 4.2 节再来改**）
 *
 * 一句话一条、每条不超过 25 字、同界面的改动合并、新功能排在前、
 * 技术细节一律不写（那是提交信息和 HANDOVER 的事），最后一条恒为
 * 「修复了若干细节问题，优化应用体验」把碎改吞掉。整版 3~6 条。
 *
 * 正例（用户亲自给的 v1.1.68 样板）：
 * ```
 * - 实验室新增 智能体 ，可AI对话
 * - 实验室功能支持桌面快捷方式了
 * - 记事本体验优化
 * - 修复了若干细节问题，优化应用体验
 * ```
 * 反例（v1.1.78 因此返工过一次）：
 * 「修复数独与贪吃蛇、数独2、番茄时钟页面的显示问题：部分机型上棋盘被缩得很小、
 *   数字键盘跑到棋盘右侧、按钮又小又细，现在统一按手机屏幕正常排布（数独改为棋盘在上、键盘在下）」
 * —— 一条塞了三个界面、两个原因、一处做法，用户读不动。
 */
private val RELEASES: List<ReleaseNote> = listOf(

    ReleaseNote(
        version = "v1.1.90",
        date = "2026-10-23",
        isFormal = true,
        items = listOf(
            "日历当天记录去掉「收起」按钮与补打卡提示文字",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.82 beta",
        date = "2026-10-23",
        isFormal = false,
        items = listOf(
            "实验室新增「今日待办」，待办自动保存到云端",
            "热搜浅色样式按设计稿修正，按钮导航更干净",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.81 beta",
        date = "2026-10-22",
        isFormal = false,
        items = listOf(
            "登录按钮加载时匀速转圈，不再看起来卡住",
            "同步管理与数据备份移到设置页顶端",
            "声音设置新增五种内置提示音，不必导入文件",
            "日历翻月与周月切换加入滑动动画",
            "开发者选项可一键开关游戏三项，收起更干净",
            "热搜导航与按钮改回简约样式，原理写进「关于」",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.80 beta",
        date = "2026-10-22",
        isFormal = false,
        items = listOf(
            "登录全面升级：真实手机验证码登录，数据存云端",
            "「我的」页新增「同步管理」，可查看同步状态并手动同步",
            "数据备份支持备份到云端 / 从云端恢复",
            "首次登录可选择上传本地数据或下载云端数据",
            "智能体升级：可导入文档提问，回答标出依据",
            "底部四个入口可左右滑动切换，页面跟随手指、新旧页面连贯滑移",
            "课程表的上课提醒改为标题栏铃铛图标，点击展开设置",
            "热搜新增底部导航与设置页，完整榜单改为二级页面",
            "数独1 支持存档继续、笔记擦除，修复偶发无法继续本局",
            "修复贪吃蛇与数独2 标题栏关不掉的问题",
            "日历可拖底部横条在周视图与月视图之间切换，今天用深色块标出",
            "滑块改为小米风格；「新增功能」按钮改为渐变描边",
            "热搜标题栏与底部导航改用新样式，完整榜单入口改为白底",
            "开发者选项新增「样式设置」，可逐项开关本轮新样式",
            "修复开发者选项二级页点标题栏返回会直接退出的问题",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.79 beta",
        date = "2026-10-21",
        isFormal = false,
        items = listOf(
            "热搜打开时显示加载动画",
            "贪吃蛇准备页可直接切换深浅色",
            "设置新增「清除缓存」，可看占用并一键清理",
            "课程表可点课程看详情，并支持上课前提醒（开始时 / 10 分钟前 / 1 小时前）",
            "修复月历、数独等页面显示异常",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.78 beta",
        date = "2026-10-21",
        isFormal = false,
        items = listOf(
            "修复数独页面显示异常，键盘改为在棋盘下方",
            "修复游戏页底部按钮被导航栏遮挡",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.77 beta",
        date = "2026-10-21",
        isFormal = false,
        items = listOf(
            "实验室新增「热搜」，聚合多个平台的热榜",
            "课程表显示第几教学周，上课前有倒计时",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.76 beta",
        date = "2026-10-20",
        isFormal = false,
        items = listOf(
            "课程表改版，分为今天、本周与全部课程",
            "贪吃蛇体验优化，新增浅色外观",
            "开发者选项新增「游戏设置」总开关",
            "实验室新增「热搜」入口",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.75 beta",
        date = "2026-10-20",
        isFormal = false,
        items = listOf(
            "《隐私政策》重写为正式文本",
            "底部导航栏与发现页不再显示假的红点数字",
            "修复了课程表显示顺序等问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.74 beta",
        date = "2026-10-19",
        isFormal = false,
        items = listOf(
            "实验室新增「数独2」小游戏：另一套内置数独，支持深浅色外观，进度自动保存",
            "实验室新增「课程表」：显示今天的课，可查看详情、搜索，并导出本周课表分享",
            "贪吃蛇、数独、数独2在最近任务里是三个独立的应用卡片",
            "日历页优化：日期格子不再错位；点某一天时打卡记录直接显示在日历下方",
            "开发者选项新增「实验室测试」与「界面测试」分区：可开关打卡动画、页面转场、按压反馈，切换日期显示格式",
            "实验室新增「清空全部数据」与「添加到桌面不再询问」等改进，游戏开屏只显示一次",
            "修复了若干界面细节问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.73 beta",
        date = "2026-10-19",
        isFormal = false,
        items = listOf(
            "实验室新增「番茄时钟」：三档时长，圆环进度，数据自动保存",
            "实验室新增四个小工具：随机数生成、摩斯电码、图片压缩、做个决定",
            "开发者选项新增「游戏标题栏颜色」：跟随系统 / 深色经典 / 深色适配三档可选",
            "数独界面重新适配手机屏幕：棋盘与键盘一屏放得下",
            "修复了已知问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.72 beta",
        date = "2026-10-18",
        isFormal = false,
        items = listOf(
            "实验室新增「数独」小游戏：五档难度、笔记与冲突提示，进度与统计自动保存",
            "智能体新增联网搜索：/search 与 /searchon 命令",
            "统计页改为上下两半：月历滑动翻月，下方可左右切换功能栏目",
            "记事本与智能体的图片查看重做：支持双指缩放与拖动",
            "开发者选项重排，设置项归类更清楚",
            "修复了桌面图标跳转、状态栏图标看不清等多个问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.71 beta",
        date = "2026-10-14",
        isFormal = false,
        items = listOf(
            "实验室新增「贪吃蛇」小游戏：滑动与方向键操作、三档难度，最高分自动保存",
            "底部导航四个入口支持左右滑动切换（带动画）",
            "主页打卡完成后自动跳到统计页；计时页新增进度条，布局优化",
            "全应用统一按压反馈（按下缩放 + 变淡）",
            "开发者选项新增「设备信息查询」；验证码与通知测试收进二级页",
            "「扫一扫」界面美化，智能体聊天界面优化",
            "修复了若干界面细节问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.70",
        date = "2026-10-14",
        isFormal = true,
        items = listOf(
            "修复：智能体对话页键盘弹起时输入框位置异常的问题",
            "修复了已知问题，优化了用户体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.69 beta",
        date = "2026-10-13",
        isFormal = false,
        items = listOf(
            "智能体：AI 回复支持 Markdown 显示，新增拍照发送图片",
            "记事本悬浮窗可收成小球并自动贴边，跳回应用更方便",
            "实验室背景按深浅色模式分池轮换",
            "计时器显示优化，新增按压反馈",
            "个人信息页新增内置头像可选；「支持作者」入口调整",
            "修复了已知问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.68 beta",
        date = "2026-10-12",
        isFormal = false,
        items = listOf(
            "实验室新增 智能体 ，可 AI 对话（支持发送图片、流式回答可中途停止）",
            "实验室功能支持桌面快捷方式了",
            "实验室背景改为按时间轮换（共 65 套渐变）",
            "智能体内置多个服务商，也支持自定义接口；对话记录保存在本机",
            "记事本体验优化：图片预览、上限提升",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.67 beta",
        date = "2026-10-11",
        isFormal = false,
        items = listOf(
            "修复：同意弹窗打开《用户协议》后按返回键卡在空白页的问题",
            "修复：点《用户协议》时整屏闪白的问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.66 beta",
        date = "2026-10-11",
        isFormal = false,
        items = listOf(
            "开发者选项新增「图标更改(实验性)」：内置图标可切换，也可上传图片",
            "实验室新增「记事本」：一条笔记，支持文字与图片",
            "「性能测试」新增开屏显示时长滑块",
            "修复了已知问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.65 beta",
        date = "2026-10-11",
        isFormal = false,
        items = listOf(
            "登录页新增「使用密码登录」与「找回密码」",
            "实验室新增「登录提示功能」，可关闭登录引导",
            "修复计时器暂停后时间不推进的问题",
            "个人信息页会显示设置的头像；实验室背景新增两套渐变"
        )
    ),

    ReleaseNote(
        version = "v1.1.64 beta",
        date = "2026-10-10",
        isFormal = false,
        items = listOf(
            "实验室新增「开发者选项」，可调试「验证码测试」等功能",
            "实验室改为渐变背景；打卡按钮移到底部；天气显示城市与 5 天预报",
            "注册后获得 8 位用户 ID，可在「我的信息」中查看",
            "修复天气搜索与显示格式的问题；协议弹窗体验优化"
        )
    ),

    ReleaseNote(
        version = "v1.1.63 beta",
        date = "2026-10-10",
        isFormal = false,
        items = listOf(
            "《用户协议》与《隐私政策》弹窗加高，显示更完整"
        )
    ),

    ReleaseNote(
        version = "v1.1.62 beta",
        date = "2026-10-10",
        isFormal = false,
        items = listOf(
            "个人信息页头像支持上传照片并裁切更换",
            "「搜一搜」改为只允许访问 https 并给出风险提示",
            "底部弹窗改为从下往上出现，不再被导航键遮挡",
            "修复了计时器与计时页的多个问题"
        )
    ),

    ReleaseNote(
        version = "v1.1.61 beta",
        date = "2026-10-09",
        isFormal = false,
        items = listOf(
            "修复深色模式启动、页面切换时闪白的问题",
            "《用户协议》与《隐私政策》改为从右侧滑入，不再有残影"
        )
    ),

    ReleaseNote(
        version = "v1.1.60",
        date = "2026-10-09",
        isFormal = true,
        items = listOf(
            "首次启动的《用户协议》与《隐私政策》可点击查看全文",
            "同意弹窗与撤回弹窗改为底部弹窗",
            "设置页文字观感优化，修复了已知问题"
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
            "日历页新增「全年打卡」视图",
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
            "「看一看」更名为「搜一搜」，修复了已知问题"
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
