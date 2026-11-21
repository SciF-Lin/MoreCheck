package com.morecheck.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.morecheck.util.AvatarStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * DataStore 委托（Preferences）。
 *
 * ⚠️ 这里**必须**是本文件独有的名字（`notebook`），不能改成 `settings` 去"复用"设置那一个。
 *
 * 原因不是风格问题，而是一个会崩溃的硬约束：`preferencesDataStore` 委托每声明一次
 * 就产生一个**独立的** `DataStore` 实例（委托内部只在它自己那个实例里缓存 INSTANCE，
 * 两个委托互相看不见）。而 DataStore 内部对**同一个文件**有全进程唯一性校验——
 * 两个实例同时持有连接时，第二次 `createConnection()` 会直接抛
 * `IllegalStateException: There are multiple DataStores active for the same file: ...`
 * （已在本机解包 `datastore-core` / `datastore-core-okio` 的字节码确认该分支存在，
 * 字符串就是这句原文）。
 *
 * 本项目里这个崩溃是**必然会发生的**，不是概率问题。解包 `datastore-core` 的字节码可以看到：
 * 存储连接是 `DataStoreImpl` 里一个 `lazy` 字段（`storageConnectionDelegate`），
 * 一旦被读取就注册进 `FileStorage` / `OkioStorage` 的进程级 `activeFiles` 集合，
 * 而 `DataStoreImpl` **没有任何地方关闭它**——收集者计数归零时只取消 `collectorJob`，
 * 连接本身直到进程结束都留在集合里。也就是说，只要设置那一个 DataStore 被用过一次
 * （`MainActivity` 一开始就读 `themeMode` / `themeAccent`），同文件的第二个实例
 * 一去创建连接就必然抛异常。
 *
 * 所以这里用**自己的文件** `datastore/notebook.preferences_pb`：
 * 记事本的文字与图片路径各自只有一个读写方，天然不存在"同一份偏好在两处不一致"的风险；
 * 而设置那一个文件一个字节都不会动。
 *
 * （如果以后确实要把笔记并入 settings 文件，正确做法是往 `SettingsRepository` 里加一个键
 * 与一个 setter——那需要改 `SettingsRepository.kt`，超出本次改动范围，故未采用。）
 */
private val Context.notebookDataStore by preferencesDataStore(name = "notebook")

/**
 * 「实验室 → 记事本」的数据层。
 *
 * 需求只要求**一条**笔记（不是列表、不是多条），所以这里没有 id、没有时间戳、没有增删改查，
 * 只有"一段文字 + 若干图片路径"这一份状态。
 *
 * ## 文字与图片分别存在哪
 *
 * - **文字**：`datastore/notebook.preferences_pb`，键 `notebook_text`（String）。
 * - **图片**：图片本体写在应用私有目录 `filesDir/notebook/note_<uuid>.jpg`，
 *   DataStore 里只存**绝对路径**（键 `notebook_image_paths`，多张用换行符连接）。
 *
 * 不把图片字节塞进 DataStore：Preferences DataStore 适合小标量，每次读写都会整体序列化，
 * 塞几百 KB 二进制会让它明显变慢。这套"私有目录存文件 + DataStore 存路径"的做法与
 * `AvatarStore` / `avatarPath` 完全一致（见 `SettingsRepository` 的 `AVATAR_PATH` 注释）。
 *
 * ## 为什么图片路径用换行符连接，而不是 DataStore 的 stringSet
 *
 * 与 `SettingsRepository.TIMER_RECENT_DURATIONS` 用逗号连接同理：`stringSet` **无序**，
 * 而这排缩略图必须按用户添加的先后顺序显示。换行符也不会出现在应用私有目录的路径里，
 * 作为分隔符是安全的。
 *
 * ## 为什么这个对象里要有一个进程级 [ioScope]
 *
 * 本页要求"返回时自动保存"，而"返回"是**同步**回调：页面随即被销毁，
 * `rememberCoroutineScope()` 拿到的组合作用域也会被取消。若用那个作用域去落盘，
 * 写操作可能在真正执行前就被取消——这正是"丢最后一次编辑"的典型成因。
 * [ioScope] 与任何界面生命周期无关（随进程存活），提交给它的写操作一定会跑完，
 * 于是"先导航、后落盘"才是安全的。
 *
 * 它带 `SupervisorJob`，且每个提交都套了 `runCatching`：单次磁盘失败（磁盘满等）
 * 不该把整个作用域带崩，更不该让应用崩溃。
 */
