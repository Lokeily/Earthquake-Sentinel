package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

/**
 * 测试报警页 — v1.3.0 可配置渐进式模拟改版。
 *
 * - 去掉旧版四级试听卡片，统一为"完整模拟预警"入口
 * - 半遮蔽配置面板：首报震级、末报震级（同首报/固定值）、震源深度、震中距
 * - 5 秒倒计时后触发，模拟真实地震预警从首报到终报的渐进升级全流程
 * - 底部保留"停止所有播放"按钮
 */
class TestAlarmActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var simScheduled = false

    // 配置控件
    private lateinit var etFirstMag: EditText
    private lateinit var rgFinalType: RadioGroup
    private lateinit var etFinalMag: EditText
    private lateinit var etDepth: EditText
    private lateinit var etDistance: EditText
    private lateinit var btnStartSim: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_alarm)

        findViewById<TextView>(R.id.btn_back).setOnClickListener { finish() }
        EewVoice.init(application)

        // 点击配置卡片 → 打开半遮蔽面板
        findViewById<View>(R.id.card_config).setOnClickListener { showConfigPanel() }
        findViewById<View>(R.id.btn_close_config).setOnClickListener { hideConfigPanel() }

        // 停止按钮
        findViewById<View>(R.id.btn_stop).setOnClickListener {
            EewVoice.stopAll()
            simScheduled = false
            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
        }

        // 初始化配置面板控件
        etFirstMag = findViewById(R.id.et_first_mag)
        rgFinalType = findViewById(R.id.rg_final_type)
        etFinalMag = findViewById(R.id.et_final_mag)
        etDepth = findViewById(R.id.et_depth)
        etDistance = findViewById(R.id.et_distance)
        btnStartSim = findViewById(R.id.btn_start_sim)

        // 末报类型切换
        rgFinalType.setOnCheckedChangeListener { _, id ->
            etFinalMag.visibility = if (id == R.id.rb_custom) View.VISIBLE else View.GONE
        }

        // 首报震级输入限制（3.0-8.0）
        etFirstMag.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                val v = s.toString().toDoubleOrNull()
                if (v != null && v < 3.0) s.replace(0, s.length, "3.0")
                if (v != null && v > 8.0) s.replace(0, s.length, "8.0")
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // 键盘回车直接开始模拟
        etDistance.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { startSimulation(); true } else false
        }

        btnStartSim.setOnClickListener { startSimulation() }
    }

    private fun showConfigPanel() {
        findViewById<View>(R.id.overlay_config).visibility = View.VISIBLE
    }

    private fun hideConfigPanel() {
        findViewById<View>(R.id.overlay_config).visibility = View.GONE
        simScheduled = false
        btnStartSim.text = "开始模拟（5 秒后触发）"
    }

    /**
     * 解析用户输入，校验后启动 5 秒倒计时，然后触发完整模拟。
     */
    private fun startSimulation() {
        if (simScheduled) return // 防止重复点击

        // 首报震级
        val firstMag = etFirstMag.text.toString().toDoubleOrNull()
        if (firstMag == null || firstMag < 3.0 || firstMag > 8.0) {
            Toast.makeText(this, "首报震级需在 3.0 - 8.0 之间", Toast.LENGTH_SHORT).show()
            return
        }

        // 末报震级
        val finalMag: Double
        if (rgFinalType.checkedRadioButtonId == R.id.rb_same) {
            finalMag = firstMag
        } else {
            val fm = etFinalMag.text.toString().toDoubleOrNull()
            if (fm == null || fm < 3.0 || fm > 8.0) {
                Toast.makeText(this, "末报震级需在 3.0 - 8.0 之间", Toast.LENGTH_SHORT).show()
                return
            }
            finalMag = fm
        }

        // 震源深度（默认随机 10-20km）
        val depthStr = etDepth.text.toString().trim()
        val depth = if (depthStr.isEmpty()) {
            10.0 + Math.random() * 10.0
        } else {
            val d = depthStr.toDoubleOrNull()
            if (d == null || d < 5.0 || d > 50.0) {
                Toast.makeText(this, "震源深度需在 5 - 50 km 之间", Toast.LENGTH_SHORT).show()
                return
            }
            d
        }

        // 震中距（默认随机 30-800km）
        val distStr = etDistance.text.toString().trim()
        val distKm = if (distStr.isEmpty()) {
            30.0 + Math.random() * 770.0
        } else {
            val d = distStr.toDoubleOrNull()
            if (d == null || d < 0.0 || d > 1000.0) {
                Toast.makeText(this, "震中距需在 0 - 1000 km 之间", Toast.LENGTH_SHORT).show()
                return
            }
            d
        }

        // 隐藏面板，开始倒计时
        hideConfigPanel()
        simScheduled = true
        btnStartSim.text = "即将触发…"

        val homeLat = AppConfig.homeLat
        val homeLon = AppConfig.homeLon
        val angle = Math.random() * 2 * Math.PI
        val dLat = distKm * Math.cos(angle) / 111.0
        val dLon = distKm * Math.sin(angle) / (111.0 * Math.cos(Math.toRadians(homeLat)))
        val epiLat = homeLat + dLat
        val epiLon = homeLon + dLon
        val actualDist = haversineKm(homeLat, homeLon, epiLat, epiLon)
        val eta = AppConfig.estimateSWaveEtaSeconds(actualDist)

        // 5 秒倒计时
        showConfigPanel()
        btnStartSim.text = "5 秒后触发…"
        var countdown = 5
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!simScheduled) return
                countdown--
                if (countdown > 0) {
                    btnStartSim.text = "$countdown 秒后触发…"
                    handler.postDelayed(this, 1000)
                } else {
                    btnStartSim.text = "触发中…"
                    hideConfigPanel()
                    simScheduled = false
                    btnStartSim.text = "开始模拟（5 秒后触发）"
                    launchSim(firstMag, finalMag, depth, actualDist, eta, epiLat, epiLon)
                }
            }
        }, 1000)
    }

    /**
     * 启动渐进式模拟：
     * 1. 以首报震级打开 AlertActivity
     * 2. 若末报震级 > 首报震级，在 ETA 的 60% 时刻通过广播发送后续报，
     *    触发 AlertActivity 内的震级升级过程（背景色/语音/指引全部联动）
     */
    private fun launchSim(
        firstMag: Double, finalMag: Double, depth: Double,
        distKm: Double, etaSec: Double, epiLat: Double, epiLon: Double
    ) {
        EewVoice.stopAll()

        // 计算首报烈度
        val firstIntensity = "%.1f".format(estimateSiteIntensity(firstMag, distKm, depth))

        Toast.makeText(this, "正在定位模拟震中…", Toast.LENGTH_SHORT).show()

        Thread {
            val geoName = LocationHelper.geocode(this, epiLat, epiLon)
            val place = if (!geoName.isNullOrBlank()) {
                "$geoName（模拟震中）"
            } else {
                val fallback = AppConfig.locationName.ifBlank { "参考位置" }
                "${fallback}附近${distKm.toInt()}km（模拟震中）"
            }

            val eventId = "SIM-${System.currentTimeMillis()}"

            runOnUiThread {
                val intent = Intent(this, AlertActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EewService.EXTRA_EVENT_ID, eventId)
                    putExtra(EewService.EXTRA_MAG, firstMag)
                    putExtra(EewService.EXTRA_PLACE, place)
                    putExtra(EewService.EXTRA_DISTANCE, distKm)
                    putExtra(EewService.EXTRA_ETA, etaSec)
                    putExtra(EewService.EXTRA_INTENSITY, firstIntensity)
                    putExtra(EewService.EXTRA_DEPTH, depth)
                    putExtra(EewService.EXTRA_REPORT_NUM, 1)
                }
                startActivity(intent)

                // 若末报震级 > 首报震级，在 ETA 的 60% 时刻发送后续报升级
                if (finalMag > firstMag && etaSec > 2.0) {
                    val delayMs = ((etaSec * 0.6) * 1000).toLong().coerceAtLeast(2000)
                    val finalIntensity = "%.1f".format(estimateSiteIntensity(finalMag, distKm, depth))
                    handler.postDelayed({
                        val refresh = Intent(EewService.ACTION_REFRESH).apply {
                            putExtra(EewService.EXTRA_EVENT_ID, eventId)
                            putExtra(EewService.EXTRA_MAG, finalMag)
                            putExtra(EewService.EXTRA_PLACE, place)
                            putExtra(EewService.EXTRA_DISTANCE, distKm)
                            putExtra(EewService.EXTRA_ETA, etaSec * 0.4)  // 剩余 ETA
                            putExtra(EewService.EXTRA_INTENSITY, finalIntensity)
                            putExtra(EewService.EXTRA_DEPTH, depth)
                            putExtra(EewService.EXTRA_REPORT_NUM, 2)
                        }
                        androidx.localbroadcastmanager.content.LocalBroadcastManager
                            .getInstance(this@TestAlarmActivity).sendBroadcast(refresh)
                    }, delayMs)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        EewVoice.stopAll()
        super.onDestroy()
    }
}
