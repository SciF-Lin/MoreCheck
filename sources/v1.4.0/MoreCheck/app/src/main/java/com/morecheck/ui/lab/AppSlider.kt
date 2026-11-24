package com.morecheck.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morecheck.di.appContainer

/**
 * 全应用统一的**滑块**外观（2026-10-22 需求 5：「滑动条样式更改，参考小米的样式」）。
 *
 * ## 小米（MIUI / HyperOS）滑块的三个特征
 *
 * 参考用户给的截图，把它拆成可实现的三个点：
 *
 * 1. **粗轨道**：约 10dp 高的圆头胶囊，而不是 Material3 默认的 4dp 细线；
 * 2. **白色圆点手柄**：纯白圆形、带一点投影，压在轨道上（没有"手柄两侧留缺口"那种效果）；
 * 3. **已填充段用主色**、未填充段用浅灰。
 *
 * ## 为什么做成一个包装组件，而不是在每个调用点各画一次
 *
 * 本应用有 6 处滑块（开发者选项 3 处、图片压缩、随机数、设备信息查询样例）。
 * 每处各写一遍 `track = {...}` + `thumb = {...}`，不仅重复，而且**样式会漂移**
 * （改一处忘一处，用户会看到"这个滑块是小米风、那个还是旧样式"）。
 *
 * 更关键的是：样式要能被「样式设置 → 小米风格滑块」这个开关关掉（回落 Material 默认）。
 * 让组件**自己读开关**（与 `pressScale` 自读按压反馈开关同一套做法），
 * 6 个调用点就都不需要知道这个开关存在。
 *
 * ## 为什么 `fraction` 由调用方传
 *
 * Material3 的 `Slider(track = ...)` 只给轨道槽位置，不给"已填充比例"；
 * 调用方本来就持有当前值与取值域，由它算比例最直白（与 [SmoothSliderTrack] 同一口径）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    fraction: Float,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    val context = LocalContext.current
    val repository = rememberStyleRepository(context)
    val xiaomiStyle by repository.styleXiaomiSliderEnabled
        .collectAsStateWithLifecycle(initialValue = true)

    if (!xiaomiStyle) {
        // 回落 Material 默认外观：**不传 track / thumb**，让 Slider 用自己的一套。
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps
        )
        return
    }

    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        // 轨道：10dp 圆头胶囊（小米的粗轨道）。
        track = {
            SmoothSliderTrack(
                fraction = fraction,
                thickness = XIAOMI_TRACK_THICKNESS
            )
        },
        // 手柄：白色圆点 + 投影（截图里最显眼的特征）。
        thumb = { XiaomiThumb(enabled = enabled) }
    )
}

/**
 * 小米风格的白色圆点手柄。
 *
 * 禁用态不换色、只靠外层的 `enabled`（Material3 会把整条轨道调淡）——
 * 与 `SplashHoldRow` 里"只对文字乘 alpha"的取舍同源：双重调淡会让禁用态看不出形状。
 */
@Composable
private fun XiaomiThumb(enabled: Boolean) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .size(XIAOMI_THUMB_SIZE)
            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(Color.White)
            .border(width = 1.dp, color = outline, shape = CircleShape)
            // 禁用态加一层极淡的灰，避免"看着能拖其实拖不动"。
            .then(
                if (enabled) Modifier
                else Modifier.background(Color.Black.copy(alpha = 0.06f))
            )
    )
}

/** 小米风格轨道厚度（截图里约为常见滑块的 2 倍）。 */
private val XIAOMI_TRACK_THICKNESS = 10.dp

/** 手柄直径：比轨道更宽，才会"压"在轨道上。 */
private val XIAOMI_THUMB_SIZE = 22.dp

/**
 * 取开发者选项仓库（样式开关）。
 *
 * 单独抽出来只为一件事：`LocalContext.current` 只能在组合期读，而它要作为
 * `remember` 的 key——写成一行会很绕（与 `GameActivityHost.rememberDeveloperOptions` 同一写法）。
 */
@Composable
private fun rememberStyleRepository(context: android.content.Context) =
    androidx.compose.runtime.remember(context) {
        context.appContainer.developerOptionsRepository
    }
