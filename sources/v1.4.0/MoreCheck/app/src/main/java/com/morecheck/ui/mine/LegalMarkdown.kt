package com.morecheck.ui.mine

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 用户协议全文（Markdown 原文）。
 *
 * ## 版本沿革
 *
 * - 最初照录自 `C:\Users\wr202\Desktop\用户协议.md`（V1.1.5，2026-10-04），
 *   当时只描述了"本地打卡服务"，条款范围与功能面严重不匹配。
 * - **2026-10-27 升到 V1.2.0**（用户要求「根据软件功能情况更新用户协议」）：
 *   补齐了本地打卡之外的真实服务范围——实验室（天气 / 智能体 / 热搜 / GitHub 热门 /
 *   去 AI 味 / 今日待办 / 小游戏）、可选云同步、本机登录态与云账号的区别、
 *   第三方服务与外部链接、AI 生成内容的责任划分、应用锁（锁屏密码）的安全边界、
 *   开源组件许可、未成年人保护、数据本地存储与备份风险。
 *
 * ## 改条款时的两个硬约束
 *
 * 1. 公司与管辖法院（石门科技有限公司 / 广东省广州市）是既有事实，不要改动；
 * 2. 「联系方式」仍是「暂未提供」——这是发布阻断项（见 `docs/规划与待办.md`），
 *    不要把占位文本改成编造的邮箱。
 *
 * 渲染由 [LegalMarkdownBody] 负责，能力有限（见那个函数的 KDoc）。
 */
