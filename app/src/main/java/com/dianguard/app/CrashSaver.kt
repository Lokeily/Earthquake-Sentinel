package com.dianguard.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 崩溃自记录（正式软件化 v1.4.2）：Application 级全局崩溃捕获。
 *
 * 与旧版（MainActivity.installCrashSaver）的差异：
 *  - 从 MainActivity 迁到 Application 级——任何进程（前台 UI / 后台 EewService /
 *    广播接收器 / 开机自启）崩溃都能被捕获，而不只是 Activity 所在进程；
 *  - 崩溃堆栈写入 App 私有外部存储 crash.txt（脱敏后），便于无 adb 时排查“一开就闪退”；
 *  - 下次启动时由 MainActivity 读取并提示用户（见 CrashSaver.pendingCrashReport）。
 *
 * 注意：本类不接入任何第三方崩溃上报服务，崩溃日志仅保存在用户本机，
 * 不上传、不统计，符合免责声明「不收集、不上报您的任何个人信息」。
 */
object CrashSaver {

    private const val FILE_NAME = "crash.txt"
    private const val MAX_LINES = 200

    /** 崩溃记录器只允许安装一次：进程级标志（Application 单例，天然只装一次） */
    @Volatile private var installed = false

    /** 崩溃文件（脱敏后）。dir 取 getExternalFilesDir，随应用卸载自动清除，无需用户清理 */
    private fun crashFile(appCtx: Context): File {
        val dir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        return File(dir, FILE_NAME)
    }

    /** 安装全局未捕获异常处理器（幂等，可在 Application.onCreate 调用一次） */
    fun install(appCtx: Context) {
        if (installed) return
        installed = true
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val raw = "time=${System.currentTimeMillis()}\nthread=${thread.name}\n${sw}"
                val cleaned = redactCoordinates(raw)
                val lines = cleaned.lines().take(MAX_LINES).joinToString("\n")
                crashFile(appCtx).writeText(lines)
            } catch (_: Exception) { }
            // 链式传递给系统默认处理器，保证进程仍按系统规则退出
            prior?.uncaughtException(thread, throwable)
        }
    }

    /** 是否存在待查看的崩溃记录（上次崩溃未读） */
    fun hasPendingCrash(appCtx: Context): Boolean =
        crashFile(appCtx).exists() && crashFile(appCtx).length() > 0

    /** 读取崩溃记录内容（脱敏后），供“查看崩溃日志”入口展示 */
    fun readCrashLog(appCtx: Context): String =
        try {
            crashFile(appCtx).readText().ifBlank { "" }
        } catch (_: Exception) { "" }

    /** 用户已读（点“不再提示”或“查看日志”后）——清除记录，下次崩溃才再提示 */
    fun clearCrash(appCtx: Context) {
        try { crashFile(appCtx).delete() } catch (_: Exception) { }
    }

    /** 将崩溃日志中的精确坐标类字段与密钥类字段脱敏，防止位置隐私/API Key 随 crash.txt 泄露 */
    internal fun redactCoordinates(text: String): String {
        // 顺序关键：先收敛完整 URL（含 ?token=）再处理散落键值对，避免 URL 骨架被二次改写
        val urlRedacted = text
            // 完整 URL 形态：wss://host/path?token=XXXX 整体收敛为 URL 骨架 + 脱敏标记
            .replace(Regex("(?i)(wss?://[^\\s?&]+\\?[^\\s]*?)(token|apikey|key)=[^\\s&]+"), "\$1\$2=[REDACTED]")
        return urlRedacted
            .replace(Regex("(?i)(home_?lat|latitude|lat)[\\s=:]+[-\\d.]+"), "\$1=[REDACTED]")
            .replace(Regex("(?i)(home_?lon|longitude|lon)[\\s=:]+[-\\d.]+"), "\$1=[REDACTED]")
            .replace(Regex("(?i)(location_?name|address)[\\s=:]+\\S+"), "\$1=[REDACTED]")
            // v1.4.3 安全增强：token 明文（如 beecld WS URL ?token=...，或日志中的键值对）
            // 一旦随异常消息/OkHttp URL 进入崩溃日志即脱敏，防止 API Key 外泄。
            .replace(Regex("(?i)(token|api[_-]?key|secret|password)[\\s=:]+[\\w.\\-+=/%]+"), "\$1=[REDACTED]")
    }
}
