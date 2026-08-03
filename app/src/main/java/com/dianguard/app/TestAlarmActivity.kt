package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 测试报警页 — v1.2.0 科普改版。
 *
 * - 试听：四级语音试听（原样保留）
 * - 全屏模拟：每级一个按钮，以手机定位周围真实城市为震中，ETA 20-30s 随机，
 *   拉起真实 AlertActivity 全方位模拟地震来临体验。
 * - 随机完整模拟：随机等级 + 随机方向 + 20-30s ETA。
 */
class TestAlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_alarm)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
        EewVoice.init(application)

        // 试听
        findViewById<View>(R.id.btn_blue_demo).setOnClickListener { demo(WarningLevel.BLUE) }
        findViewById<View>(R.id.btn_yellow_demo).setOnClickListener { demo(WarningLevel.YELLOW) }
        findViewById<View>(R.id.btn_orange_demo).setOnClickListener { demo(WarningLevel.ORANGE) }
        findViewById<View>(R.id.btn_red_demo).setOnClickListener { demo(WarningLevel.RED) }

        // 全屏模拟
        findViewById<View>(R.id.btn_blue_sim).setOnClickListener { launchSim(WarningLevel.BLUE) }
        findViewById<View>(R.id.btn_yellow_sim).setOnClickListener { launchSim(WarningLevel.YELLOW) }
        findViewById<View>(R.id.btn_orange_sim).setOnClickListener { launchSim(WarningLevel.ORANGE) }
        findViewById<View>(R.id.btn_red_sim).setOnClickListener { launchSim(WarningLevel.RED) }

        // 随机完整模拟
        findViewById<View>(R.id.btn_full).setOnClickListener {
            val rnd = WarningLevel.entries.filter { it != WarningLevel.NONE }.random()
            launchSim(rnd)
        }

        findViewById<View>(R.id.btn_stop).setOnClickListener {
            EewVoice.stopAll()
            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        }
    }

    /** 每级震级/烈度映射 */
    private fun levelParams(level: WarningLevel): Pair<Double, String> = when (level) {
        WarningLevel.BLUE -> 3.5 to "4"
        WarningLevel.YELLOW -> 4.5 to "6"
        WarningLevel.ORANGE -> 5.5 to "8"
        WarningLevel.RED -> 6.5 to "10"
        WarningLevel.NONE -> 2.0 to "2"
    }

    /** 启动全屏模拟：随机方位 + 20-30s ETA + 逆地理定位附近城市 */
    private fun launchSim(level: WarningLevel) {
        EewVoice.stopAll()
        val homeLat = AppConfig.homeLat
        val homeLon = AppConfig.homeLon
        val (mag, intensity) = levelParams(level)

        // 随机方位和距离，产生 20-30s ETA
        val targetEta = 20.0 + Math.random() * 10.0  // 20-30s
        val distKm = targetEta * 3.5  // S波 3.5 km/s
        val angle = Math.random() * 2 * Math.PI
        val dLat = distKm * Math.cos(angle) / 111.0
        val dLon = distKm * Math.sin(angle) / (111.0 * Math.cos(Math.toRadians(homeLat)))
        val epiLat = homeLat + dLat
        val epiLon = homeLon + dLon
        val actualDist = haversineKm(homeLat, homeLon, epiLat, epiLon)
        val eta = AppConfig.estimateSWaveEtaSeconds(actualDist)

        // 震中深度 10-20km 随机
        val depth = 10.0 + Math.random() * 10.0

        // 逆地理定位震中城市名
        Toast.makeText(this, "正在定位模拟震中…", Toast.LENGTH_SHORT).show()
        Thread {
            val geoName = LocationHelper.geocode(this@TestAlarmActivity, epiLat, epiLon)
            val place = if (!geoName.isNullOrBlank()) {
                "$geoName（模拟震中）"
            } else {
                val fallback = AppConfig.locationName.ifBlank { "参考位置" }
                "${fallback}附近${actualDist.toInt()}km（模拟震中）"
            }
            runOnUiThread {
                val intent = Intent(this@TestAlarmActivity, AlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EewService.EXTRA_EVENT_ID, "SIM-${System.currentTimeMillis()}")
                    putExtra(EewService.EXTRA_MAG, mag)
                    putExtra(EewService.EXTRA_PLACE, place)
                    putExtra(EewService.EXTRA_DISTANCE, actualDist)
                    putExtra(EewService.EXTRA_ETA, eta)
                    putExtra(EewService.EXTRA_INTENSITY, intensity)
                    putExtra(EewService.EXTRA_DEPTH, depth)
                    putExtra(EewService.EXTRA_REPORT_NUM, 1)
                }
                startActivity(intent)
            }
        }.start()
    }

    private fun demo(level: WarningLevel) {
        EewVoice.demoLevel(level)
        Toast.makeText(this, level.label(), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        EewVoice.stopAll()
        super.onDestroy()
    }
}