// ⚠️ 正文里「更新日期 / 生效日期 / 版本号」这三行的 `**` 必须**各自成对、不能跨行**。
//
// `LegalMarkdownBody` 是按行解析 `**` 的（见 `inlineBold`），一对 `**` 跨行时，
// 每一行都只有落单的星号，于是会被原样打印出来——实机上就出现过
// 「**更新日期：【…】」这种字面星号。修法是让三行各自成对。
//
// 另外注意：**不要在这段三引号原始字符串里写双斜杠注释**——
// 原始字符串不认注释，那些字符会原样进入协议正文（这个坑真的踩过一次：
// 一条说明性注释被当成条款渲染了出来）。要写注释就写在这段字符串**之前**。
const val AGREEMENT_MARKDOWN: String = """
# 用户服务协议



**更新日期：【2026 年 10 月 27 日】**
**生效日期：【2026 年 10 月 27 日】**
**版本号：V1.2.0**



欢迎您使用 **MoreCheck**！

**重要提示：**
请您仔细阅读以下全部内容，**特别是以加粗方式提示的条款**。如果您不同意本协议的任一或全部条款内容，请不要进行后续操作或使用本服务。当您点击“同意”、“下一步”或开始使用/注册本服务时，即表示您已充分理解并同意接受本协议的全部内容。

## 一、总则

1.1 本协议是您（以下简称“用户”或“您”）与 **石门科技有限公司**（以下简称“我们”或“平台”）之间关于您使用 **MoreCheck**（以下简称“本 App”）所订立的有效协议。

1.2 本协议内容包括协议正文及所有我们已经发布或将来可能发布的各类规则、政策、通知、声明等。所有规则为本协议不可分割的组成部分，与本协议正文具有同等法律效力。

1.3 **本 App 的数据默认保存在您的设备本地**。除您主动开启的「云同步」与主动登录的「实验室 → 今日待办」外，本 App 不向我们的服务器上传您的打卡数据。具体的数据处理规则以《隐私政策》为准，《隐私政策》是本协议不可分割的组成部分。

## 二、账号与登录

2.1 **本机登录态**：您可以在本 App 内填写手机号码、昵称、性别与密码，生成一份**仅保存在本机**的登录态与个人资料。它不是服务器账号，不发送至任何服务器，卸载本 App 或清除应用数据后即消失。

2.2 **云账号（可选）**：您可以选择用手机号码 + 短信验证码登录云账号，用于多设备同步。云账号与上述本机登录态是两套独立体系。您应妥善保管账号与验证码，通过您账号进行的操作视为您本人的行为。

2.3 **账号注销**：您可以随时在「云同步」页面退出登录；如需删除云端数据，可通过本协议第九条的方式联系我们处理。退出登录或删除云端数据不影响您设备本地的数据。

2.4 **注册资格**：您确认，在您开始使用本服务前，您应当具备中华人民共和国法律规定的与您行为相适应的民事行为能力。若您不具备前述主体资格，请在监护人陪同下阅读本协议并使用本服务。

## 三、服务内容

3.1 **核心功能（本地）**：打卡任务的创建与管理、打卡与记录、补打卡、统计、计时器、记事本、课程表等。上述功能的数据全部保存在您的设备本地。

3.2 **实验室功能（部分需要联网）**：本 App 设有「实验室」，其中包含若干工具与实验性功能，例如天气查询、智能体对话、热搜聚合、GitHub 热门项目、去 AI 味（文本改写）、今日待办、若干小游戏等。**其中部分功能需要向第三方服务发送必要数据**（如天气查询需要位置、智能体与去 AI 味需要您输入的文本），具体清单与接收方见《隐私政策》第三条。这些功能由您**主动进入并主动操作**时才产生网络请求。

3.3 **云同步（可选，默认关闭）**：开启后，任务、打卡记录、记事本文字、应用设置与游戏存档会在您的多台设备之间同步。不开启时本 App 完全离线可用。

3.4 **实验性功能**：标注为「实验性」或以开发者选项形式提供的功能，可能随时调整、变更或移除，我们不承诺其长期可用性与稳定性。您理解并同意，此类功能不构成对本协议第 3.1 条核心服务的承诺。

3.5 **服务内容以我们实际提供的为准**。我们有权根据业务需要新增、调整或下线具体功能，并通过应用内更新日志等方式告知。

## 四、使用规范

4.1 您承诺不会利用本服务从事任何违反国家法律法规、社会公德或侵犯第三方合法权益的行为。

4.2 您不得利用本服务制作、上传、复制、发布、传播任何违法、违规、色情、暴力、诽谤、侵权等信息。

4.3 您不得干扰或破坏本服务的正常运行，包括但不限于使用恶意软件、进行网络攻击、发送大量垃圾信息等。

4.4 **您不得对本服务的任何部分进行反向工程、反编译、反汇编或试图获取源代码**，但法律法规允许或相关开源许可证明确许可的情形除外（本 App 使用的开源组件遵循其各自的许可证）。

4.5 使用需要联网的功能时，您应自行承担相应的流量费用，并遵守该第三方服务的使用条款。因第三方服务限制、封禁或变更导致的不可用，不属于我们的违约。

## 五、您的内容与生成内容

5.1 您在任务、备注、记事本、头像、对话等位置填写或上传的内容，其权利归您所有或您已获得合法授权。您授予我们一项有限的、非排他性的许可，**仅为向您提供本服务所必需的范围**（例如：把您的内容加密传输并在您的设备之间同步）而处理这些内容。

5.2 **我们不主张对您的内容享有任何所有权**，也不会将其用于广告投放、模型训练或向第三方出售。

5.3 智能体对话与「去 AI 味」的改写结果由第三方大模型生成。**生成内容不构成我们的观点**，我们不对其准确性、完整性或适用性作出保证。您应自行判断并对使用结果负责，不得将其用于违法违规用途。

5.4 您应确保您提交给联网功能的内容不包含他人的个人信息、商业秘密或其他受法律保护的信息；因您提交内容引发的纠纷由您自行承担。

## 六、第三方服务与外部链接

6.1 本 App 的部分功能依赖第三方服务（如天气数据、热搜榜单、GitHub 检索、云服务与云端大模型）。这些服务由其各自运营方提供，我们无法保证其持续可用、内容准确或完全符合您的预期。

6.2 本 App 展示的热搜、GitHub 热门项目等内容**均来自公开接口**，仅作信息展示，不代表我们对其内容作出推荐、认可或背书。您因访问这些内容或跳转至对应网站所产生的后果，由您与相应网站自行处理。

6.3 点击外部链接会跳转至系统浏览器或其他应用，该过程不再受本协议约束，请您遵守相应网站的服务条款与隐私政策。

## 七、隐私保护

7.1 我们非常重视您的隐私保护。我们将按照《隐私政策》的规定收集、使用、存储和保护您的个人信息。请您仔细阅读并了解我们的隐私政策。

7.2 **本 App 的核心设计是数据本地优先**：不收集设备标识符，不读取通讯录与短信正文，不接入第三方广告、统计或推送 SDK，不出售您的个人信息。

7.3 我们不会将您的个人信息非法出售或提供给第三方，除非：

* 获得您的明确同意；
* 为向您提供您主动使用的功能所必需（详见《隐私政策》第三条的接收方清单）；
* 根据法律法规、司法或行政命令的要求；
* 为维护公共安全、社会公共利益或平台自身合法权益所必需。

7.4 **应用锁（锁屏密码）**：您可在设置中开启手势或指纹解锁。手势图案以加盐哈希形式保存在本机，我们不存储图案本身；指纹校验由操作系统完成，本 App 不接触您的指纹数据。该功能用于降低他人临时拿起设备后查看内容的可能，**不构成对本地数据的安全承诺**，请勿在设备已 Root 或已被他人控制的情况下依赖它。

## 八、知识产权

8.1 本服务中包含的所有软件、技术、界面设计、商标、标识、文案等，其知识产权（包括但不限于著作权、专利权、商标权、商业秘密等）均归我们或我们的关联公司所有。

8.2 未经我们书面许可，您不得以任何方式复制、修改、传播、销售或用于其他商业用途。

8.3 本 App 内嵌的开源组件、字体与网页素材，其权利归各自权利人所有，并遵循其各自的开源许可证。

## 九、联系我们

如您对本协议有任何疑问、意见或建议，或希望行使查阅、更正、删除个人信息、注销云账号等权利，请通过以下方式与我们联系：

* **邮箱**：暂未提供
* **地址**：暂未提供

我们将在收到您的请求后 **15 个工作日**内予以答复。

## 十、免责声明

10.1 本服务按“现状”和“可用性”提供。我们不保证服务在任何时候都不中断、无错误或完全安全。

10.2 **打卡数据保存在您的设备本地**，请您自行做好备份（可使用应用内的备份功能）。设备丢失、损坏、卸载应用、清除应用数据或被他人操作导致的数据丢失，我们无法恢复。

10.3 在法律允许的最大范围内，我们不对因下列原因导致的任何直接、间接、附带、特殊或惩罚性损害承担责任：

* 您使用或无法使用本服务；
* 任何第三方的行为或内容；
* 您未能妥善保管账号密码；
* 不可抗力事件（如自然灾害、战争、政府行为、互联网故障等）。

## 十一、协议的变更与终止

11.1 **协议变更**：我们有权根据需要不时地修订本协议。协议变更后，如果您继续使用本服务，即视为您已接受修改后的协议。如您不同意变更，应停止使用本服务。重大变更我们会在应用内以显著方式提示。

11.2 **服务终止**：我们有权在提前 **30天** 通知的情况下，终止本服务的运营。届时，我们将协助您处理数据迁移事宜。

11.3 **违约终止**：如果您违反本协议的任何条款，我们有权立即暂停或终止您的账号，并删除相关内容，无需承担任何责任。

11.4 **您可随时终止**：卸载本 App 即可终止使用；如已开启云同步，建议先在应用内退出登录。

## 十二、未成年人保护

12.1 若您是未满 14 周岁的未成年人，请在监护人的陪同下阅读本协议，并在取得监护人同意后使用本服务。

12.2 监护人对其监护的未成年人使用本服务的行为负有指导与监督责任。如未成年人使用需联网的功能，监护人应予以关注。

## 十三、法律适用与争议解决

13.1 本协议的订立、执行、解释及争议的解决均应适用中华人民共和国法律。

13.2 因本协议引起的或与之有关的任何争议，双方应首先友好协商解决；协商不成的，任何一方均有权将争议提交至 **广东省广州市** 有管辖权的人民法院诉讼解决。

13.3 本政策存在中文与其他语言版本的，**以中文版本为准**。



**生效日期：【2026 年 10 月 27 日】**
"""
/**
 * 极简 Markdown 渲染器：只实现这两份法律文本真正用到的语法。
 *
 * 支持：`#` / `##` / `###` 三级标题、行内 `**粗体**`、`* ` 与 `- ` 项目符号、
 * 空行 = 段间距；其余每行按独立段落渲染。
 *
 * 「逐行成段」是刻意的：现有的隐私政策文案也是逐行成句的，
 * 这样协议页与隐私政策页的观感一致，不会因为换渲染器而突变。
 *
 * **不支持**表格、图片、代码块、多级列表、超链接——将来正文变复杂时应换成完整的 Markdown 库。
 */
