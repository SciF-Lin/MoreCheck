package com.morecheck.ui.lab.tools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.morecheck.ui.theme.strongTextColor

/**
 * 「实验室 → 摩斯电码」。
 *
 * ## 为什么输入即转换，没有「转换」按钮
 *
 * 摩斯电码的编/解码都是**纯函数、零成本**（没有网络、没有 IO、没有大计算），
 * 一个显式按钮只会让用户多点一次。反过来说，若将来这里接了需要请求的能力，
 * 才应该退回按钮式——本页的距离还很远。
 *
 * ## 分隔约定（编码与解码必须对称，否则一来一回就丢信息）
 *
 * - 编码：字母之间用**一个空格**，单词之间用 **`/`**（前后各留一个空格）。
 * - 解码：按 `/` 切单词，再按**任意长度的空白**切字母。
 *   多个空格、制表符、换行都能容忍——因为用户是从别处粘贴摩斯串进来的，
 *   粘贴内容里出现连续空格是常态，为这个报错毫无意义。
 *
 * ## 斜杠为什么可以同时在表里、又当分隔符
 *
 * 两者用的是**不同的表示**，不会冲突：
 * - 「单词分隔符」是摩斯串里的**字面斜杠字符**（如 `.- / -...`）；
 * - 「斜杠这个字符的编码」是摩斯符号 `-..-.`（点与横，不含任何斜杠字符）。
 *
 * 因此编码 `A/B` 得到 `.- -..-. -...`，解码时先按字面 `/` 切单词（这里一个都没有），
 * 再按空白切字母，`-..-.` 正常还原成 `/` —— 往返等价。
 * 反过来说，任何字母的摩斯码都不可能是字面 `/`，所以两条规则永远不会抢同一个 token。
 *
 * ## 大小写
 *
 * 编码前统一转大写：摩斯码没有大小写概念，`a` 与 `A` 必须得到同一个结果。
 * 解码输出的都是大写字母——这是唯一不会「自作主张」的选择：若按某种规则还原小写，
 * 用户看到的就不再是他自己输入的东西了。
 *
 * ## 未知字符的处理
 *
 * 编码时遇到表里没有的字符（如中文、emoji）**跳过一个占位**并计入提示，
 * 而不是静默丢弃整串——静默丢弃会让用户以为程序坏了；
 * 解码时遇到无法识别的码同样用 `?` 占位，并在结果下方说明有几处没解出来。
 *
 * @param onBack 返回上一级（实验室宫格）。
 * @param onMessage 页面级一次性提示（复制成功等），走全局 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MorseCodeScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    // 系统返回键等价于左上角箭头，避免用户被卡在本页。
    BackHandler(onBack = onBack)

    // 两种输入各自独立保存：来回切标签页时，用户刚敲的内容必须还在。
    // 若共用一个输入变量，切换方向就得把文本清掉（否则上一次的摩斯串会被当明文重新编码），
    // 那才是真的丢东西。
    var plainText by rememberSaveable { mutableStateOf("") }
    var morseText by rememberSaveable { mutableStateOf("") }
    var encodeMode by rememberSaveable { mutableStateOf(true) }
    var tableExpanded by rememberSaveable { mutableStateOf(false) }

    // LocalClipboardManager 在**组合期**读取（不能放进 remember{} 的 lambda 里，
    // 那个 lambda 可能在没有组合上下文时执行——本项目的历史坑）。
    val clipboard = LocalClipboardManager.current

    val encoded = encodeToMorse(plainText)
    val decoded = decodeFromMorse(morseText)

    ToolScreen(title = "摩斯电码", onBack = onBack) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = encodeMode,
                onClick = { encodeMode = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = { SegmentedButtonDefaults.Icon(active = encodeMode) }
            ) {
                Text("文字 → 摩斯", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            SegmentedButton(
                selected = !encodeMode,
                onClick = { encodeMode = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = { SegmentedButtonDefaults.Icon(active = !encodeMode) }
            ) {
                Text("摩斯 → 文字", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (encodeMode) {
            ToolCard(title = "输入文字") {
                OutlinedTextField(
                    value = plainText,
                    onValueChange = { plainText = it },
                    label = { Text("要编码的文字") },
                    placeholder = { Text("例如 SOS") },
                    minLines = 3,
                    // 摩斯码没有大小写，但输入时自动大写能让用户提前看到最终形态。
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ToolHint("支持 A-Z、0-9 与常用标点；字母间空格、单词间用 /")
            }

            Spacer(Modifier.height(12.dp))

            ToolCard(title = "摩斯电码") {
                Text(
                    text = encoded.output.ifEmpty { "（输入后这里实时显示）" },
                    style = MaterialTheme.typography.bodyLarge,
                    // 摩斯串里全是点与横，等宽字体才分得清间距；否则「...」与「. . .」
                    // 在比例字体下几乎一样宽，用户无法判断是不是一个字母。
                    fontFamily = FontFamily.Monospace,
                    color = if (encoded.output.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.strongTextColor
                    }
                )
                if (encoded.skipped > 0) {
                    Spacer(Modifier.height(8.dp))
                    ToolHint("有 ${encoded.skipped} 个字符没有对应摩斯码，已用 ? 占位")
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(encoded.output))
                            onMessage("已复制摩斯码")
                        },
                        enabled = encoded.output.isNotEmpty()
                    ) {
                        Text("复制")
                    }
                    OutlinedButton(
                        onClick = { plainText = "" },
                        enabled = plainText.isNotEmpty()
                    ) {
                        Text("清空")
                    }
                }
            }
        } else {
            ToolCard(title = "输入摩斯码") {
                OutlinedTextField(
                    value = morseText,
                    onValueChange = { morseText = it },
                    label = { Text("点用 .，横用 -") },
                    placeholder = { Text("例如 ... --- ...") },
                    minLines = 3,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                // 键盘上找不到「-」时用户会卡住，这里直接给一键插入，
                // 比让用户切到符号面板再翻找省事得多。
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { morseText += "." }) { Text("插入 .") }
                    OutlinedButton(onClick = { morseText += "-" }) { Text("插入 -") }
                    OutlinedButton(onClick = { morseText += " / " }) { Text("插入 /") }
                }
                Spacer(Modifier.height(8.dp))
                ToolHint("字母间用空格，单词间用 /；多余空格会自动忽略")
            }

            Spacer(Modifier.height(12.dp))

            ToolCard(title = "解码结果") {
                Text(
                    text = decoded.output.ifEmpty { "（输入后这里实时显示）" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (decoded.output.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.strongTextColor
                    }
                )
                if (decoded.unknownTokens > 0) {
                    Spacer(Modifier.height(8.dp))
                    ToolHint("有 ${decoded.unknownTokens} 段摩斯码无法识别，已用 ? 占位")
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            clipboard.setText(AnnotatedString(decoded.output))
                            onMessage("已复制解码结果")
                        },
                        enabled = decoded.output.isNotEmpty()
                    ) {
                        Text("复制")
                    }
                    OutlinedButton(
                        onClick = { morseText = "" },
                        enabled = morseText.isNotEmpty()
                    ) {
                        Text("清空")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- 速查表（可折叠）----
        //
        // 默认收起：表有 50 多行，展开后会把上面的输入区推到很远，
        // 而来这里的人多数是「想转换」，查表是次要需求。
        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "摩斯码对照表",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.strongTextColor,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { tableExpanded = !tableExpanded }) {
                    Text(if (tableExpanded) "收起" else "展开")
                }
            }
            AnimatedVisibility(visible = tableExpanded) {
                Column {
                    MorseTableGroup("字母 A - Z", MORSE_LETTERS)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MorseTableGroup("数字 0 - 9", MORSE_DIGITS)
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    MorseTableGroup("标点与符号", MORSE_PUNCTUATION)
                }
            }
        }
    }
}

/** 编码结果：转换出的摩斯串 + 无法编码的字符数（见 [MorseCodeScreen] 的 KDoc）。 */
private data class MorseEncodeResult(val output: String, val skipped: Int)