object NotebookStore {

    /**
     * 图片数量上限。
     *
     * ## 为什么从 3 提到 9（v1.1.68 需求 4「增加图片上限」）
     *
     * 3 张是上一版按"刚好摆满一行、不换行"定的；本轮需求明确要求提高上限，
     * 而用户加图之后**会点击预览、也会长按删除**（需求 4 同时新增了这两个交互），
     * 说明他确实会放进多张再挑，因此给到 9 张。
     *
     * 取 9 而不是更大的数，理由：
     * - **9 = 3×3 九宫格**，是最广为人知的多图上限，用户不必学就知道"最多九张"；
     *   界面沿用既有的**单行横向滚动**排布（`horizontalScroll`），
     *   一排占到底、不换行，因此缩略图那一块的高度**始终只有一行**，
     *   9 张也不会把上面的正文输入框挤扁（只有宽度需要滑动，而滑动本来就有）；
     * - 单张按最长边 [IMAGE_DECODE_MAX_PX] 压缩后约 300~600KB，
     *   9 张约 3~5MB。仍在"应用私有目录随意写入"的合理量级内，
     *   也不会因为一次性解码 9 张缩略图而明显卡顿（缩略图走 [THUMB_DECODE_MAX_PX] 小图）；
     * - 再多就真的变成相册了，而需求说的是"一条笔记"。
     *
     * ⚠️ 改这个值会同时影响三处（都由本常量驱动，不必手工同步）：
     * `PickMultipleVisualMedia` 的多选上限、界面上的"已添加 N/上限"、以及落盘时的截断长度。
     */
    const val MAX_IMAGES = 9

    /**
     * 存盘图片的最长边（像素）。
     *
     * 取 1600：缩略图只有几十 dp，但用户可能点开看或以后想在别处显示，
     * 1600 足够清楚；同时把 4000×3000 的原图降采样，避免整张读进内存（同 `AvatarStore` 的取舍）。
     */
    const val IMAGE_DECODE_MAX_PX = 1600

    /** JPEG 压缩质量，与 `AvatarStore` 取同一档（肉眼无损与体积的常见平衡点）。 */
    const val JPEG_QUALITY = 90

    /**
     * 缩略图解码的最长边。
     *
     * 缩略图显示尺寸只有 72dp，按 3x 屏算也就 216px，256 足够；
     * 取值小意味着 `BitmapFactory` 的 `inSampleSize` 更大、峰值内存更低。
     */
    const val THUMB_DECODE_MAX_PX = 256

    /**
     * 全屏预览解码的最长边（v1.1.68 需求 4 新增）。
     *
     * 取 1080：常规手机屏宽就是 1080px 量级，按 `ContentScale.Fit` 全屏显示时
     * 这个分辨率已经看不出模糊，而再高（例如原图 1600）只是白占内存——
     * 预览是**一次只看一张**，1080px 的 ARGB_8888 位图约 4.6MB，安全。
     *
     * 与 [THUMB_DECODE_MAX_PX] 分开是两个场景诉求相反，见 [decodePreview] 的说明。
     */
    const val PREVIEW_DECODE_MAX_PX = 1080

    private const val DIR_NAME = "notebook"

    /**
     * 保存到相册时给文件名加的前缀（`morecheck_note_xxx.jpg`）。
     *
     * 用**全小写的应用名**而不是中文：部分相册 / 文件管理器对 `DISPLAY_NAME` 里的
     * 非 ASCII 字符处理不一致，而这个小前缀的唯一用途是"让用户在相册里认出它从哪来"，
     * ASCII 更稳；重名由 `MediaStore` 自己补 `(1)`，不靠时间戳。
     */
    private const val GALLERY_DISPLAY_PREFIX = "morecheck_"

    /**
     * 写进相册时的 MIME 类型。见 [saveImageToGallery] 的说明：
     * 本页图片统一由 [addImage] 以 JPEG 压缩，这里写死是**准确**而不是偷懒。
     */
    private const val GALLERY_MIME_TYPE = "image/jpeg"

