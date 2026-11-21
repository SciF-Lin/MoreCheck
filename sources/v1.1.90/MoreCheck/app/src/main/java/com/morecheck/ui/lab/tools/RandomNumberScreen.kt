package com.morecheck.ui.lab.tools

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morecheck.ui.theme.strongTextColor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 「实验室 → 随机数生成」。
 *
 * ## 交互里最容易被忽略的一条：排除重复的可用性
 *
 * 「排除重复」只在「要生成的个数 ≤ 区间内整数个数」时才有意义。不满足时硬开它
 * 会让生成算法陷入死循环（要 10 个不重复的数却只有 5 个可选值），因此开关本身
 * 按当前区间实时禁用，**但用户之前选中的意图不被悄悄改掉**（`checked` 仍是用户的选择）。
 * 真正生成时再判一次：条件不满足就按「允许重复」执行，并在结果区上方明确说明
 * 「本次已忽略排除重复」——这比静默换成允许重复、或直接不生成更诚实。
 *
 * ## 为什么历史记录可以有上限
 *
 * 历史只活在本次会话（不进 DataStore），每次生成追加一条。不设上限的话，
 * 反复点「生成」会让这个列表无限长，页面越滚越长且无任何用处；
 * 取最近 [MAX_HISTORY] 条足以满足「回看刚才几次」的实际用途。
 *
 * ## 状态为什么都用 rememberSaveable
 *
 * 四个输入（最小值 / 最大值 / 个数 / 排除重复）与结果、历史都是用户输入的延伸。
 * 转屏或系统回收重建组合时若退回默认值，用户会以为「自己填的东西丢了」。
 * 列表状态用自定义 Saver 存成标量串：`List<List<Int>>` 直接交给默认 Saver
 * 会依赖「实现类恰好可序列化」，而 `listOf` 的运行时实现不保证这一点。
 *
 * @param onBack 返回上一级（实验室宫格）。
 * @param onMessage 页面级一次性提示（清空历史等），走全局 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RandomNumberScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    // 系统返回键等价于左上角箭头，避免用户被卡在本页。BackHandler 比宿主更靠内、优先生效。
    BackHandler(onBack = onBack)

    // 最小值 / 最大值用**字符串**保存而不是 Int：
    // 用户编辑过程中会出现 ""、"-" 这类不是合法整数的中间态，用 Int 存就得强行兜底，
    // 表现为「清空输入框立刻被改回 0」，光标还会跳。字符串如实保留输入，提交时再解析。
    var minText by rememberSaveable { mutableStateOf("1") }
    var maxText by rememberSaveable { mutableStateOf("100") }
    var count by rememberSaveable { mutableIntStateOf(DEFAULT_COUNT) }
    var excludeDuplicate by rememberSaveable { mutableStateOf(false) }

    // 最近一次生成的结果与本次会话的历史，用「标量列表」Saver 保存（见 KDoc）。
    var results by rememberSaveable(stateSaver = intListSaver) {
        mutableStateOf(emptyList<Int>())
    }
    var history by rememberSaveable(stateSaver = intListListSaver) {
        mutableStateOf(emptyList<List<Int>>())
    }

    // 输入不合法时的就地提示（红字）；null 表示没有错误。
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    // 「这次实际忽略了排除重复」这类说明，与 error 分开：它不是用户的错，
    // 不该用报错色，只在结果区上方用次要文字说清本次到底怎么生成的。
    var notice by rememberSaveable { mutableStateOf<String?>(null) }

    // 结果卡片上标注「这一批是在什么区间生成的」。
    //
    // 不能直接读当前输入框的两个值：用户生成完往往会接着改区间，
    // 一改就与 result 脱钩，卡片上会写出一个假的区间说明。
    var lastRange by rememberSaveable { mutableStateOf<String?>(null) }

    val parsedMin = minText.trim().toIntOrNull()
    val parsedMax = maxText.trim().toIntOrNull()

    // 区间内整数个数。用 Long 算：`max - min + 1` 在 min = Int.MIN_VALUE、
    // max = Int.MAX_VALUE 时会撑破 Int，虽然用户几乎不会这么填，但溢出后的负数
    // 会让「排除重复是否可用」的判断反向出错，代价不值得省。
    val rangeSize: Long? = if (parsedMin != null && parsedMax != null && parsedMax >= parsedMin) {
        parsedMax.toLong() - parsedMin.toLong() + 1L
    } else {
        null
    }

    val canExcludeDuplicate = rangeSize != null && rangeSize >= count

    fun generate() {
        // 先把两个输入解析成**非空局部变量**再往下走：
        // `minText` / `maxText` 是委托属性，编译器不会对它们做智能类型收窄，
        // 直接拿 `Int?` 去做 `randomIntBetween` 会编译失败。
        //
        // ⚠️ 校验也**不能写成 `when { 空 -> ...; min > max -> ... }`**：
        // `when` 的各分支之间不共享智能转换，第二个分支里的 `min` 仍是 `Int?`，
        // `min > max` 直接编译不过。用「先判空、再比较」两段写才成立。
        val min = minText.trim().toIntOrNull()
        val max = maxText.trim().toIntOrNull()
        if (min == null || max == null) {
            error = "最小值与最大值必须是整数"
            notice = null
            return
        }
        if (min > max) {
            error = "最小值不能大于最大值"
            notice = null
            return
        }
        error = null

        val size = max.toLong() - min.toLong() + 1L
        // 用户勾了「排除重复」但区间不够：按允许重复生成，并说明原因（见 KDoc）。
        val dedupe = excludeDuplicate && size >= count
        notice = if (excludeDuplicate && !dedupe) {
            "当前区间只有 $size 个整数，不够取 $count 个不重复的数，本次按允许重复生成"
        } else {
            null
        }

        val generated = if (dedupe) {
            // 区间够大时拒绝采样一定能结束；这里的 count 最多 10，复杂度可忽略。
            // 用 LinkedHashSet 而不是先 `(min..max).shuffled().take(count)`：
            // 后者在「区间 = 10 亿」时会把整段区间材料化，直接 OOM。
            val picked = LinkedHashSet<Int>(count)
            while (picked.size < count) {
                picked.add(randomIntBetween(min, max))
            }
            picked.toList()
        } else {
            List(count) { randomIntBetween(min, max) }
        }

        results = generated
        lastRange = "$min ~ $max"
        // 新的在前，超上限截断（见 KDoc）。
        history = (listOf(generated) + history).take(MAX_HISTORY)
    }

    ToolScreen(title = "随机数生成", onBack = onBack) {
        ToolCard(title = "范围与数量") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minText,
                    onValueChange = {
                        minText = it
                        error = null
                    },
                    label = { Text("最小值") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxText,
                    onValueChange = {
                        maxText = it
                        error = null
                    },
                    label = { Text("最大值") },
                    singleLine = true,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "生成个数",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.strongTextColor,
                    modifier = Modifier.weight(1f)
                )
                // 读数用等宽字体：拖动时位数变化不会让这个数字左右跳。
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = count.toFloat(),
                // 回调是连续值，必须自己吸附到整数档，否则个数会变成 3.7 这种值。
                onValueChange = { raw -> count = raw.roundToInt().coerceIn(MIN_COUNT, MAX_COUNT) },
                valueRange = MIN_COUNT.toFloat()..MAX_COUNT.toFloat(),
                // steps = 档位数 - 2：1~10 共 10 个整数档 → 8 个中间档。
                steps = MAX_COUNT - MIN_COUNT - 1,
                modifier = Modifier.fillMaxWidth()
            )
            ToolHint("可生成 1~${MAX_COUNT} 个整数")

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "排除重复",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.strongTextColor
                    )
                    ToolHint(
                        // 说清「为什么此刻不能开」，而不是只把开关变灰。
                        if (canExcludeDuplicate) {
                            "本次结果中不会出现相同数字"
                        } else {
                            "需满足「个数 ≤ 区间内整数个数」才可用"
                        }
                    )
                }
                Switch(
                    checked = excludeDuplicate,
                    onCheckedChange = { excludeDuplicate = it },
                    // 区间不满足时禁用，但 checked 仍保留用户的选择（见 KDoc）。
                    enabled = canExcludeDuplicate
                )
            }

            Spacer(Modifier.height(8.dp))
            ToolHint(
                text = if (rangeSize != null) {
                    "当前区间共 $rangeSize 个整数"
                } else {
                    "填入有效的整数区间后即可生成"
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { generate() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("生成")
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        notice?.let {
            Spacer(Modifier.height(8.dp))
            ToolHint(it)
        }

        if (results.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            ToolCard {
                Text(
                    text = results.joinToString("  "),
                    style = MaterialTheme.typography.headlineMedium,
                    // 等宽字体让多位数对得更整齐，也让「有几个数」一眼可数。
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ToolHint(
                    // 用生成时快照下来的区间，而不是当前输入框的值（见 lastRange 注释）。
                    "共 ${results.size} 个 · 区间 ${lastRange ?: "-"}",
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        ToolCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "历史记录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.strongTextColor,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        history = emptyList()
                        onMessage("已清空历史记录")
                    },
                    enabled = history.isNotEmpty()
                ) {
                    Text("清空")
                }
            }
            if (history.isEmpty()) {
                ToolHint("本次会话还没有生成记录")
            } else {
                history.forEachIndexed { index, entry ->
                    if (index > 0) Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            // 序号按「最新为第 1 次」倒序标注，与列表顺序一致，
                            // 免得用户以为越往下越新。
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = entry.joinToString("  "),
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.strongTextColor
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                ToolHint("最多保留最近 $MAX_HISTORY 次")
            }
        }
    }
}

/**
 * 取 `[min, max]` 闭区间内的一个随机整数。
 *
 * 用 `Random.nextLong(from, until)` 而不是 `Random.nextInt(min, max + 1)`：
 * 后者在 `max == Int.MAX_VALUE` 时 `max + 1` 溢出成负数，会直接抛
 * `IllegalArgumentException: bound must be positive`。区间端点是用户填的，
 * 不该假设它不会取到边界值。
 */
private fun randomIntBetween(min: Int, max: Int): Int =
    Random.nextLong(min.toLong(), max.toLong() + 1L).toInt()

/** 生成个数的下限（少于 1 个没有意义）。 */
private const val MIN_COUNT = 1

/** 生成个数的上限：再多也只是一屏放不下的一串数字，没有实际用途。 */
private const val MAX_COUNT = 10

/** 默认生成个数：取中间值，用户一进来就能直接点「生成」看效果。 */
private const val DEFAULT_COUNT = 3

/** 历史记录最多保留的条数（见 [RandomNumberScreen] 的 KDoc）。 */
private const val MAX_HISTORY = 20

/**
 * `List<Int>` 的保存器：`listSaver` 把每个元素交给默认 Saver，`Int` 本身可直接进 Bundle。
 */
private val intListSaver = listSaver<List<Int>, Int>(
    save = { it },
    restore = { it }
)

/**
 * `List<List<Int>>` 的保存器：压平成「逗号分隔的字符串列表」。
 *
 * 不直接把内层 `List<Int>` 交给默认 Saver：那会把「能不能存」的判定交给运行时实现类。
 * 压平成字符串后，每个元素都是确定的 `String`，没有歧义。
 */
private val intListListSaver = listSaver<List<List<Int>>, String>(
    save = { rows -> rows.map { row -> row.joinToString(",") } },
    restore = { rows ->
        rows.map { row ->
            if (row.isEmpty()) emptyList() else row.split(",").mapNotNull { it.toIntOrNull() }
        }
    }
)
