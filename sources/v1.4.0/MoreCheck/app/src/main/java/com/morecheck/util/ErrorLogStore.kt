package com.morecheck.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机的崩溃 / 报错日志（2026-11-14 需求 3）。
 *
 * ## 它和 Bugly 是什么关系
 *
 * 两条链路，互不替代：
 * - [CrashMonitor] 把崩溃上报到 Bugly 控制台，**只在有网、且用户同意隐私政策后**才工作；
 *   没配 AppId 或者断网时，那条链路什么都留不下。
 * - 本对象把同一份信息**落到本机文件**，用户在「设置 → 问题与诊断 → 错误日志」里
 *   随时能看到、能复制。它不联网、不需要登录，也不受 Bugly 配置影响。
 *
 * ## 落盘策略（宁可少留，也不要撑爆存储）
 *
 * - 目录：`filesDir/logs`，每天一个文件（`log-2026-11-14.txt`）；
 * - 单文件超过 [MAX_BYTES] 后不再追加，直接写一行"已达上限"后停手；
 * - 只保留最近 [MAX_FILES] 个文件，超出的按文件名（即日期）从旧到新删掉。
 *
 * ## 崩溃为什么还能写下来
 *
 * `Thread.setDefaultUncaughtExceptionHandler` 里写完文件再**交回原来的处理器**
 * （通常是系统的，会把进程杀掉）。绝不能在处理器里 `return` 掉——那会让主线程
 * 带着异常继续跑，表现为"卡住不死"，比崩溃更难排查。
 *
 * 写入用同步 IO，并且放在 `runCatching` 里：崩溃处理器本身再抛异常，
 * 系统不会给第二次机会，日志就成了空文件。
 */
object ErrorLogStore {

    private const val TAG = "ErrorLogStore"

    /** 日志目录名（相对 `filesDir`）。 */
    private const val DIR_NAME = "logs"

    /** 单文件上限：128 KB，约几千行，够看一次崩溃的前后文。 */
    private const val MAX_BYTES = 128 * 1024

    /** 最多保留的天数（按文件个数算，一天一个）。 */
    private const val MAX_FILES = 20

    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private val fileFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    /** 一条日志：时间 + 级别 + 标题 + 详情（多行）。 */
    data class Entry(
        val time: Long,
        val level: String,
        val title: String,
        val detail: String
    )

    /**
     * 装上全局崩溃捕获。可重复调用（幂等），在 `Application.onCreate` 里调。
     *
     * @param context 任意 Context，内部只留 applicationContext。
     */
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        appContext = app
        installed = true

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                write(
                    app,
                    level = "CRASH",
                    title = "${throwable.javaClass.simpleName}: ${throwable.message ?: "无消息"}",
                    detail = buildString {
                        appendLine("线程：${thread.name}")
                        appendLine(throwable.stackTraceToString())
                    }
                )
            }.onFailure { Log.w(TAG, "写崩溃日志失败：${it.message}") }
            // 交回系统：不吞异常，进程该结束就结束。
            previous?.uncaughtException(thread, throwable)
        }
        Log.i(TAG, "错误日志已启用：${logsDir(app).absolutePath}")
    }

    /**
     * 记录一条**已捕获**的错误（网络失败、解析失败这类不会让应用退出的问题）。
     *
     * 只在用户能看到"出了错"的地方调用：它写的是本地文件，
     * 用来回答"刚才那个失败到底是什么原因"，不是调试打点工具。
     */
    fun record(tag: String, throwable: Throwable? = null, message: String = "") {
        val app = appContext ?: return
        val title = message.ifBlank { throwable?.message ?: "未提供描述" }
        runCatching {
            write(
                app,
                level = "ERROR",
                title = "[$tag] $title",
                detail = throwable?.stackTraceToString().orEmpty()
            )
        }
    }

    /** 读出所有日志（新的在前）。读失败时返回空列表，调用方不必处理异常。 */
    fun entries(context: Context): List<Entry> {
        val dir = logsDir(context.applicationContext)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        val result = mutableListOf<Entry>()
        files.forEach { file ->
            runCatching {
                file.forEachLine { line ->
                    // 以 "[时间] [级别] 标题" 起头的是新条目，其余行是上一条的详情。
                    val head = parseHeader(line)
                    if (head != null) {
                        result += Entry(head.first, head.second, head.third, "")
                    } else if (result.isNotEmpty()) {
                        val last = result.removeAt(result.lastIndex)
                        result += last.copy(detail = (last.detail + line + "\n").trimStart())
                    }
                }
            }
        }
        return result.sortedByDescending { it.time }
    }

    /** 清空所有日志文件。返回删除的文件数。 */
    fun clear(context: Context): Int {
        val dir = logsDir(context.applicationContext)
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) count++
        }
        return count
    }

    /**
     * 日志占用的字节数（设置页副标题用）。
     */
    fun totalBytes(context: Context): Long =
        logsDir(context.applicationContext).listFiles()
            ?.filter { it.isFile }
            ?.sumOf { it.length() }
            ?: 0L

    /** 把全部日志拼成一段可直接粘贴的文本（复制按钮用）。 */
    fun exportText(context: Context): String {
        val list = entries(context)
        if (list.isEmpty()) return ""
        return buildString {
            appendLine("MoreCheck ${AppInfo.versionName(context)} 错误日志（${list.size} 条）")
            appendLine()
            list.forEach { entry ->
                appendLine("[${stampFormat.format(Date(entry.time))}] [${entry.level}] ${entry.title}")
                if (entry.detail.isNotBlank()) appendLine(entry.detail.trimEnd())
                appendLine()
            }
        }
    }

    // ---- 内部实现 ----------------------------------------------------------

    private fun logsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun write(context: Context, level: String, title: String, detail: String) {
        val dir = logsDir(context)
        pruneOldFiles(dir)
        val file = File(dir, "log-${fileFormat.format(Date())}.txt")
        if (file.exists() && file.length() > MAX_BYTES) return
        file.appendText(
            buildString {
                appendLine("[${stampFormat.format(Date())}] [$level] ${title.trim().replace('\n', ' ')}")
                if (detail.isNotBlank()) appendLine(detail.trimEnd())
            }
        )
    }

    private fun pruneOldFiles(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.size <= MAX_FILES) return
        files.take(files.size - MAX_FILES).forEach { runCatching { it.delete() } }
    }

    /** 解析一行表头，返回 (时间, 级别, 标题)；不是表头返回 null。 */
    private fun parseHeader(line: String): Triple<Long, String, String>? {
        if (!line.startsWith("[")) return null
        val close = line.indexOf(']')
        if (close <= 0) return null
        val timeText = line.substring(1, close)
        val rest = line.substring(close + 1).trimStart()
        if (!rest.startsWith("[")) return null
        val levelEnd = rest.indexOf(']')
        if (levelEnd <= 0) return null
        val level = rest.substring(1, levelEnd)
        val title = rest.substring(levelEnd + 1).trimStart()
        val time = runCatching { stampFormat.parse(timeText)?.time }.getOrNull() ?: return null
        return Triple(time, level, title)
    }
}