    /**
     * 图片在相册里的相对落点：`Pictures/MoreCheck`（API 29+ 的 `RELATIVE_PATH`）。
     *
     * 用 `Environment.DIRECTORY_PICTURES` 拼而不是写死 `"Pictures"`：
     * 这个常量就是平台对"相册目录"的定义，引用它可避免拼错、也表明我们落的是标准位置。
     * 后面那层 `MoreCheck` 是我们自己加的**独立分组**，让导出的图不与
     * 用户自己的几千张照片混在 `Pictures` 根目录里。
     *
     * ⚠️ 是 `val` 而**不是** `const val`：`Environment.DIRECTORY_PICTURES` 是 Java 类的
     * `static final String`，在 Kotlin 眼里不是**编译期常量**（它没有 `const` 语义），
     * 用它做 `const val` 的初始化器会直接编译失败
     *（`Const 'val' initializer must be a constant value`）。
     * 用 `private val`（顶层 object 的字段，仍是单例、只求值一次）语义完全够用。
     */
    private val GALLERY_RELATIVE_PATH =
        Environment.DIRECTORY_PICTURES + "/MoreCheck"

    /** 多张路径之间的分隔符（见类注释：不能用 stringSet）。 */
    private const val PATH_SEPARATOR = "\n"

    private object Keys {
        /** 笔记正文。空白时**移除该键**（而不是写空串），让"没写过"只有一种表示法。 */
        val TEXT = stringPreferencesKey("notebook_text")

        /** 图片绝对路径，换行符连接。 */
        val IMAGE_PATHS = stringPreferencesKey("notebook_image_paths")
    }

    /** 见类注释：与界面生命周期无关的写盘作用域。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- 读 ----------------------------------------------------------------

    /**
     * 读取唯一的那一条笔记；**读失败返回 null**。
     *
     * 用 `first()` 取一次快照，而不是让界面去 `collect` 这条流：
     * 页面里文本框自己持有"正在编辑"的状态，如果还同时订阅 DataStore 流，
     * 自己刚写下去的值会再发射回来，把正在敲的字覆盖掉（典型的自读自写回环）。
     *
     * ## 为什么返回可空，而不是读失败时回落到空笔记
     *
     * `data` 流在读盘出错时会抛 `IOException`（本项目 `ScanScreen` 也遇到过并做了处理）。
     * 若这里容错成"空笔记"，界面就会把用户的正文当成空的、
     * 接着在防抖到点时**把真正的笔记覆盖成空**——读失败反而变成数据丢失。
     * 因此这里把失败**如实告诉调用方**（返回 null）：界面据此既不覆盖正文，
     * 也不启动自动落盘，只在退出时按用户实际输入保存。宁可少存一次，也不能凭空清空。
     */
    suspend fun read(context: Context): NotebookNote? = runCatching {
        val prefs = context.notebookDataStore.data.first()
        NotebookNote(
            text = prefs[Keys.TEXT].orEmpty(),
            imagePaths = splitPaths(prefs[Keys.IMAGE_PATHS])
        )
    }.getOrNull()

    // ---- 写：文字 ----------------------------------------------------------

    /**
     * 保存正文（挂起版）。
     *
     * 由界面在"打字停顿"后调用（防抖）。退出路径用的是下面的 [saveTextAsync]：
     * 那个回调是同步的，不能在这里挂起。
     */
    suspend fun saveText(context: Context, text: String) {
        context.notebookDataStore.edit { prefs ->
            // 清空笔记时移除键：与 `setAvatarPath(null)` 同一口径，
            // 让"没有内容"只有 null 一种表示法，读取端不用同时兼容 null 与 ""。
            if (text.isEmpty()) prefs.remove(Keys.TEXT) else prefs[Keys.TEXT] = text
        }
    }

    /**
     * 保存正文（**不等待**版），供"返回 / 页面被销毁"这类同步回调使用。
     *
     * 提交到 [ioScope] 而不是组合作用域，写操作不会因为页面销毁被取消（见类注释）。
     * 调用方据此可以立刻导航，不必等磁盘往返。
     */
    fun saveTextAsync(context: Context, text: String) {
        val appContext = context.applicationContext
        ioScope.launch { runCatching { saveText(appContext, text) } }
    }

    // ---- 写：图片 ----------------------------------------------------------

