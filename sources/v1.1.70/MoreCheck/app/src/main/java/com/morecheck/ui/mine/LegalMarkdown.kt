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
 * 内容逐字照录自 `C:\Users\wr202\Desktop\用户协议.md`，**未改动任何条款、公司主体或版本号**
 * （文中「版本号：V1.1.5」与应用的 1.1.50 不一致，属原文如此）。
 * 需要修订条款时直接改这里的文本即可，渲染由 [LegalMarkdownBody] 负责。
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



**更新日期：【2026 年 10 月 4 日】**
**生效日期：【2026 年 10 月 4 日】**
**版本号：V1.1.5**



欢迎您使用 **MoreCheck**！

**重要提示：**
请您仔细阅读以下全部内容。如果您不同意本协议的任一或全部条款内容，请不要进行后续操作或使用本服务。当您点击“同意”、“下一步”或开始使用/注册本服务时，即表示您已充分理解并同意接受本协议的全部内容。

## 一、总则

1.1 本协议是您（以下简称“用户”或“您”）与 **石门科技有限公司**（以下简称“我们”或“平台”）之间关于您使用 **MoreCheck** 所订立的有效协议。

1.2 本协议内容包括协议正文及所有我们已经发布或将来可能发布的各类规则、政策、通知、声明等。所有规则为本协议不可分割的组成部分，与本协议正文具有同等法律效力。

## 二、账号注册与管理

2.1 **注册资格**：您确认，在您开始注册程序使用本服务前，您应当具备中华人民共和国法律规定的与您行为相适应的民事行为能力。若您不具备前述主体资格，则您及您的监护人应依照法律规定承担因此而导致的一切后果。

2.2 **账号信息**：您在注册时应提供真实、准确、完整的个人信息。注册信息发生变更时，您应及时更新。如因您提供的注册信息不实或不完整导致无法享受服务或产生其他损失，由您自行承担。

2.3 **账号安全**：您有责任妥善保管您的账号及密码。任何通过您账号进行的操作均视为您本人的行为，由此产生的法律责任由您本人承担。如发现他人未经授权使用您的账号，请立即通知我们。

2.4 **账号注销**：您有权申请注销您的账号。账号注销后，我们将停止为您提供服务，并依据法律法规的要求处理您的个人信息。账号注销后，相关的数据记录可能无法恢复。

## 三、服务内容与规范

3.1 **服务内容**：我们向您提供 **本地打卡** 服务。具体服务内容以我们实际提供的为准。

3.2 **服务许可**：我们授予您一项非排他性、不可转让、可撤销的许可，允许您在本协议有效期内，仅为个人或合法商业目的使用本服务。

3.3 **用户行为规范**：

* 您承诺不会利用本服务从事任何违反国家法律法规、社会公德或侵犯第三方合法权益的行为。
* 您不得利用本服务制作、上传、复制、发布、传播任何违法、违规、色情、暴力、诽谤、侵权等信息。
* 您不得干扰或破坏本服务的正常运行，包括但不限于使用恶意软件、进行网络攻击、发送大量垃圾信息等。
* 您不得对本服务的任何部分进行反向工程、反编译、反汇编或试图获取源代码。

3.4 **内容所有权**：您通过本服务上传、发布的内容（如文档、图片、视频等），其知识产权归您所有或您已获得合法授权。您授予我们一项全球范围内的、免费的、非排他性的许可，以便我们能为您提供本服务（例如：存储、备份、展示您的文件）。

## 四、隐私保护

4.1 我们非常重视您的隐私保护。我们将按照 **隐私政策** 的规定收集、使用、存储和保护您的个人信息。请您仔细阅读并了解我们的隐私政策。

4.2 我们不会将您的个人信息非法出售或提供给第三方，除非：

* 获得您的明确同意；
* 根据法律法规、司法或行政命令的要求；
* 为维护公共安全、社会公共利益或平台自身合法权益所必需。

## 五、知识产权

5.1 本服务中包含的所有软件、技术、界面设计、商标、标识、文案、音频、视频等，其知识产权（包括但不限于著作权、专利权、商标权、商业秘密等）均归我们或我们的关联公司所有。

5.2 未经我们书面许可，您不得以任何方式复制、修改、传播、销售或用于其他商业用途。

## 六、免责声明

6.1 本服务按“现状”和“可用性”提供。我们不保证服务在任何时候都不中断、无错误或完全安全。

6.2 在法律允许的最大范围内，我们不对因下列原因导致的任何直接、间接、附带、特殊或惩罚性损害承担责任：

* 您使用或无法使用本服务；
* 任何第三方的行为或内容；
* 您未能妥善保管账号密码；
* 不可抗力事件（如自然灾害、战争、政府行为、互联网故障等）。

## 七、协议的变更与终止

7.1 **协议变更**：我们有权根据需要不时地修订本协议。协议变更后，如果您继续使用本服务，即视为您已接受修改后的协议。如您不同意变更，应停止使用本服务。

7.2 **服务终止**：我们有权在提前 **30天** 通知的情况下，终止本服务的运营。届时，我们将协助您处理数据迁移事宜。

7.3 **违约终止**：如果您违反本协议的任何条款，我们有权立即暂停或终止您的账号，并删除相关内容，无需承担任何责任。

## 八、法律适用与争议解决

8.1 本协议的订立、执行、解释及争议的解决均应适用中华人民共和国法律。

8.2 因本协议引起的或与之有关的任何争议，双方应首先友好协商解决；协商不成的，任何一方均有权将争议提交至 **广东省广州市** 有管辖权的人民法院诉讼解决。

8.3 本政策存在中文与其他语言版本的，**以中文版本为准**。



## 九、联系我们

如您对本协议有任何疑问、意见或建议，请通过以下方式与我们联系：

* **邮箱**：暂未提供
* **地址**：暂未提供



**生效日期：【2026 年 10 月 4 日】**
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
