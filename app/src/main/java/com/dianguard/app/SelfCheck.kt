package com.dianguard.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat

/**
 * 系统自检（共用）：逐项检查预警链路是否就绪，快速定位“开着却收不到预警”的原因。
 *
 * 设计目标：
 *  - 主页“首次设置后主动自检”与设置页“系统自检”按钮共用同一份检查逻辑，避免重复实现；
 *  - 每项给出 ✅/❌ 结论 + 一句话说明，让用户一眼看清“权限开启与否”；
 *  - 提供“去设置”直达系统设置页（覆盖通知 / 定位权限），以及“重新检测”在授权后复查。
 */
data class CheckItem(
    val label: String,
    val ok: Boolean,
    val detail: String
)

object SelfCheck {

    /** 计算当前各检查项状态（主线程 / 任意线程均可调用，仅读取系统状态，无副作用） */
    fun items(context: Context): List<CheckItem> {
        val list = mutableListOf<CheckItem>()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. 通知权限
        val notifyOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else true
        list.add(
            CheckItem(
                "通知权限",
                notifyOk,
                if (notifyOk) "已授权，预警可正常推送" else "未授权，将无法收到预警推送"
            )
        )

        // 2. 悬浮窗权限（锁屏强制弹出必需）
        val overlayOk = Settings.canDrawOverlays(context)
        list.add(
            CheckItem(
                "悬浮窗权限",
                overlayOk,
                if (overlayOk) "已授权，锁屏也能强制弹出" else "未授权，预警可能无法在锁屏弹出"
            )
        )

        // 3. 定位权限（计算震中距离）
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val locOk = fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
        list.add(
            CheckItem(
                "定位权限",
                locOk,
                if (locOk) "已授权，可计算震中距离" else "未授权，无法计算震中距离"
            )
        )

        // 4. 参考位置是否已写入
        list.add(
            CheckItem(
                "参考位置",
                AppConfig.hasLocation,
                if (AppConfig.hasLocation) "已设置：${AppConfig.locationName.ifBlank { "未命名" }}"
                else "尚未获取，预警距离可能不准"
            )
        )

        // 5. 后台保活锁（WakeLock）
        if (AppConfig.serviceEnabled) {
            list.add(
                CheckItem(
                    "后台保活",
                    EewService.wakeLockHeld,
                    if (EewService.wakeLockHeld) "WakeLock 持有中" else "后台保活未生效"
                )
            )
        }

        // 6. 电池优化豁免（防止系统杀后台）
        val battOk = pm.isIgnoringBatteryOptimizations(context.packageName)
        list.add(
            CheckItem(
                "电池优化",
                battOk,
                if (battOk) "已豁免，不被系统杀后台" else "未豁免，可能被系统杀后台"
            )
        )

        return list
    }

    /** 弹出自检结果对话框：顶部汇总 + 逐项 ✅/❌，并提供"去设置 / 重新检测 / 关闭" */
    fun showDialog(context: Context) {
        showDialog(context, null)
    }

    /** 自检对话框 + 回调：用户点"继续"后执行 onContinue */
    fun showDialog(context: Context, onContinue: (() -> Unit)?) {
        val items = items(context)
        val failed = items.count { !it.ok }
        val okMark = context.getString(R.string.selfcheck_ok)
        val failMark = context.getString(R.string.selfcheck_fail)
        val lines = items.joinToString("\n") { item ->
            "${if (item.ok) okMark else failMark} ${item.label} — ${item.detail}"
        }
        val summary = if (failed == 0) "全部就绪 ✅" else "有 $failed 项需处理 ❌"
        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.selfcheck_title)
            .setMessage("$summary\n\n$lines")
            .setPositiveButton(R.string.selfcheck_open) { _, _ -> openAppSettings(context) }
            .setNeutralButton(R.string.selfcheck_recheck) { _, _ -> showDialog(context, onContinue) }
        if (onContinue != null) {
            builder.setNegativeButton("继续") { _, _ -> onContinue() }
        } else {
            builder.setNegativeButton(R.string.selfcheck_close, null)
        }
        builder.show()
    }

    /** 跳转应用详情设置页（通知 / 定位 / 电池优化等多数列表项集中在此） */
    private fun openAppSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Exception) { }
    }
}