/** 解码结果：还原出的明文 + 无法识别的码段数。 */
private data class MorseDecodeResult(val output: String, val unknownTokens: Int)

/**
 * 文字 → 摩斯。
 *
 * 逐字符分组而不是「先 split 再 join」：需要区分「单词边界」与「字母边界」两种分隔，
 * 用一个显式的 `words` 累加器最直白。连续空格不会产生空单词（`isBlank` 判断跳过），
 * 这里的空单词若被保留，输出里就会出现 `//`，解码端会多认出一个不存在的单词。
 */
private fun encodeToMorse(text: String): MorseEncodeResult {
    if (text.isEmpty()) return MorseEncodeResult("", 0)

    var skipped = 0
    val words = mutableListOf<String>()
    val current = mutableListOf<String>()

    for (ch in text.uppercase()) {
        when {
            ch.isWhitespace() -> {
                // 一个单词结束：只有非空时才落盘，避免连续空格产生空单词。
                if (current.isNotEmpty()) {
                    words.add(current.joinToString(" "))
                    current.clear()
                }
            }

            else -> {
                val code = MORSE_BY_CHAR[ch]
                if (code != null) {
                    current.add(code)
                } else {
                    // 无法编码的字符用 `?` 占位：保留它在句子里的位置信息。
                    // 直接跳过会让「A中B」与「AB」编出同样的串，用户无从发现丢了东西。
                    current.add("?")
                    skipped++
                }
            }
        }
    }
    if (current.isNotEmpty()) words.add(current.joinToString(" "))

    return MorseEncodeResult(words.joinToString(" / "), skipped)
}