    /**
     * 图片列表变化后落盘；[removedPath] 不为 null 时顺带删掉那张被移除的文件。
     *
     * 先落盘、再删文件，顺序是**故意**的：万一中途进程被杀，
     * 最坏是留下一个没人引用的文件（占点空间），而不是"列表里留着一个已经被删掉的路径"
     * （那会让那张缩略图变成永远读不出来的占位）。
     *
     * 图片列表在**每次增删时**就即时落盘，不像文字那样等"返回时"：
     * 因为图片是"选完就已经产生了一个实体文件"的重操作，越早把路径与文件对齐越安全；
     * 这样也能让页面在配置变更（旋转）后重建时，直接从盘上读回正确的列表。
     */
    fun commitImagesAsync(context: Context, paths: List<String>, removedPath: String? = null) {
        val appContext = context.applicationContext
        val snapshot = paths.toList()
        ioScope.launch {
            runCatching { saveImagePaths(appContext, snapshot) }
            if (removedPath != null) {
                runCatching { File(removedPath).takeIf { it.isFile }?.delete() }
            }
        }
    }

    /** 保存图片路径列表，并顺手做一次去空与截断，保证任何调用路径都写不进越界数据。 */
    suspend fun saveImagePaths(context: Context, paths: List<String>) {
        val cleaned = paths.filter { it.isNotBlank() }.take(MAX_IMAGES)
        context.notebookDataStore.edit { prefs ->
            if (cleaned.isEmpty()) {
                prefs.remove(Keys.IMAGE_PATHS)
            } else {
                prefs[Keys.IMAGE_PATHS] = cleaned.joinToString(PATH_SEPARATOR)
            }
        }
    }

