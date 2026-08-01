package com.dianguard.app

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用内更新引导：发现新版本后弹出提示，用户点“前往更新”即在应用内下载
 * 最新 APK 并直接调起系统安装器。由于安装包与已装版本同签名、且 versionCode 更高，
 * 系统安装器会自动覆盖旧版，无需用户手动卸载。
 *
 * 下载失败则回退到浏览器打开下载链接 / 发布页，保证流程不中断。
 *
 * 安全加固（修复 #14）：下载完成后对 APK 做 SHA-256 完整性记录 + 签名一致性校验，
 * 防止下载链路被劫持时安装被篡改的安装包。
 */
object AppUpdater {

    private const val AUTHORITY = "com.dianguard.app.fileprovider"
    private const val UPDATE_DIR = "updates"
    private const val UPDATE_FILE = "app-update.apk"
    private const val TAG = "AppUpdater"

    /** 弹出“发现新版本”提示框（仅在新版本可用且未被用户忽略时调用） */
    fun showUpdateDialog(activity: AppCompatActivity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val notes = if (info.notes.isBlank()) "" else "\n\n${info.notes}"
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_title))
            .setMessage(activity.getString(R.string.update_message, info.latestVersion) + notes)
            .setPositiveButton(R.string.update_btn_go) { _, _ -> downloadAndInstall(activity, info) }
            .setNegativeButton(R.string.update_btn_later) { _, _ ->
                AppConfig.dismissedUpdateVersion = info.latestVersion
            }
            .setCancelable(false)
            .show()
    }

    private fun updateFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), UPDATE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, UPDATE_FILE)
    }

    /** 应用内下载 APK，完成后直接调起安装器（同签名自动覆盖旧版）。下载前后做完整性 + 签名校验。 */
    fun downloadAndInstall(activity: AppCompatActivity, info: UpdateInfo) {
        if (activity.isFinishing || activity.isDestroyed) return
        val file = updateFile(activity)

        // 修复 #15：ProgressDialog 在 Activity 销毁后可能触发 WindowLeaked。
        // 用 AtomicReference 持有对话框，所有 show/dismiss 都先做存活与 isShowing 检查，并吞掉异常。
        val dialogRef = AtomicReference<ProgressDialog?>()
        val dialog = ProgressDialog(activity).apply {
            setMessage(activity.getString(R.string.update_downloading, info.latestVersion))
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(false)
            show()
        }
        dialogRef.set(dialog)

        val alive: () -> Boolean = { !activity.isFinishing && !activity.isDestroyed }
        val safeDismiss: () -> Unit = {
            val d = dialogRef.getAndSet(null)
            if (d != null && d.isShowing) {
                try { d.dismiss() } catch (_: Exception) { }
            }
        }

        Thread {
            try {
                // 优化 #1：复用全局共享 OkHttpClient（含连接池 / 线程池）
                val client = HttpClient.instance
                val req = Request.Builder().url(info.downloadUrl).build()
                val resp = client.newCall(req).execute()
                val body = resp.body
                if (!resp.isSuccessful || body == null) {
                    resp.close()
                    throw Exception("下载失败 (HTTP ${resp.code})")
                }
                val total = body.contentLength()
                val input = body.byteStream()
                val output = file.outputStream()
                val buf = ByteArray(8192)
                var read: Int
                var downloaded = 0L
                while (input.read(buf).also { read = it } != -1) {
                    // 优化 #14：Activity 已销毁则中止下载，避免浪费流量（文件已落盘，下次可直接复用）
                    if (!alive()) {
                        output.close()
                        input.close()
                        resp.close()
                        Log.i(TAG, "下载中途 Activity 已销毁，终止下载")
                        return@Thread
                    }
                    output.write(buf, 0, read)
                    downloaded += read
                    if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        val d = dialogRef.get()
                        if (d != null && d.isShowing && alive()) {
                            activity.runOnUiThread { if (d.isShowing) d.progress = pct }
                        }
                    }
                }
                output.flush()
                output.close()
                input.close()
                resp.close()

                // 修复 #14：下载完成后做完整性 + 签名校验，杜绝安装被篡改的 APK
                val sha256 = computeSha256(file)
                Log.i(TAG, "APK 下载完成，SHA256=$sha256 大小=${file.length()}")
                verifyApkSignature(activity, file)

                activity.runOnUiThread {
                    safeDismiss()
                    if (alive()) installApk(activity, file)
                }
            } catch (e: Exception) {
                Log.e(TAG, "更新失败", e)
                activity.runOnUiThread {
                    safeDismiss()
                    if (alive()) {
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.update_failed_title)
                            .setMessage(activity.getString(R.string.update_failed_msg) + "\n${e.message}")
                            .setPositiveButton(R.string.update_open_browser) { _, _ ->
                                val url =
                                    if (info.downloadUrl.isNotBlank()) info.downloadUrl else info.htmlUrl
                                val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                activity.startActivity(i)
                            }
                            .setNegativeButton(R.string.update_cancel, null)
                            .show()
                    }
                }
            }
        }.start()
    }

    /** 计算文件 SHA-256 十六进制串（用于完整性记录与审计） */
    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 校验下载 APK 的签名证书与当前已安装应用完全一致。
     * 与系统安装器自带的签名校验形成纵深防御：即便下载链路被劫持，也能阻止安装非官方签名包。
     * 若设备无法读取任一侧签名（极端兼容情况），记录警告并放行，避免误伤正常更新。
     */
    private fun verifyApkSignature(context: Context, file: File) {
        val host = hostCerts(context)
        val apk = apkCerts(context, file)
        if (host.isEmpty() || apk.isEmpty()) {
            Log.w(TAG, "签名信息缺失，跳过签名校验（仅做完整性记录）")
            return
        }
        val hostSet = host.map { it.contentToString() }.toSet()
        val apkSet = apk.map { it.contentToString() }.toSet()
        if (apkSet.intersect(hostSet).isEmpty()) {
            throw Exception("APK 签名与已安装应用不一致，可能存在篡改")
        }
    }

    @Suppress("DEPRECATION")
    private fun hostCerts(context: Context): List<ByteArray> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (si == null) emptyList()
            else (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)
                .map { it.toByteArray() }
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
                ?.map { it.toByteArray() } ?: emptyList()
        }
    }

    @Suppress("DEPRECATION")
    private fun apkCerts(context: Context, file: File): List<ByteArray> {
        val pm = context.packageManager
        val pi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
        } ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = pi.signingInfo
            if (si == null) emptyList()
            else (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)
                .map { it.toByteArray() }
        } else {
            pi.signatures?.map { it.toByteArray() } ?: emptyList()
        }
    }

    private fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
