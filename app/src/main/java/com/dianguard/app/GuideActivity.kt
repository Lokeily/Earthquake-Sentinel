package com.dianguard.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 厂商保活引导页：老手机 ROM 会强制杀后台，本页引导用户把地震哨兵加入
 * 自启动白名单 / 电池优化白名单 / 多任务锁定，否则预警服务会被系统回收。
 */
class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        // 顶部栏返回按钮（主题为 NoActionBar，无系统返回键）
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<Button>(R.id.btn_app_details).setOnClickListener {
            // 打开本应用详情页（可在此设置电池/权限/锁定）
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_battery).setOnClickListener {
            // 电池优化白名单（API 23+）
            try {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                } else {
                    Intent(Settings.ACTION_SETTINGS)
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        findViewById<Button>(R.id.btn_oem).setOnClickListener { openOemAutostart() }
    }

    /** 尝试跳转到各厂商的自启动管理页；都不支持则提示手动设置 */
    private fun openOemAutostart() {
        val candidates = listOf(
            // 小米 / Redmi
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            ),
            // 华为
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            // OPPO / Realme
            ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            // vivo / iQOO
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            ),
            // 三星
            ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity"
            )
        )

        val pm = packageManager
        for (cn in candidates) {
            val intent = Intent().setComponent(cn)
            if (intent.resolveActivity(pm) != null) {
                try {
                    startActivity(intent)
                    return
                } catch (_: Exception) { }
            }
        }
        Toast.makeText(
            this,
            "未能自动跳转，请按页面下方对应品牌的步骤手动设置",
            Toast.LENGTH_LONG
        ).show()
    }
}
