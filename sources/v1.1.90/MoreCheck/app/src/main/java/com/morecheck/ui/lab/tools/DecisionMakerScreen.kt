package com.morecheck.ui.lab.tools

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.morecheck.ui.theme.strongTextColor
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 「实验室 → 做个决定」。
 *
 * ## 动画为什么用 `Animatable` + 单调减速，而不是一串 `delay`
 *
 * 需求要的是「老虎机式减速滚动」：开始时名字飞速切换、越来越慢、最后停在中奖项上。
 * 常见写法是 `repeat(n) { delay(interval); index++ }` 且 interval 递减——但那样
 * 每换一格都要挂起一次，总帧数受 delay 精度限制，观感是「一跳一跳」而不是连续减速，
 * 而且**用户按下返回、页面离开时那段循环很难及时停**（每个 delay 都要等完才检查）。
 *
 * 这里的做法：用一个 `Animatable<Float>` 从 0 连续涨到 `圈数 × 项数 + 中奖下标`，
 * 缓动函数用一条**结尾非常缓慢**的三次贝塞尔曲线。显示哪一项由
 * `当前值取整 % 项数` 得出——值连续变化，切换自然由快到慢；
 * 而 `Animatable.animateTo` 是可取消的协程，页面销毁时随 `rememberCoroutineScope` 一起取消。
 *
 * ## 为什么不用 `Crossfade` / `AnimatedContent` 做滚动
 *
 * 按本项目既有教训（HANDOVER 第 1 节 Pager 教训）：需要「新旧两页并存」的动画组件
 * 在多页面并存的骨架里风险高。本页只需要**一个文本槽**的内容替换，
 * 连位移都不需要，因此用最朴素的「同一位置换字符串」即可。
 *
 * ## 校验口径
 *
 * 最少 2 项、最多 [MAX_ITEMS] 项；**空白项不计入**（用户点了「添加」但没填，
 * 不该让「开始」按钮永远点不动，也不该把空白当成一个候选）。若填了内容却不足 2 项，
 * 就地给出原因，而不是把按钮置灰了事——置灰不解释是这类工具最常见的体验问题。
 *
 * ## 状态保存
 *
 * 候选项是用户输入，必须 `rememberSaveable`（转屏不丢）。用 `listSaver` 压成
 * `List<String>`——`List<String>` 的默认 Saver 虽然通常可用，但显式收窄类型
 * 能避免「实现类不可序列化」这类只在特定 Compose 版本上出现的隐患。
 *
 * @param onBack 返回上一级（实验室宫格）。
 * @param onMessage 页面级一次性提示（如「已清空候选项」），走全局 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DecisionMakerScreen(
    onBack: () -> Unit,
    onMessage: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    // 系统返回键等价于左上角箭头，避免用户被卡在本页。
    // 滚动动画跑在 rememberCoroutineScope 里，离开页面时随之取消，不会有残留协程。
    BackHandler(onBack = onBack)

    // 默认给两个空项：用户进来就能直接填，不用先点两次「添加」。
    var items by rememberSaveable(stateSaver = stringListSaver) {
        mutableStateOf(List(DEFAULT_ITEM_COUNT) { "" })
    }

    var rolling by remember { mutableStateOf(false) }
    // 中奖项下标；null = 还没开始过（结果区显示占位）。
    var resultIndex by remember { mutableStateOf<Int?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // 动画驱动值。显示哪一项由它取整取模算出（见 KDoc）。
    val spinner = remember { Animatable(0f) }

    // 参与抽取的候选项：去掉空白后的列表（见 KDoc 的校验口径）。
    val activeItems = items.map { it.trim() }.filter { it.isNotEmpty() }
    val canStart = activeItems.size >= MIN_ITEMS && !rolling

    // ⚠️ 这里**刻意不**在页面顶层读 `spinner.value`。
    //
    // `Animatable.value` 是 Compose 的快照状态，读它的那个组合作用域会在动画的
    // 每一帧重组。若在此处读，整页（十几个输入框 + 两张卡片）就会以 60fps 重组，
    // 明显掉帧。正确做法是把「读动画值」下沉到一个叶子 Composable
    // （见 [RollingNameSlot]）：帧级重组只发生在那一格文字上。

    fun startRoll() {
        if (rolling) return
        val candidates = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (candidates.size < MIN_ITEMS) {
            errorText = "至少需要 $MIN_ITEMS 个非空候选项"
            return
        }
        if (candidates.size > MAX_ITEMS) {
            // 理论上界面已经把「添加」按钮禁掉了，这里再兜一层：
            // 万一以后放宽了入口（比如支持逗号分隔批量粘贴），也不会静默丢项。
            errorText = "最多支持 $MAX_ITEMS 个候选项"
            return
        }
        errorText = null
        resultIndex = null
        rolling = true

        scope.launch {
            val count = candidates.size
            val chosen = Random.nextInt(count)
            // 目标值 = 整圈数 × 项数 + 中奖下标：
            // 这样动画结束时 `值取整 % 项数` 恰好等于 chosen，
            // 无需在动画结束后额外「跳一下」到中奖项（那正是观感最假的地方）。
            val target = (SPIN_ROUNDS * count + chosen).toFloat()
            try {
                spinner.snapTo(0f)
                spinner.animateTo(
                    targetValue = target,
                    animationSpec = tween(
                        durationMillis = SPIN_DURATION_MS,
                        easing = DecelerateEasing
                    )
                )
                resultIndex = chosen
            } finally {
                // 协程被取消时（页面即将销毁）也把 `rolling` 复位：
                // 虽然本页销毁后状态自然丢弃，但若将来把动画改成「重试/中断」语义，
                // 留下的 `rolling = true` 会让按钮永久禁用——这里提前消除这个坏状态。
                rolling = false
            }
        }
    }

    ToolScreen(title = "做个决定", onBack = onBack) {
        ToolCard(title = "候选项") {
            ToolHint("填写选项，最少 $MIN_ITEMS 项、最多 $MAX_ITEMS 项；空白项不参与抽取。")
            Spacer(Modifier.height(12.dp))

            items.forEachIndexed { index, value ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { newValue ->
                            // 整体替换列表而不是修改原列表：`mutableStateOf(List)` 是
                            // 按引用判等的，原地改元素不会触发重组。
                            items = items.toMutableList().also { it[index] = newValue }
                            errorText = null
                            // 改了候选项，上一次的结果就作废：结果卡片按**下标**取项，
                            // 列表一变下标就可能指向另一个选项，留着会显示一个
                            // 用户从没抽到过的字符串（比空着更糟）。
                            resultIndex = null
                        },
                        label = { Text("选项 ${index + 1}") },
                        singleLine = true,
                        enabled = !rolling,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            // 至少留 2 个输入框：删到只剩 1 个就没法满足抽取条件了。
                            if (items.size > MIN_ITEMS) {
                                items = items.toMutableList().also { it.removeAt(index) }
                                errorText = null
                                resultIndex = null
                            }
                        },
                        // 滚动中禁止编辑（否则项数变化会让正在跑的动画的取模失去意义）。
                        enabled = items.size > MIN_ITEMS && !rolling
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除选项 ${index + 1}"
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    items = items + ""
                    resultIndex = null
                },
                enabled = items.size < MAX_ITEMS && !rolling,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("添加选项")
            }

            errorText?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { startRoll() },
            enabled = canStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(if (resultIndex == null) "开始" else "再来一次")
        }

        if (!canStart && !rolling && activeItems.size < MIN_ITEMS) {
            Spacer(Modifier.height(8.dp))
            ToolHint("再填 ${MIN_ITEMS - activeItems.size} 个选项就可以开始了")
        }

        // ---- 结果大卡片 ----
        Spacer(Modifier.height(16.dp))
        ToolCard {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    // 滚动中：显示当前滚过的那一项，不显示中奖项（那正是动画的意义）。
                    //
                    // 只把动画值传给叶子组件去读（见页面顶层那段注释）：
                    // 这里调用方本身不读 `spinner.value`，因此不会每帧重组整页。
                    rolling -> {
                        RollingNameSlot(spinner = spinner, candidates = activeItems)
                        Spacer(Modifier.height(6.dp))
                        ToolHint("正在挑选…")
                    }

                    resultIndex != null -> {
                        // 用 `?.let` 而不是先判 `resultIndex != null` 再直接把它传进去：
                        // `resultIndex` 是 `by remember` 委托出来的属性，Kotlin 不会对它
                        // 做智能类型收窄，`getOrNull(resultIndex)` 会因类型仍是 `Int?` 编译失败。
                        val picked = resultIndex?.let { activeItems.getOrNull(it) }
                        if (picked != null) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                // 结果字号比候选项大得多：这是整页唯一要「一眼看到」的东西。
                                text = picked,
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.strongTextColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            ToolHint("已从 ${activeItems.size} 个选项中选出")
                        }
                    }

                    else -> {
                        Text(
                            text = "？",
                            style = MaterialTheme.typography.headlineLarge,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        ToolHint("点「开始」让它替你决定")
                    }
                }
            }
        }

        // 「清空」只在有结果后出现：抽之前清空没有意义（本来就没东西可清），
        // 而且会把用户刚填好的候选项一键抹掉。
        //
        // ⚠️ 这里**不再放「再来一次」**：主按钮在出结果后文案已经变成「再来一次」，
        // 再放一个同功能的按钮就是同一动作的两个入口，用户会犹豫该点哪个。
        if (resultIndex != null && !rolling) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    // 保留两个空输入框（而不是清成 0 个）：清空后用户还得先点添加，
                    // 而「重新开始填选项」本来就该从两个空项开始——与首次进入一致。
                    items = List(DEFAULT_ITEM_COUNT) { "" }
                    resultIndex = null
                    errorText = null
                    onMessage("已清空候选项")
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("清空候选项")
            }
        }
    }
}

