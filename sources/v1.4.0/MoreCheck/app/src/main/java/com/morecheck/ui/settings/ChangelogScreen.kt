package com.morecheck.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一个发布版本的更新内容。
 *
 * @param isFormal `true` = 正式版，`false` = 预发布（beta）。
 */
internal data class ReleaseNote(
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
 * 「修复了若干细节问题，优化应用体验」把碎改吞掉。整版 3~6 条；
 * 多轮改动合并成一个版本发布时按轮次分组，可适度超出（见 v1.3.4 beta）。
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
internal val RELEASES: List<ReleaseNote> = listOf(

    ReleaseNote(
        version = "v1.4.0",
        date = "2026-11-15",
        isFormal = true,
        items = listOf(
            "新增音乐播放：榜单、歌词与离线缓存",
            "新增 B 站视频、热搜与知识问答",
            "官网标题栏更紧凑，网页显示更完整",
            "计时记录、错误日志与设置分类优化",
            "云同步、锁屏与打卡稳定性提升",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.3.9 beta",
        date = "2026-11-14",
        isFormal = false,
        items = listOf(
            "更新后首次打开显示更新介绍",
            "更新介绍可直达安装包下载页",
            "音乐进出确认统一在音乐设置",
            "崩溃与报错日志：琥珀标色可展开",
            "音乐标签加上图标更好认",
            "音乐下载转圈提示、失败可重试",
            "按钮按下变淡变灰更有反馈",
            "设置与开发者选项分类、速览顶对齐",
            "记录页新增计时记录卡",
            "实验室长按可移回发现页",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.3.8 beta",
        date = "2026-11-11",
        isFormal = false,
        items = listOf(
            "音乐页首次进入先看温馨提示",
            "温馨提示确认键带 5 秒倒计时",
            "音乐页开关改为偏方形",
            "关于我们右上角新增链接打开位置",
            "开屏文字恢复字体选择",
            "密码登录提交后自动收起键盘",
            "修复今天不再锁屏仍弹指纹的问题",
            "修复 B 站视频页内容显示不全",
            "移除开发者选项的样式与页面自定义",
            "进入音乐前先弹窗确认",
            "关于我们新增意见反馈问卷",
            "音乐底栏按钮位置与层级优化",
            "音乐底栏按钮增宽",
            "开发者选项可重置音乐确认弹窗",
            "不再提示整行可点",
            "确认弹窗增加进出动画",
            "播放页点任意位置可切视图",
            "长按歌名可复制",
            "退出确认弹窗适配音乐风格",
            "修复确认弹窗卡顿与侧边残影",
            "退出确认弹窗可在音乐设置开关",
            "新增会员音乐播放检测与报告日志",
            "重复提示自动去重与合并",
            "提示避让底部按钮并缩短显示",
            "发现页官网位可选搜一搜或官网",
            "实验室开屏支持逐页分类设置",
            "发现页与实验室入口可互相搬迁",
            "开发者选项新增入口管理页",
            "修复了若干细节问题，优化应用体验"
        )
    ),
    ReleaseNote(
        version = "v1.3.7 beta",
        date = "2026-11-10",
        isFormal = false,
        items = listOf(
            "搜一搜更名为官网，默认打开 www.scif.top",
            "官网不再弹出只能访问 https 的提示",
            "安装包下载页不再显示登录状态横幅",
            "下载进度改在按钮上平滑显示",
            "已有安装包不再重复下载",
            "修复了若干细节问题，优化应用体验"
        )
    ),
    ReleaseNote(
        version = "v1.3.6 beta",
        date = "2026-11-09",
        isFormal = false,
        items = listOf(
            "音乐页浅色主题按钮更清晰",
            "底栏中部播放入口抬高更醒目",
            "热门榜单独立，搜索只出结果",
            "我的收藏支持新建分组",
            "歌词支持逐字点亮",
            "新增桌面歌词悬浮窗",
            "清除音乐缓存前会先确认",
            "实验室功能可以自定义名字",
            "域名比价改为应用内直连，不再依赖线上地址",
            "6 家注册商官网实时价，统一折算人民币比价",
            "首年促销价单独标注，续费与转入价一并给出",
            "域名比价显示真实注册状态，只输后缀或主体也能查",
            "热搜更多平台能读到原文",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.3.5 beta",
        date = "2026-11-09",
        isFormal = false,
        items = listOf(
            "封面与歌词可以互相点击切换",
            "切歌词时封面不再跳动",
            "播放队列有了进出动画",
            "音乐「我的」改为页内嵌",
            "在播时底栏中间显示转圈封面",
            "修复播放页返回偶尔退出音乐",
            "音乐页系统导航栏跟随主题沉浸",
            "开发者选项可开退出二次确认",
            "开发者选项可关音乐入口实时歌词",
            "热搜我的页不再明文显示接口",
            "热搜对比卡等高，新增原文检测",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.3.4 beta",
        date = "2026-11-08",
        isFormal = false,
        items = listOf(
            "音乐主页与播放页重做为黑胶风格",
            "进度条点一下即跳转，点唱片切歌词",
            "新增三套音乐主题，各支持浅深色",
            "按钮成立体渐变，按下只缩图标",
            "歌名下显示两行歌词，收藏缓存移到右下角",
            "主题键换刷子，播放页恢复竖排四键",
            "发现页默认显示热门榜单，我的改页内滑入",
            "播放页按钮移到标题右侧",
            "标题与歌词成组移到左侧",
            "歌词页不再显示标题与歌词栏",
            "歌词栏默认透明，点击才高亮",
            "播放队列默认半屏显示",
            "队列上滑展开到约九成屏高",
            "队列下滑即可关闭",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.3.3 beta",
        date = "2026-11-08",
        isFormal = false,
        items = listOf(
            "点歌自动进播放页，进出有动画",
            "点正在播放的歌直接回播放页",
            "歌词更大了，封面右滑看全屏歌词",
            "播放过的歌再播也能显示歌词",
            "头像进二级页：退出登录与音乐设置",
            "同步与备份合并成一页",
            "打卡提醒增强与个性化移到二级页",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.3.2 beta",
        date = "2026-11-08",
        isFormal = false,
        items = listOf(
            "音乐支持后台播放，设置里可关",
            "通知栏能控制上一首、下一首",
            "新增播放器页：封面、歌词、进度拖动",
            "音乐搜索支持中文与英文",
            "歌曲可下载缓存，断网也能听",
            "新增「最近播放」列表",
            "设置页删掉与开发者选项重复的五项",
            "音乐列表显示专辑封面",
            "缓存分开清理：音乐与其他",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.3.1 beta",
        date = "2026-11-06",
        isFormal = false,
        items = listOf(
            "发现页新增「音乐」：搜索、在线播放、收藏",
            "今日待办与成绩统计支持密码登录",
            "开发者选项一键切换链接打开方式",
            "正式版更新免登录，测试版需登录",
            "安装包页去掉未登录提示，下载提示更简洁",
            "修复锁屏与网页提示的两处反复弹出",
            "优化 B 站视频播放与失败兜底",
            "实验室卡片不再在进入时跳动排序",
            "接入崩溃监控，闪退问题便于定位",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.9 beta",
        date = "2026-11-04",
        isFormal = false,
        items = listOf(
            "凭据不再随系统备份离开设备",
            "修复游戏存档云同步覆盖问题",
            "更新隐私政策与备份说明",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.8 beta",
        date = "2026-11-03",
        isFormal = false,
        items = listOf(
            "B站视频支持横屏、弹幕与评论",
            "B站新增分区频道与搜索热搜",
            "B站可登录，新增「我的下载」",
            "热搜概况可切换跳转平台",
            "实验室支持长按拖拽排序",
            "开发者选项新增界面尺寸尺",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.7 beta",
        date = "2026-11-03",
        isFormal = false,
        items = listOf(
            "B站视频改版：推荐、热门、搜索都能用",
            "B站视频可自选清晰度下载",
            "手势密码输入后稍作停留再解锁",
            "修复忘记密码无法用短信验证码",
            "热搜概况优先抓平台官方 AI 总结",
            "安装包下载页返回时不再刷新",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.6 beta",
        date = "2026-11-03",
        isFormal = false,
        items = listOf(
            "B站视频：修复标题不显示、无法刷新与播放的问题",
            "B站下载增强：新增高清双轨合成回退方案",
            "统计页：修复下方无法左右滑动切换界面",
            "日历：显示前后月日期（灰色）、周视图支持整周切换",
            "退出登录后头像重置为默认灰色",
            "新增密码登录：可用手机号+密码登录云账号",
            "「我的信息」可设置/修改登录密码；忘记密码可用验证码找回"
        )
    ),

    ReleaseNote(
        version = "v1.2.5 beta",
        date = "2026-11-03",
        isFormal = false,
        items = listOf(
            "发现页新增「B站视频」，可在线观看并下载",
            "视频保存到相册 Movies/MoreCheck",
            "热搜改为登录后使用，登录即恢复",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.4 beta",
        date = "2026-11-02",
        isFormal = false,
        items = listOf(
            "登录页新增微信登录，绑定页界面就绪",
            "微信与手机号共用同一份云数据",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.3 beta",
        date = "2026-11-02",
        isFormal = false,
        items = listOf(
            "补打卡可自定义当天的打卡时刻",
            "「距上次打卡」按补卡时刻精确计算",
            "「去AI味」轻度、中度、重度改写差异更分明",
            "更新日志入口可自选放在下载页或关于页",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.2 beta",
        date = "2026-11-02",
        isFormal = false,
        items = listOf(
            "实验室新增「色卡」，中国色与日本色共 776 色",
            "更新日志支持折叠，可一键全部展开",
            "下载页选为检查更新去处时，日志入口随之搬过去",
            "锁屏密码界面重新排版，提示与手势板不再跳动",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.1 beta",
        date = "2026-11-01",
        isFormal = false,
        items = listOf(
            "下载页分正式版与测试版，旧版安装需确认",
            "新增「忘记密码」，指纹或短信验证身份",
            "主界面四个页面支持左右滑动切换",
            "日历选中今天更清楚，圆点不再被裁",
            "实验室调整顺序后菜单不再关闭",
            "锁定时机新增一小时内、今天内不再锁",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.0",
        date = "2026-10-31",
        isFormal = true,
        items = listOf(
            "云同步修复，删除操作不再导致同步失败",
            "待办图片原图上传，长按可保存分享",
            "新增今日待办网页版，网址直接访问",
            "计时页内容垂直居中，按钮更醒目",
            "新增安装包下载页，正式版应用内下载",
            "下载旧版本前会弹窗二次确认",
            "设备信息页重做，部件分类可复制",
            "声音设置重新分组，试听更直观",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.2.0 beta",
        date = "2026-10-31",
        isFormal = false,
        items = listOf(
            "云同步恢复正常，删除操作不再导致同步失败",
            "待办图片改为原图上传，放大不再糊",
            "待办图片长按可保存到相册或分享",
            "待办列表更顺滑，看大图有淡入动画",
            "新增今日待办网页版，可用网址访问",
            "计时页内容垂直居中，按钮更醒目",
            "新增安装包下载页，正式版可在应用内下载",
            "设备信息页重做，分类清晰可一键复制",
            "声音设置重新分组，试听更直观",
            "记录页、菜单与同步按钮细节优化",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.99 beta",
        date = "2026-10-31",
        isFormal = false,
        items = listOf(
            "今日待办：加载有骨架、空内容有提示、删图可撤销",
            "待办图片可长按存相册，看大图时返回只关图",
            "成绩收集不再先闪登录页",
            "热搜：各平台看完整榜，也能在应用内看概况",
            "热搜筛选按钮不再被屏幕边缘切断",
            "开发者选项移除「启用游戏设置」总开关",
            "设备信息页新增章节目录，备份页重新排版",
            "备份「从文件恢复」按钮改回浅底色",
            "锁屏页底部新增「忘记密码」入口",
            "指纹弹窗时提示改为「使用指纹解锁」",
            "锁屏密码关闭时，指纹入口不再可点",
            "打卡热力图显示更完整，任务管理更好懂",
            "日历底部显示距上次打卡，按天与小时计",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.98 beta",
        date = "2026-10-30",
        isFormal = false,
        items = listOf(
            "开屏随机浮动改为设定时长的 ±15%",
            "开发者选项副标题精简为一句话",
            "模拟验证码测试开启前二次确认",
            "热搜页加载与演示数据标识更清晰",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.97 beta",
        date = "2026-10-29",
        isFormal = false,
        items = listOf(
            "打卡与今日待办合并为一个账号，登录一次就通用",
            "锁屏设置改成二级页，指纹相关更集中",
            "热搜演示数据会标明「当前为演示数据」",
            "登录后可挑默认头像，也能自己上传",
            "支持作者的跳转地址可在开发者选项修改",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.96 beta",
        date = "2026-10-28",
        isFormal = false,
        items = listOf(
            "实验室新增「成绩收集」，学生填分、班长管全班",
            "今日待办改版：待办永久保留，可按天回看",
            "待办可加图片备注，点头像看个人资料",
            "待办登录一次就记住，换版本不用重登",
            "修复今日待办、成绩收集的弹窗按钮点不到",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.95 beta",
        date = "2026-10-27",
        isFormal = false,
        items = listOf(
            "锁屏页「指纹登录」改为小按钮，位置更顺手",
            "「去 AI 味」自动挑快模型，轻度几秒出结果",
            "「去 AI 味」思考再久也不会被掐断",
            "「去 AI 味」输入框随文字长高，可看思考过程",
            "打卡进度条中间不再断开",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.94 beta",
        date = "2026-10-27",
        isFormal = false,
        items = listOf(
            "实验室可自定义开屏文字、字号与字体",
            "实验室开屏可逐页开关、单独调时长",
            "解锁新增淡入动画，可设置锁定时机",
            "开发者选项重新分类，设置更好找",
            "开关恢复主题色，主按钮恢复白字",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.93 beta",
        date = "2026-10-27",
        isFormal = false,
        items = listOf(
            "热搜新增腾讯新闻热点",
            "实验室新增 GitHub 热门项目",
            "实验室新增「去 AI 味」改写",
            "主按钮与进度条显示更清晰",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.92 beta",
        date = "2026-10-26",
        isFormal = false,
        items = listOf(
            "开屏密码新增 Hello World 开屏，可关闭",
            "开屏密码输错会震动并变红，可关闭",
            "指纹弹窗文字可自定义、预览与清空",
            "设置新增 4 种主题色",
            "修复了若干细节问题，优化应用体验"
        )
    ),

    ReleaseNote(
        version = "v1.1.91 beta",
        date = "2026-10-26",
        isFormal = false,
        items = listOf(
            "设置新增「开屏密码」，支持手势与指纹",
            "课程表新增本月视图，可一键回到今日",
            "实验室长按可排序，热搜移到发现页",
            "记事本显示字数与保存时间，可分享",
            "游戏标题栏新增回到主页按钮",
            "修复了若干细节问题，优化应用体验"
        )
    ),

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
 * 按版本号找一条更新日志（用于「更新后首次启动」的介绍弹窗）。
 *
 * 入参兼容两种写法：`1.3.9 beta` / `v1.3.9 beta`——[BuildConfig.VERSION_NAME] 不带 `v`，
 * 而 [RELEASES] 里统一带 `v`，两边对齐放在这个函数里做，调用点不必各自处理。
 */
internal fun releaseNoteFor(version: String): ReleaseNote? {
    val target = version.trim().removePrefix("v")
    return RELEASES.firstOrNull { it.version.removePrefix("v") == target }
}

/**
 * 更新日志页。
 *
 * 内容**全部内置、不联网**——本应用没有网络能力，所以更新日志随包发布，
 * 由 [RELEASES] 这份数据驱动。
 *
 * ## 折叠（2026-11-02 需求 2）
 *
 * 版本一多，平铺就是三屏长的滚动墙：想找"上一版改了什么"要一路划过去。
 * 所以每个版本可折叠，**默认只展开最新一版**（点进来通常就想知道这次改了什么），
 * 其余折叠成"版本号 + 日期 + 一条摘要"——摘要取该版第一条改动，
 * 让人不展开也能判断要不要展开。
 *
 * 标题栏右侧给「全部展开 / 全部收起」：想通读时一键铺开，想扫标题时一键收拢。
 * 两种状态由当前列表**实际是否全展开**推导，不额外存一个布尔
 * （存布尔就会出现"手动收了中间一版，按钮却还写着全部收起"的错位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    // 每个版本的展开状态。缺省（map 里没有）时按「是不是最新一版」算，
    // 这样"重置"只需清表，不必在初始化时逐条写入。
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val newestVersion = RELEASES.firstOrNull()?.version
    fun isExpanded(version: String): Boolean =
        expanded[version] ?: (version == newestVersion)

    val allExpanded = RELEASES.isNotEmpty() && RELEASES.all { isExpanded(it.version) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("更新日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val target = !allExpanded
                        RELEASES.forEach { expanded[it.version] = target }
                    }) {
                        Text(
                            text = if (allExpanded) "全部收起" else "全部展开",
                            fontSize = 13.sp
                        )
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
                text = "以下为各版本的更新内容，按时间倒序排列（含当前正在使用的版本）。" +
                    "点击版本号可展开或收起。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            RELEASES.forEach { release ->
                val open = isExpanded(release.version)
                ReleaseCard(
                    release = release,
                    expanded = open,
                    // ⚠️ 写的是**显式值**而不是取反：`open` 是本次组合算出来的，
                    // 连点两下时第二下必须落到"收起"，不能被旧状态带成又展开。
                    onToggle = { expanded[release.version] = !open }
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 一个版本的卡片。
 *
 * @param expanded 是否展开（内容可见）。
 * @param onToggle 点标题行切换；**整行**都是热区，不是只有右侧那枚箭头——
 *   折叠列表里最气人的就是"点了没反应"。
 */
@Composable
private fun ReleaseCard(release: ReleaseNote, expanded: Boolean, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Spacer(Modifier.width(2.dp))
                // 箭头只做"可展开"的提示，旋转动画很轻（150ms 插值），
                // 不跟着做高度动画——列表里同时展开多张卡片时那样会很晃。
                val arrow by animateFloatAsState(
                    targetValue = if (expanded) 180f else 0f,
                    label = "changelog-arrow"
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(arrow)
                )
            }

            // 折叠态给一条摘要：不展开也能判断"这版跟我有没有关系"。
            if (!expanded) {
                Text(
                    text = release.items.firstOrNull().orEmpty(),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
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