    /**
     * 把用户选中的一张图解码、压缩、写进私有目录，返回其绝对路径。
     *
     * 复用 `AvatarStore.decodeForCrop` 而不是自己再写一遍解码，是为了**只维护一套**踩过坑的逻辑：
     * - 用 `ImageDecoder`（API 28+，本项目 minSdk 29）自动按 EXIF 方向摆正，
     *   相机竖拍的照片不会躺倒，且不需要引入 `androidx.exifinterface`（禁止新增依赖）；
     * - `setTargetSize` 直接按目标边长降采样，不会把 4000×3000 原图整个读进内存；
     * - 强制 `ALLOCATOR_SOFTWARE`。
     * 它的名字里有 `ForCrop`，但它做的事本质是"安全地解码用户从选择器拿到的 URI"，
     * 与本页需求完全一致，因此直接复用（另起一套风格不同的实现正是需求要避免的）。
     *
     * 写盘失败（磁盘满 / 编码器拒绝）返回 null：界面据此提示，而不是静默地"以为加上了"。
     *
     * 位图**不主动 recycle**：与 `AvatarCropScreen` 同一取舍——这里虽然压缩完就不再使用它，
     * 但主动回收一旦与别处（例如预览）仍有引用就会撞上
     * "Canvas: trying to use a recycled bitmap" 崩溃；每次只处理一张，峰值内存本就可控，
     * 交给 GC 更稳。
     */
    suspend fun addImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = AvatarStore.decodeForCrop(context, uri, maxPx = IMAGE_DECODE_MAX_PX)
            ?: return@withContext null

        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) return@withContext null

        // 文件名用 UUID 而不是"按序号命名"：序号会与并发的增删撞名，
        // 而这里每次添加都是一张全新的图，独立文件名最省心（同 `AvatarStore` 的固定名不适用，
        // 因为头像只有一张、这里最多 [MAX_IMAGES] 张且可分别删除——上限写成常量，
        // 避免以后调整上限时这句注释变成错的）。
        val file = File(dir, "note_${UUID.randomUUID()}.jpg")
        val ok = runCatching {
            file.outputStream().use { out ->
                // `compress` 会自己返回 false（例如编码器拒绝该配置），不能只看有没有抛异常。
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }.getOrDefault(false)

        if (!ok) {
            // 失败时把可能已写了一半的残文件清掉，避免留下无法解码的垃圾文件。
            runCatching { if (file.isFile) file.delete() }
            null
        } else {
            file.absolutePath
        }
    }

    /**
     * 解码一张已保存的图片作为缩略图（**必须在 IO 线程调用**）。
     *
     * 直接转发给 `AvatarStore.decodeStored`：它用 `BitmapFactory` 两遍法
     * （先只读文件头拿尺寸、算出 `inSampleSize`、再真正解码），
     * 文件不存在或已损坏时返回 null，界面回落到占位图。
     */
    fun decodeThumb(path: String): Bitmap? = AvatarStore.decodeStored(path, THUMB_DECODE_MAX_PX)

    /**
     * 解码一张已保存的图片用于**全屏预览**（**必须在 IO 线程调用**）。
     *
     * 与 [decodeThumb] 的唯一区别是解码目标尺寸：缩略图只要 256px 级别就够
     * （省内存、列表滚动流畅），而全屏预览解到 1080px 级别才不糊。
     *
     * 刻意**不共用同一个上限**：如果预览也解成 256px，放大到全屏会明显模糊；
     * 反过来若缩略图解到 1080px，9 张一起解码就是几十 MB 位图，低端机上直接 OOM。
     * 两个场景的诉求相反，所以各用一个常量，而不是取中间值两头都不讨好。
     */
    fun decodePreview(path: String): Bitmap? =
        AvatarStore.decodeStored(path, PREVIEW_DECODE_MAX_PX)

    // ---- 导出：保存到系统相册 ---------------------------------------------

    /**
     * 把本页存过的一张图**再下载一份到系统相册**（v1.1.69 需求 3）。
     *
     * ## 为什么需要它
     *
     * 记事本的图片此前只能在本应用内预览：图片本体躺在应用私有目录
     *（`filesDir/notebook/`）里，别的应用既看不见也拿不到，用户想"把这张图存下来
     * 发出去"没有任何入口。本方法就是那个入口。
     *
     * ## 为什么**零权限**（本文件最关键的取舍）
     *
     * 用 `MediaStore` 的 `insert` 通道写入相册时，**写入方是自己的应用**，
     * 平台不需要任何存储权限——需要 `WRITE_EXTERNAL_STORAGE` 的是
     * "往公共目录直接写 `File`"那条老路（那条路在 API 29 起本就已被分区存储废掉）。
     * 本项目 minSdk 29，[MediaStore.Images.Media.RELATIVE_PATH] 与
     * [MediaStore.Images.Media.IS_PENDING] 都可用，因此本功能
     * **不需要在 manifest 里加任何权限**，与「头像」「Photo Picker」一贯的零权限口径一致。
     * 这也是刻意不用已废弃的 `MediaStore.Images.Media.insert(...)` 的原因：
     * 那个 API 在 API 29 起标记废弃（它走的是 `DATA` 列那条老路），
     * 且失败时只返回 null、无法表达"插了半张"，不符合下面的三步约束。
     *
     * ## 标准三步：insert → 写字节 → 发布
     *
     * 1. `insert` 时带 `IS_PENDING = 1`：这一行对**其它应用不可见**，
     *    相册不会在图片只写了一半时就把它列出来（否则用户会看到一张 0 字节的坏图，
     *    而系统扫描器与第三方相册的缓存可能把这半张图一直留着）；
     * 2. 写入字节；
     * 3. `IS_PENDING = 0` 发布，此时图片才对相册可见。
     *
     * ⚠️ **任一步失败都必须清理已插入的行**（`resolver.delete`）。
     * 不清理的后果是实打实的：`insert` 已经把一行元数据提交给 `MediaStore`，
     * 后面写字节失败（磁盘满、源文件损坏、流被关）时，那一行会**永远留在相册里**，
     * 表现为一张打不开、也删不掉的 0 字节图——比"没保存成功"更让用户困惑。
     *
     * ## 命名与落点
     *
     * - `DISPLAY_NAME` = `morecheck_<relativeName>`：加前缀是为了让用户在相册
     *   "最近"里一眼认出这是本应用存出来的，而不是某张随手拍的照片。
     *   `MediaStore` 自己会在重名时补 `(1)` 后缀，不需要我们用时间戳抢唯一；
     * - `RELATIVE_PATH` = `Pictures/MoreCheck`（API 29+ 支持）：让图片落在相册里
     *   一个**独立分组**，而不是散在 `Pictures` 根目录与几千张照片混在一起；
     * - `MIME_TYPE` 固定 `image/jpeg`：本页图片统一由 [addImage] 以 JPEG 压缩写入
     *   （见 [JPEG_QUALITY]），不存在别的格式，写死才是准确的。
     *
     * ## 线程与参数
     *
     * **必须在 IO 线程调用**：本方法内部会读源文件、写公共存储，都是阻塞 IO。
     * 为了让"必须"在类型上就成立，这里做成 `suspend` 并自己 `withContext(Dispatchers.IO)`，
     * 调用方在任意协程里直接调即可，不需要自己再切线程。
     *
     * [relativeName] 是**相对于本页私有目录的文件名**（例如
     * `note_<uuid>.jpg`，即 `addImage` 返回值经 `File(path).name` 得到的那一段），
     * **不是绝对路径**。刻意收窄成相对名而不是收绝对路径：
     * 本方法只该导出本页自己存下来的图，收绝对路径等于允许调用方传任意文件进来。
     * 为了容错，若按相对名找不到、而把它当路径能找到文件，也照常处理。
     *
     * @return true 表示图片已发布进相册；false 表示源文件不存在、或三步中任一步失败
     *   （失败时**不会**在相册留下任何残留行）。
     */
    suspend fun saveImageToGallery(context: Context, relativeName: String): Boolean =
        withContext(Dispatchers.IO) {
            // 源文件解析：先在私有图片目录下按相对名找；
            // 找不到再把它当路径试一次（见上面 KDoc 关于参数口径的说明）——
            // `File(父目录, 绝对路径)` 会拼出一个根本不存在的怪路径，
            // 不兜这一层就会因为一个命名歧义把整张图判成"保存失败"。
            val source = listOf(
                File(File(context.filesDir, DIR_NAME), relativeName),
                File(relativeName)
            ).firstOrNull { it.isFile } ?: return@withContext false

            val resolver: ContentResolver = context.contentResolver

            // ---- 第 1 步：insert（挂起为 pending，外部不可见）----
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$GALLERY_DISPLAY_PREFIX$relativeName")
                put(MediaStore.Images.Media.MIME_TYPE, GALLERY_MIME_TYPE)
                put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            // `insert` 在 MediaStore 不可用 / 空间不足时会抛（而不是返回 null），
            // 因此这里 runCatching 与 null 判断都要有：前者挡异常，后者挡"静默失败"。
            val uri: Uri = runCatching {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }.getOrNull() ?: return@withContext false

            // ---- 第 2 步：写字节 ----
            val written = runCatching {
                // `openOutputStream` 可能返回 null（这一行刚被其它进程删掉等），
                // 不能直接 `!!`。
                val out = resolver.openOutputStream(uri) ?: return@runCatching false
                out.use { target ->
                    source.inputStream().use { input -> input.copyTo(target) }
                }
                true
            }.getOrDefault(false)

            if (!written) {
                cleanupPending(resolver, uri)
                return@withContext false
            }

            // ---- 第 3 步：发布（IS_PENDING = 0）----
            val published = runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }.isSuccess

            if (!published) {
                // 发布失败同样要清理：这一行仍对外不可见，留在库里只是一条永远
                // 挂着的 pending 记录（相册里查不到、用户也删不掉）。
                cleanupPending(resolver, uri)
                return@withContext false
            }
            true
        }

    /**
     * 删掉一条已经插进 `MediaStore`、但没能完整写入 / 没能发布的行。
     *
     * 单独抽出来是因为**有两个失败点**（写字节失败、发布失败）都要做同一件事，
     * 而它本身就带 `runCatching`：清理失败（例如这一行已被系统回收）不该把
     * "保存失败"这个结果再变成一次崩溃——用户要的是"没存上"这个明确结论。
     */
    private fun cleanupPending(resolver: ContentResolver, uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    /** 把换行符连接的路径串拆回列表（顺带滤掉空串，兼容历史上的脏数据）。 */
    private fun splitPaths(raw: String?): List<String> =
        raw?.split(PATH_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.take(MAX_IMAGES)
            ?: emptyList()
}

/**
 * 唯一的那一条笔记。
 *
 * 没有 id / 时间戳 / 标题：需求只要一条笔记，多出来的字段迟早会被误当成"支持多条"的入口。
 */
data class NotebookNote(
    /** 正文；空串表示用户清空了内容（或从没写过）。 */
    val text: String,
    /** 图片绝对路径，按添加先后排列；空列表表示没有图片。 */
    val imagePaths: List<String>
)
