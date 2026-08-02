package com.dianguard.app

import android.content.Context
import android.util.Log
import com.dianguard.app.BuildConfig
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用内更新检测：查询 GitHub 上发布的最新 Release。
 *
 * 仓库固定为 Lokeily/Earthquake-Sentinel；调用 `releases/latest`（仅取最新正式版，
 * 忽略草稿/预发布）。比较最新版本号与本地 BuildConfig.VERSION_NAME，
 * 若更新则回传下载链接（最新 Release 资源 browser_download_url）。
 *
 * 注意：GitHub 匿名 API 限流 60 次/小时/IP；调用方（MainActivity）已做节流。
 */
data class UpdateInfo(
    val available: Boolean,
    val latestVersion: String = "",
    val releaseName: String = "",
    val notes: String = "",
    val downloadUrl: String = "",
    val htmlUrl: String = ""
)

object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    private const val REPO = "Lokeily/Earthquake-Sentinel"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    fun check(context: Context, callback: (UpdateInfo) -> Unit) {
        Thread {
            try {
                // 优化 #1：复用全局共享 OkHttpClient
                val client = HttpClient.instance
                val req = Request.Builder().url(API)
                    .header("Accept", "application/vnd.github+json")
                    .header("Cache-Control", "no-cache, no-store")
                    .build()
                // 修复 #20：用 use{} 包裹，确保无论成功/失败都显式关闭 ResponseBody，避免连接泄漏
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "GitHub API 返回 ${response.code}")
                        callback(UpdateInfo(false))
                        return@Thread
                    }
                    val json = JSONObject(response.body?.string() ?: "")
                val tag = json.optString("tag_name", "")
                val name = json.optString("name", "")
                val notes = json.optString("body", "")
                val html = json.optString("html_url", "")
                val assets = json.optJSONArray("assets") ?: JSONArray()
                var dl = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val an = a.optString("name", "")
                    if (an.endsWith(".apk", ignoreCase = true)) {
                        dl = a.optString("browser_download_url", "")
                        break
                    }
                }
                val latest = tag.removePrefix("v").removePrefix("V")
                val current = BuildConfig.VERSION_NAME
                val available = dl.isNotBlank() && isNewer(latest, current)
                Log.i(TAG, "当前版本=$current 最新版本=$latest 有更新=$available")
                callback(UpdateInfo(available, latest, name, notes, dl, html))
            }
            } catch (e: Exception) {
                Log.w(TAG, "更新检查失败: ${e.message}")
                callback(UpdateInfo(false))
            }
        }.start()
    }

    /** 语义化版本比较：latest > current 返回 true */
    private fun isNewer(latest: String, current: String): Boolean {
        return parseVersion(latest) > parseVersion(current)
    }

    private fun parseVersion(v: String): Long {
        val parts = v.split('.').map { p ->
            p.filter { it.isDigit() }.toLongOrNull() ?: 0L
        }
        val padded = (parts + listOf(0L, 0L, 0L)).take(3)
        return padded[0] * 1_000_000L + padded[1] * 1_000L + padded[2]
    }
}
