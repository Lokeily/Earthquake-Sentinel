package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 测试报警第二页：把四级预警分别列出来，点击任意一级即可试听该等级的
 * 预警语音 + 倒计时结束后的警报提示音（音频取自用户提供的真实大陆预警录音）。
 * 另有“完整模拟预警”可跑一次全屏倒计时 + 警报。
 */
class TestAlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_alarm)

        // 顶部栏返回按钮（主题为 NoActionBar，无系统返回键）
        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }

        EewVoice.init(application)

        findViewById<View>(R.id.card_blue).setOnClickListener { demo(WarningLevel.BLUE) }
        findViewById<View>(R.id.card_yellow).setOnClickListener { demo(WarningLevel.YELLOW) }
        findViewById<View>(R.id.card_orange).setOnClickListener { demo(WarningLevel.ORANGE) }
        findViewById<View>(R.id.card_red).setOnClickListener { demo(WarningLevel.RED) }

        findViewById<View>(R.id.btn_stop).setOnClickListener {
            EewVoice.stopAll()
            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btn_full).setOnClickListener {
            EewVoice.stopAll()
            val homeLat = AppConfig.homeLat
            val homeLon = AppConfig.homeLon
            val dKm = 50.0
            val epiLat = homeLat + dKm / 111.0
            val epiLon = homeLon
            val dist = haversineKm(homeLat, homeLon, epiLat, epiLon)
            val eta = AppConfig.estimateSWaveEtaSeconds(dist)

            // 逆地理编码：把模拟震中坐标转成真实省市区县名
            Toast.makeText(this, "正在定位模拟震中…", Toast.LENGTH_SHORT).show()
            Thread {
                val geoName = LocationHelper.geocode(this@TestAlarmActivity, epiLat, epiLon)
                val simPlace = if (!geoName.isNullOrBlank()) {
                    "$geoName（模拟震中）"
                } else {
                    val fallback = AppConfig.locationName.ifBlank { "参考位置" }
                    "${fallback}正北约${dist.toInt()}km（模拟震中）"
                }
                val simMag = 6.0
                val simIntensity = "8"
                val intent = Intent(this@TestAlarmActivity, AlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EewService.EXTRA_EVENT_ID, "SIM-${System.currentTimeMillis()}")
                    putExtra(EewService.EXTRA_MAG, simMag)
                    putExtra(EewService.EXTRA_PLACE, simPlace)
                    putExtra(EewService.EXTRA_DISTANCE, dist)
                    putExtra(EewService.EXTRA_ETA, eta)
                    putExtra(EewService.EXTRA_INTENSITY, simIntensity)
                    putExtra(EewService.EXTRA_DEPTH, 10.0)
                    putExtra(EewService.EXTRA_REPORT_NUM, 1)
                }
                startActivity(intent)
            }.start()
        }
    }

    private fun demo(level: WarningLevel) {
        EewVoice.demoLevel(level)
        Toast.makeText(this, level.label(), Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        EewVoice.stopAll()
        super.onDestroy()
    }
}
