package com.morecheck.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全屏看图：支持**双指缩放 / 拖动 / 双击放大**，点任意处或返回键关闭
 * （本轮需求 20：优化智能体、记事本等地方的图片查看界面）。
 *
 * ## 为什么要单独一个组件，而不是各页自己写
 *
 * 「记事本」与「智能体」原来各有一份预览弹窗（`NotebookScreen.ImagePreviewDialog`
 * 与 `AgentChatScreen.ChatImagePreviewDialog`），两份代码高度重复：
 * 都是 `Dialog(usePlatformDefaultWidth = false)` + 铺满 `scrim` + 中央一张
 * `ContentScale.Fit` 的图。而需求 20 要加的是**一整套交互**（缩放 / 拖动 / 双击），
 * 再让两边各抄一遍，就会出现"记事本能双击放大、智能体不能"这种只有一边生效的分裂。
 *
 * 因此把**弹窗骨架 + 交互 + 状态复位**都收在这里，两个调用点各自只剩
 * "解码一张图 + 传进来"那几行（解码方式本来就不同：记事本走
 * `NotebookStore.decodePreview`，智能体走 `decodeSampledFromFile`，无法统一）。
 *
 * ## 三套手势的边界（为什么不会互相打架）
 *
 * - **双指缩放**（`detectTransformGestures`）：两指以上才成立，单指滑动不触发；
 * - **单指拖动**（同一个 `detectTransformGestures` 的 `pan`）：缩放大于 1 时才允许，
 *   否则"没放大就想挪图"会把用户的单击关闭也变成拖拽；
 * - **双击**（`detectTapGestures` 的 `onDoubleTap`）：在两指手势之外单独挂，
 *   两个修饰符的顺序保证双击不会被判成"快速的双指缩放"。
 *
 * ⚠️ 缩放倍数夹在 [MIN_SCALE]..[MAX_SCALE]：不夹的话连续双击会一直放大到
 * 位图被拉成马赛克，而用户看不出"已经到底了"。夹住之后，到达边界时再双击
 * 会**回落**到 1（见 [toggleScale]），这是相册类应用的通用交互。
 *
 * ## 为什么缩放状态不用 rememberSaveable
 *
 * 它是**看图过程中的瞬时状态**：进程被回收后重建时，图片要重新解码、
 * 缩放倍数本身也没有保留价值（用户回到的是一张新加载的图）。
 * 用 `remember` 就够，且避免为一个"每次都想从 1 开始"的状态写 Saver。
 *
 * @param image 已解码好的图；null = 还在加载（显示转圈 + 文案）。
 * @param onDismiss 点画面 / 返回键关闭。
 * @param modifier 外层修饰符（测试用，正常调用不必传）。
 */
