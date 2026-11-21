package com.morecheck.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 「无刻度」的**平滑滑块轨道**（本轮需求：消除滑块拖动条上的锯齿状波纹）。
 *
 * ## 锯齿是哪来的
 *
 * 项目里两个滑块（开发者选项的「开屏显示时长」、设备信息查询的 Slider 样例）
 * 都是 **0.1 秒一档、共 101 档**（`steps = 99`）。material3 的默认轨道会在
 * 每一档处画一个刻度点：手机宽度约 340dp、99 个点平均每档约 3.4dp，
 * 而刻度点本身直径约 2~4dp——**点与点首尾相连又彼此错位**，
 * 视觉上就糊成了一条锯齿状 / 波浪状的粗线（真机截图确认）。
 * 档位本身是有用的（吸附手感），要消掉的只是**画出来的刻度**。
 *
 * ## 为什么自绘轨道，而不是调 `steps`
 *
 * - 把档距放大（如 0.5 秒）可以让刻度稀疏，但默认值 900ms 不再落在整档上，
 *   会触发「滑块位置被吸附到隔壁档、与右侧读数对不上」的老坑
 *   （`SplashHoldRow` 的 KDoc 有完整记录）——不可取；
 * - steps = 0 会丢掉吸附手感，与「0.1 秒一档」的产品口径冲突——也不可取；
 * - material3 的 `Slider(track = ...)` 参数允许**整条替换默认轨道**：
 *   刻度点画在默认轨道内部（`SliderDefaults.Track` 的实现里），换成自绘轨道后
 *   刻度自然消失，`steps` 仍保留吸附语义。**动动画、不动手感**，这是最小改动。
 *
 * ## 画法
 *
 * 两层圆角矩形叠放：底层为未选中（inactive）色铺满，上层为选中（active）色
 * 按 [fraction] 占宽。颜色一律取 `SliderDefaults.colors()`（跟随主题与启用态），
 * 不新造颜色值。轨道厚度取 4dp：这是 material2 时代与大量系统滑块的常规厚度，
 * 比默认的 16dp 厚轨道轻，也与本页其余控件的分量相称。
 *
 * ⚠️ 换自绘轨道后，默认轨道自带「手柄两侧留缺口」（thumbTrackGap）的效果不再有：
 * 手柄直接压在轨道上，与常见滑块观感一致，属可接受的取舍。
 *
 * @param fraction 已填充比例（0f~1f），由调用方按「当前值在取值域中的位置」算出。
 *   刻意由调用方计算而不是从 `SliderState` 里取：调用处本来就持有当前值与取值域，
 *   用外层状态算既直白又不依赖 `SliderState` 的公开 API 面。
 */
@Composable
internal fun SmoothSliderTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
    /**
     * 轨道厚度。默认 10dp（2026-10-22 需求 5：小米风格粗轨道）；
     * 传 4dp 可回到改造前的细轨道（本参数为显式可调，方便将来按开关切换）。
     */
    thickness: androidx.compose.ui.unit.Dp = SLIDER_TRACK_THICKNESS
) {
    val colors = SliderDefaults.colors()
    val shape = RoundedCornerShape(thickness / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(shape)
            .background(colors.inactiveTrackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(shape)
                .background(colors.activeTrackColor)
        )
    }
}

/**
 * 平滑轨道的**默认**厚度。
 *
 * 2026-10-22 从 4dp 改成 10dp：用户要求滑块改成小米风格（粗轨道 + 白色圆点），
 * 细轨道配大圆点会像"一颗球放在一根线上"。想让某个地方回到细轨道，
 * 给 `thickness` 传 4dp 即可（见 [SmoothSliderTrack] 的参数说明）。
 */
private val SLIDER_TRACK_THICKNESS = 10.dp