/**
 * 滚动中显示候选项名的**叶子组件**：整个动画期间只有它随帧重组。
 *
 * ## 为什么必须是单独的 Composable
 *
 * `spinner.value` 是快照状态，**读它的组合作用域会随每一帧重组**。
 * 若在 `DecisionMakerScreen` 顶层读它，动画期间整页（十几个输入框 + 两张卡片）
 * 每秒重组 60 次，在没有硬件加速优势的低端机上会明显掉帧。
 * 把读取下沉到这里之后，帧级重组的作用域就只剩这一个 `Text`。
 *
 * 取模两遍 `(x % n + n) % n` 而不是 `x % n`：Kotlin 的 `%` 对负数返回负值，
 * 若将来把动画起点改成负数（例如做反向滚动），单次取模会算出负下标直接崩。
 *
 * @param spinner 动画驱动值；**只在本组件内读取**。
 * @param candidates 候选项（已去掉空白，非空）。
 */
@Composable
private fun RollingNameSlot(
    spinner: Animatable<Float, AnimationVector1D>,
    candidates: List<String>
) {
    // 防御空列表：滚动期间理论上不会为空（`startRoll` 已校验 ≥ 2 项且滚动中禁编辑），
    // 但「取模除零」是会直接崩掉的错误，值得一行兜底。
    if (candidates.isEmpty()) return

    val index = (spinner.value.toInt() % candidates.size + candidates.size) % candidates.size
    Text(
        text = candidates[index],
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * 结尾极缓的三次贝塞尔缓动。
 *
 * 参数 `(0.05, 0.7, 0.1, 1)`：前段斜率较大（开头飞快），
 * 末段斜率趋近 0（结尾缓慢停住）。刻意不用 `FastOutSlowInEasing`：
 * 那条曲线在结尾仍有明显速度，光标会「啪」地停住，缺少老虎机那种拖尾感。
 */
private val DecelerateEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** 滚动动画总时长。1.8 秒：短到不烦，长到能让「减速」被感知。 */
private const val SPIN_DURATION_MS = 1800

/**
 * 动画过程中滚过的整圈数。
 *
 * 6 圈 × 平均 4 项 ≈ 24 次切换，1.8 秒内完成 → 开头约每 40ms 换一次，
 * 结尾约每 300ms 换一次，肉眼能明确看到「越来越慢」。
 */
private const val SPIN_ROUNDS = 6

/** 最少候选项数：少于 2 项就不叫「做选择」了。 */
private const val MIN_ITEMS = 2

/** 最多候选项数：再多在手机上就只是让人懒得看的噪音。 */
private const val MAX_ITEMS = 12

/** 首次进入时给出的空输入框个数（见 KDoc）。 */
private const val DEFAULT_ITEM_COUNT = 2

/** `List<String>` 的保存器：每个元素本身就是可存进 Bundle 的 `String`。 */
private val stringListSaver = listSaver<List<String>, String>(
    save = { it },
    restore = { it }
)
