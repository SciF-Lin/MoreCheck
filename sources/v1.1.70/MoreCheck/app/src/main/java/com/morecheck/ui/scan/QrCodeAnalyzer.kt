package com.morecheck.ui.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * CameraX 的逐帧分析器：取出 YUV_420_888 的 **Y 平面**（灰度）交给 ZXing 解码。
 *
 * 几个刻意的取舍：
 * - **只取 Y 平面**，不做 YUV→RGB 转换：二维码识别只需要亮度信息，
 *   省掉一次整帧的颜色转换，帧率明显更高。
 * - **忽略 `rotationDegrees`**：ZXing 的二维码 / DataMatrix / Aztec 检测器本身是旋转无关的，
 *   为一维条码单独做一次旋转的收益不抵复杂度（一维码建议横向对准扫描框）。
 * - **异常一律吞掉**：解码失败在正常不过（大多数帧里根本没有码），
 *   ZXing 用异常表达「本帧没找到」，让它冒到 CameraX 的执行器上会污染日志甚至中断分析。
 * - **[ImageProxy] 必须在 finally 里 close()**，否则分析管线会迅速卡死（只拿到 1~2 帧）。
 *
 * [paused] 由外部控制：出结果后置 true 停止重复回调；用户继续扫描时置回 false。
 */
class QrCodeAnalyzer(
    private val onDecoded: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to SUPPORTED_FORMATS,
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    /** 出结果后 / 页面不可见时置 true，避免重复回调与无谓的 CPU 占用。 */
    @Volatile
    var paused: Boolean = false

    override fun analyze(image: ImageProxy) {
        try {
            if (paused) return
            val text = decode(image) ?: return
            // 解码期间可能已被暂停（页面切走 / 上一次结果还没消费掉），再确认一次。
            if (paused) return
            paused = true
            onDecoded(text)
        } catch (_: Throwable) {
            // 本帧没有可识别的码，等下一帧即可。
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null

        val data = readLuminance(plane.buffer, plane.rowStride, width, height) ?: return null

        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        // decodeWithState 会复用上一次的 hints，官方要求每次用完 reset()。
        val result = runCatching { reader.decodeWithState(bitmap) }
        reader.reset()
        return result.getOrNull()?.text
    }

    /**
     * 把 Y 平面拷贝成紧凑的 `width * height` 灰度数组。
     *
     * 注意 `rowStride` 往往大于 `width`（每行尾部有对齐填充），
     * 直接整块拷给 ZXing 会让图像逐行错位、完全解不出来 —— 必须按行剥掉填充。
     */
    private fun readLuminance(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        width: Int,
        height: Int
    ): ByteArray? {
        val data = ByteArray(width * height)
        if (rowStride <= width) {
            // 少数设备没有填充，直接整块读取（按可用字节数截断）。
            val count = minOf(data.size, buffer.remaining())
            if (count <= 0) return null
            buffer.get(data, 0, count)
            return data
        }

        val row = ByteArray(rowStride)
        var offset = 0
        repeat(height) {
            val remaining = buffer.remaining()
            if (remaining <= 0) return@repeat
            val len = minOf(rowStride, remaining)
            buffer.get(row, 0, len)
            System.arraycopy(row, 0, data, offset, minOf(width, len))
            offset += width
        }
        return data
    }

    private companion object {
        /**
         * 支持的码制。以二维码为主（旋转无关、识别率高），
         * 一并带上最常见的几种一维码，覆盖商品条码场景。
         */
        val SUPPORTED_FORMATS = listOf(
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.AZTEC,
            BarcodeFormat.CODE_128,
            BarcodeFormat.CODE_39,
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A
        )
    }
}