/**
 * 摩斯 → 文字。
 *
 * 切分顺序是**先单词后字母**：`/` 是比空格更高一级的分隔符，
 * 若先按空格切再处理 `/`，`/` 会变成一个孤立 token 混在字母序列里，
 * 需要额外的分支判断它是否独占一个空格位——反过来做就没有这个歧义。
 *
 * 容忍空段：`".. / / --"` 里的空单词段会被 `filter` 掉，不会输出多余的空格。
 */
private fun decodeFromMorse(raw: String): MorseDecodeResult {
    if (raw.isBlank()) return MorseDecodeResult("", 0)

    var unknown = 0
    // `split("/")` 而不是正则：分隔符就是单个字符，正则在这里只会更慢且更难读。
    // 每个单词段再按任意空白切。
    val decodedWords = raw.split("/").mapNotNull { wordSegment ->
        val letters = wordSegment.split(WHITESPACE_RUN)
            .filter { it.isNotEmpty() }
            .map { token ->
                val ch = MORSE_TO_CHAR[token]
                if (ch == null) {
                    // `?` 不是摩斯码里的合法符号，表里不会有它的键，
                    // 因此用户自己敲进来的 `?` 也会走到这里——这是可接受的：
                    // 它本来就无法解码，标成未知才是正确结果。
                    unknown++
                    '?'
                } else {
                    ch
                }
            }
        if (letters.isEmpty()) null else letters.joinToString("")
    }

    return MorseDecodeResult(decodedWords.joinToString(" "), unknown)
}

/** 一张对照表分组：左列字符、右列摩斯码，三列排布以节省纵向空间。 */
@Composable
private fun MorseTableGroup(title: String, entries: List<Pair<Char, String>>) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        // 三列：每列宽 1f，行数 = ceil(n / 3)。用 Column + Row 手排而不是 LazyVerticalGrid——
        // 本页外层已经是 verticalScroll，嵌套一个纵向可滚动的懒加载网格会被要求给定高度，
        // 而这里的行数是常量（最长的分组也只有 26 行），完全不需要懒加载。
        val columnCount = 3
        val rowCount = (entries.size + columnCount - 1) / columnCount
        for (row in 0 until rowCount) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until columnCount) {
                    val index = col * rowCount + row
                    val entry = entries.getOrNull(index)
                    if (entry == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = entry.first.toString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.strongTextColor,
                                modifier = Modifier.padding(end = 6.dp)
                            )
                            Text(
                                text = entry.second,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** 连续空白（含换行、制表符）作为字母分隔符，见 [decodeFromMorse]。 */
private val WHITESPACE_RUN = Regex("\\s+")

/** 字母的摩斯码表（A-Z）。 */
private val MORSE_LETTERS: List<Pair<Char, String>> = listOf(
    'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".",
    'F' to "..-.", 'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---",
    'K' to "-.-", 'L' to ".-..", 'M' to "--", 'N' to "-.", 'O' to "---",
    'P' to ".--.", 'Q' to "--.-", 'R' to ".-.", 'S' to "...", 'T' to "-",
    'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-", 'Y' to "-.--",
    'Z' to "--.."
)

/** 数字的摩斯码表（0-9）。 */
private val MORSE_DIGITS: List<Pair<Char, String>> = listOf(
    '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
    '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----."
)

/**
 * 标点与符号的摩斯码表。
 *
 * 覆盖需求列出的全部常用标点，并额外收了 `/`：
 * 「单词分隔符」用的是摩斯串里的**字面斜杠字符**，而斜杠这个字符的编码是
 * 点与横组成的 `-..-.`，两者不是同一个 token，因此可以共存
 * （往返推导见 [MorseCodeScreen] 的 KDoc）。
 */
private val MORSE_PUNCTUATION: List<Pair<Char, String>> = listOf(
    '.' to ".-.-.-",
    ',' to "--..--",
    '?' to "..--..",
    '\'' to ".----.",
    '!' to "-.-.--",
    '"' to ".-..-.",
    '(' to "-.--.",
    ')' to "-.--.-",
    '&' to ".-...",
    ':' to "---...",
    ';' to "-.-.-.",
    '=' to "-...-",
    '+' to ".-.-.",
    '-' to "-....-",
    '_' to "..--.-",
    '$' to "...-..-",
    '@' to ".--.-.",
    '/' to "-..-."
)

/** 编码用的正查表：字符 → 摩斯码。 */
private val MORSE_BY_CHAR: Map<Char, String> =
    (MORSE_LETTERS + MORSE_DIGITS + MORSE_PUNCTUATION).toMap()

/**
 * 解码用的反查表：摩斯码 → 字符。
 *
 * 用 `toMap()` 从同一份数据推导，**不手写第二份**：两份表一旦漂移
 * （编码能出、解码认不出），排查起来极难——而它们本来就该是同一个映射的两个方向。
 */
private val MORSE_TO_CHAR: Map<String, Char> =
    (MORSE_LETTERS + MORSE_DIGITS + MORSE_PUNCTUATION).associate { (ch, code) -> code to ch }