@Composable
fun LegalMarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        markdown.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(10.dp))

                line.startsWith("### ") ->
                    LegalHeading(line.removePrefix("### "), 15.sp, topGap = 12.dp)

                line.startsWith("## ") ->
                    LegalHeading(line.removePrefix("## "), 17.sp, topGap = 18.dp)

                line.startsWith("# ") ->
                    LegalHeading(line.removePrefix("# "), 21.sp, topGap = 0.dp)

                line.startsWith("* ") || line.startsWith("- ") -> LegalBullet(line.drop(2))

                else -> Text(
                    text = inlineBold(line),
                    fontSize = 14.sp,
                    lineHeight = 23.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun LegalHeading(text: String, size: TextUnit, topGap: Dp) {
    if (topGap > 0.dp) Spacer(Modifier.height(topGap))
    Text(
        text = text,
        fontSize = size,
        lineHeight = size * 1.35f,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun LegalBullet(text: String) {
    Row(
        modifier = Modifier.padding(start = 2.dp, bottom = 4.dp)
    ) {
        Text(
            text = "•",
            fontSize = 14.sp,
            lineHeight = 23.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = inlineBold(text),
            fontSize = 14.sp,
            lineHeight = 23.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 把行内的 `**粗体**` 解析成 [AnnotatedString]，其余部分原样保留。
 *
 * 不做完整解析：只处理成对出现的 `**`，落单的星号按普通字符输出（法律文本里不会出现
 * 需要转义的星号，出现时按字面显示也更安全）。
 */
private fun inlineBold(line: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < line.length) {
        val start = line.indexOf("**", index)
        if (start < 0) {
            append(line.substring(index))
            return@buildAnnotatedString
        }
        val end = line.indexOf("**", start + 2)
        if (end < 0) {
            append(line.substring(index))
            return@buildAnnotatedString
        }
        append(line.substring(index, start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(line.substring(start + 2, end))
        }
        index = end + 2
    }
}
