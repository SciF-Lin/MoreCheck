package com.morecheck.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File

/**
 * 头像图片的读写。
 *
 * 两个关键取舍：
 *
 * **为什么写在 `filesDir` 而不是外部存储 / MediaStore**：
 * 头像只给本应用用，写进应用私有目录就**完全不需要任何存储权限**——
 * 既不用申请 `READ_MEDIA_IMAGES`，也不用管分区存储在各版本上的差异。
 * 代价是用户没法从相册里看到这张裁好的图，但这本来就不是需求要的。
 *
 * **为什么解码一律降采样**：
 * 用户选的照片动辄 4000×3000（约 4800 万像素、按 ARGB_8888 算要占 190MB），
 * 整张读进内存几乎必然 OOM。头像最终只显示几十 dp，所以解码时按目标边长
 * 取 `inSampleSize` / `setTargetSampleSize`，把峰值内存压到几 MB。
 */
object AvatarStore {

    private const val DIR_NAME = "avatar"
    private const val FILE_NAME = "avatar.jpg"

    /** 导出边长（像素）。头像显示尺寸只有几十 dp，512 在 3x 屏上也足够清晰。 */
    const val EXPORT_SIZE = 512

    /** JPEG 压缩质量。90 是肉眼无损与体积的常见平衡点。 */
    const val JPEG_QUALITY = 90

    /**
     * 裁切页的解码上限（最长边像素）。
     *
     * 取 2048：比 [EXPORT_SIZE] 大得多，保证放大裁切时仍有足够细节；
     * 又远小于原图，避免把整张大图读进内存。
     */
    const val CROP_DECODE_MAX_PX = 2048

    /** 头像文件。固定文件名：换头像时直接覆盖，不留一堆历史文件。 */
    fun avatarFile(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** 存进 DataStore 的绝对路径。 */
    fun storedPath(context: Context): String = avatarFile(context).absolutePath

    /**
     * 把裁好的正方形位图写成 JPEG。
     *
     * @return 是否成功。写失败（磁盘满 / IO 异常）时界面要能给出提示，
     *   而不是静默地"以为保存了"。
     */
    fun saveAvatar(context: Context, bitmap: Bitmap): Boolean = runCatching {
        val file = avatarFile(context)
        // 目录可能不存在（首次安装后从没存过头像），必须先建。
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            // `compress` 自己会返回 false（例如编码器拒绝该配置），不能只看有没有抛异常。
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
    }.getOrDefault(false)

    /**
     * 解码已保存的头像（用于「我的信息」页显示）。
     *
     * 用 `BitmapFactory` 两遍法：
     * 第一遍 `inJustDecodeBounds = true` 只读文件头拿尺寸（不分配像素内存），
     * 据此算出 `inSampleSize` 后，第二遍才真正解码。这是平台推荐的降采样路径。
     *
     * @param path DataStore 里存的路径；文件不存在或已损坏时返回 null，
     *   调用方据此回落到灰色占位头像。
     */
    fun decodeStored(path: String, maxPx: Int = EXPORT_SIZE): Bitmap? {
        val file = File(path)
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        // outWidth/outHeight <= 0 说明不是能识别的图片（或文件被截断）。
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    /**
     * 解码用户选中的原图，供裁切页使用。
     *
     * 这里用 `ImageDecoder` 而**不是** `BitmapFactory` + `ExifInterface`，原因：
     * 1. `ImageDecoder`（API 28+，本项目 minSdk 29）**会自动按 EXIF 方向把图片摆正**，
     *    相机竖拍的照片不会躺倒，因此不需要额外处理方向；
     * 2. 它就是平台 API，**不引入任何新依赖**。
     *    若改用 `androidx.exifinterface` 得新增依赖（被明令禁止）；
     *    而平台的 `android.media.ExifInterface` 自 API 29 起已废弃，用它会产生废弃警告。
     * 3. `setTargetSize` 直接给目标像素尺寸，由解码器内部高效降采样。
     *
     * 注意这里用的是 **`setTargetSize`（API 28）而不是 `setTargetSampleSize`（API 30）**：
     * 本项目 minSdk 是 29，用 API 30 的方法会触发 NewApi 告警甚至拦住 lintVitalRelease。
     * 两者效果等价——反正采样倍数也是我们按目标边长自己算出来的。
     *
     * 强制 `ALLOCATOR_SOFTWARE`：默认可能返回硬件位图（`HardwareBitmap`），
     * 硬件位图不能直接画进软件 `Canvas`——而裁切导出正是要画进软件 Canvas，
     * 所以必须在解码阶段就要求软件位图。
     *
     * @return 解码失败（URI 失效 / 不是图片 / 内存不足）时返回 null。
     */
    fun decodeForCrop(context: Context, uri: Uri, maxPx: Int = CROP_DECODE_MAX_PX): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        // 用 runCatching 兜住 ImageDecoder 的 DecodeException 以及超大图可能的 OOM，
        // 让裁切页能显示"无法读取"而不是直接崩掉。
        return runCatching {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = sampleSizeFor(info.size.width, info.size.height, maxPx)
                // 目标尺寸至少 1px，避免极端比例下算出 0 而抛 IllegalArgumentException。
                decoder.setTargetSize(
                    (info.size.width / sample).coerceAtLeast(1),
                    (info.size.height / sample).coerceAtLeast(1)
                )
            }
        }.getOrNull()
    }

    /**
     * 算出 `inSampleSize`：保证最长边降到 [maxPx] 附近，且只取 2 的幂
     * （解码器对 2 的幂有优化，非 2 的幂会被向下取整）。
     */
    private fun sampleSizeFor(width: Int, height: Int, maxPx: Int): Int {
        if (maxPx <= 0) return 1
        var sample = 1
        val longest = maxOf(width, height)
        // 用「再小一半是否仍 >= maxPx」判断，循环结束后最长边落在 [maxPx, 2*maxPx) 区间内。
        while (longest / (sample * 2) >= maxPx) sample *= 2
        return sample
    }
}