@Composable
fun FullScreenImageViewer(
    image: ImageBitmap?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 底色必须显式铺满：`Dialog` 的窗口本身是透明的，不铺底色的话，
        // 下层页面（记事本正文 / 聊天列表）会透过图片显示出来。
        // 用 `colorScheme.scrim` 而不是 `Color.Black`：本主题里 scrim 在深浅
        // 两种模式下**都是纯黑**（见 `ui/theme/Theme.kt` 的 NeutralLight /
        // NeutralDark），因此效果与"纯黑"完全相同，却不必新增一个颜色常量。
        // 看图时用中性黑（而不是主题色）是有意的：任何色调都会干扰对图片颜色的判断。
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim)
        ) {
            if (image == null) {
                // 加载中：转圈 + 文案。`CircularProgressIndicator` 用
                // `inverseOnSurface`（浅色）——它压在纯黑 scrim 上必须看得见。
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
                Text(
                    text = "图片加载中…",
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(top = 64.dp)
                )
                return@Box
            }

            // ── 变换状态 ──────────────────────────────────────────────────────
            //
            // `mutableFloatStateOf` 而不是 `mutableStateOf`：缩放是基本类型，
            // 前者无装箱（本文件 `TimerPanel` 里对 Long 用的是同一手法）。
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }

            /**
             * 双击：在「1 倍」与「双击放大倍数」之间切换。
             *
             * 放大的中心点取手指按下的位置（`centroid`）：以那一点为锚放大，
             * 用户想看清的位置正好落在放大后的中央附近，而不是屏幕正中。
             * 回落时同时把位移清零——否则用户看到的是"图缩小了但还歪在一边"，
             * 得再拖一次才回正。
             */
            fun toggleScale(centroid: Offset) {
                if (scale > 1.01f) {
                    scale = 1f
                    offset = Offset.Zero
                } else {
                    scale = DOUBLE_TAP_SCALE
                    // 位移按锚点位置做一次近似补偿（见上）。
                    // 这里不做精确的视口除法（拿不到尺寸），比例近似已足够贴近直觉。
                    offset = Offset(centroid.x * 0.12f, centroid.y * 0.12f)
                }
            }

            // 图片本体：先挂变换手势，再挂点击手势。
            //
            // ⚠️ **顺序不能反**：`pointerInput` 是修饰符链，先挂的先收到事件。
            // 变换手势**全程不消费**事件（`detectTransformGestures` 内部只在
            // 多指时消费），所以单击 / 双击仍能到达后挂的 `detectTapGestures`；
            // 反过来先挂点击，双击会被点击检测器当成两次单击提前处理掉。
            Image(
                bitmap = image,
                contentDescription = "图片预览",
                // Fit 而不是 Crop：预览要看清整张图，不能裁边。
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            // 缩放：乘在**上一帧的值**上（`scale` 是 mutableState，
                            // 这里读到的是最新值），再夹到合法区间。
                            // 用乘式而不是 `scale + zoom`：后者在快速双指开合时会漂移。
                            scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                            // 拖动：只有放大过才允许（未放大时"挪图"没有意义，
                            // 而且会与单击关闭抢手势——用户轻轻一滑就把图关了）。
                            if (scale > 1.01f) {
                                // 位移同样夹一下：不允许把图完全挪出屏幕
                                // （那会留下一片纯黑，用户以为图没了）。
                                offset = Offset(
                                    x = (offset.x + pan.x).coerceIn(-MAX_PAN_PX, MAX_PAN_PX),
                                    y = (offset.y + pan.y).coerceIn(-MAX_PAN_PX, MAX_PAN_PX)
                                )
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { toggleScale(it) },
                            // 单击关闭。放在 `onDoubleTap` **之后**没有关系：
                            // 系统双击判定有一个约 200ms 的窗口，期间不会派发单击，
                            // 因此"双击"不会先触发一次关闭。
                            onTap = { onDismiss() }
                        )
                    }
            )

            // ── 底部一行操作提示（本轮新增）────────────────────────────────────
            //
            // 需求 20 的「优化」不只是能放大：用户第一次用时**不知道**这图能缩放。
            // 一行常驻的小字把三件事说清（双击放大 / 拖动查看 / 点击关闭），
            // 成本是一行字，省掉的是"这图怎么不动"的困惑。
            //
            // 位置在底部而非顶部：顶部留给调用方自己的「保存到相册」按钮
            // （记事本那页有，见 `NotebookScreen.ImagePreviewDialog`），
            // 两者叠在一起会互相挡。
            Text(
                text = if (scale > 1.01f) {
                    "已放大 ${"%.1f".format(scale)} 倍 · 拖动查看 · 双击还原"
                } else {
                    "双击放大 · 点击关闭"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            )
        }
    }
}

/** 缩放下限：1 = 原始大小（Fit 之后的样子），不允许缩小到看不清。 */
private const val MIN_SCALE = 1f

/**
 * 缩放上限：5 倍。
 *
 * 取 5 而不是更大：预览图本身已经过采样（记事本 1080px、智能体 1080px），
 * 放大到 5 倍时单个像素已经明显可见，再放大只是把马赛克放大。
 */
private const val MAX_SCALE = 5f

/** 双击后的目标放大倍数。 */
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * 单指拖动的位移上限（px）。
 *
 * 粗略地按"图占满屏幕时的宽高"给一个量级：够把图从一头拖到另一头，
 * 又不至于把图完全拖出视野留下一片纯黑。
 */
private const val MAX_PAN_PX = 1200f

/**
 * 把一张位图包成「加载失败也不崩」的 [ImageBitmap]。
 *
 * 位图可能已被系统回收（低内存下会发生，而状态还没更新），
 * `asImageBitmap()` 会抛异常；兜一层宁可回落 null 显示加载态，
 * 也不要让看图这个纯展示动作把页面弄崩。
 */
fun Bitmap.asImageBitmapOrNull(): ImageBitmap? = runCatching { asImageBitmap() }.getOrNull()
