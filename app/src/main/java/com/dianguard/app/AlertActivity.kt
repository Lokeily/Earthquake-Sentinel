package com.dianguard.app

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * 全屏地震预警页：锁屏之上弹出，红屏 + 大号倒计时 + 最大音量警报声 +
 * 分级预警语音（第一段短语循环 → 倒计时结束 → 第三段警报循环）。
 *
 * v1.0.8 流程（按用户真实大陆预警音频，不使用 TTS 读数字）：
 *   第一段（短语，如“有感地震，请勿惊慌”）在倒计时前段循环播放 →
 *   屏幕显示 20→1 倒计时；进入最后 10 秒时停短语、播放音频里自带的 10→1 播报 →
 *   倒计时归零：第三段警报声循环播放，直到用户点“我已安全”。
 *
 * 完整模拟预警与真实预警共用本 Activity，逻辑完全一致。
 */
class AlertActivity : AppCompatActivity() {

    private val TAG = "AlertActivity"

    @Volatile private var countDownTimer: CountDownTimer? = null
    @Volatile private var vibrator: Vibrator? = null

    // 优化 #6：记录倒计时起点，结束时比对“预期时长 vs 实际流逝”，用于精度分析
    private var countdownStartMs = 0L

    private lateinit var tvCountdown: TextView
    private lateinit var tvPlace: TextView
    private lateinit var tvMag: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvLevel: TextView
    private lateinit var tvDamage: TextView
    private lateinit var btnDismiss: Button
    private lateinit var tvTitle: TextView
    private lateinit var tvUnit: TextView
    private lateinit var tvHint: TextView

    // 当前等级对应的文字配色（黄色面板为深色，其余为白色），供计时结束等场景复用
    private var themeTextColor: Int = android.graphics.Color.WHITE

    private var currentEventId: String? = null
    private var currentLevel: WarningLevel = WarningLevel.NONE
    private var alarmStarted = false
    // 倒计时进入最后 10 秒后停止第一段短语、改播音频自带的 10→1（只触发一次）
    private var countdownTriggered = false
    // 拉起告警前记录的 ALARM 流原始音量，解除时还原，避免设备长期处于最大音量
    private var savedAlarmVolume = -1

    private val refreshReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != EewService.ACTION_REFRESH) return
            val eta = intent.getDoubleExtra(EewService.EXTRA_ETA, -1.0)
            val eventId = intent.getStringExtra(EewService.EXTRA_EVENT_ID)
            if (eventId == currentEventId && eta > 0) {
                // 后续报修正 ETA：若被上修到 10 秒以上，允许重新触发倒计时语音播报
                if (eta > 10) countdownTriggered = false
                startCountdown(eta)
                tvPlace.text = intent.getStringExtra(EewService.EXTRA_PLACE) ?: tvPlace.text
                tvMag.text = "M${intent.getDoubleExtra(EewService.EXTRA_MAG, 0.0)}"
                updateDetail(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        EewVoice.init(application)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 真实预警时无论 App 是否在前台，都以悬浮窗形式强制弹出（需 SYSTEM_ALERT_WINDOW 权限）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } catch (_: Exception) { }
        }

        setContentView(R.layout.activity_alert)

        tvCountdown = findViewById(R.id.tv_countdown)
        tvPlace = findViewById(R.id.tv_place)
        tvMag = findViewById(R.id.tv_mag)
        tvDetail = findViewById(R.id.tv_detail)
        tvLevel = findViewById(R.id.tv_level)
        tvDamage = findViewById(R.id.tv_damage)
        btnDismiss = findViewById(R.id.btn_dismiss)
        tvTitle = findViewById(R.id.tv_title)
        tvUnit = findViewById(R.id.tv_unit)
        tvHint = findViewById(R.id.tv_hint)

        bindAlertData(intent)

        btnDismiss.setOnClickListener { dismissAlert() }

        // 优化 #7：告警期间拦截系统返回键，只能通过“我已安全”解除，
        // 避免用户误触返回键关闭告警却未清空活动事件（导致后续报无法再次全屏）。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.i(TAG, "返回键被拦截：告警中不允许关闭，请点“我已安全”")
            }
        })

        boostAlarmVolume()
        vibrate()

        LocalBroadcastManager.getInstance(this)
            .registerReceiver(refreshReceiver, android.content.IntentFilter(EewService.ACTION_REFRESH))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        setIntent(intent)
        val newEventId = intent.getStringExtra(EewService.EXTRA_EVENT_ID)
        // 同事件后续报交给 refreshReceiver 处理，不在此重置
        if (newEventId == currentEventId) return
        // 不同地震（典型为余震）：完整重置告警状态
        bindAlertData(intent)
        vibrate()
    }

    /**
     * 绑定一次地震告警所需的全部 UI 与音频状态。
     * onCreate 与 onNewIntent（不同事件）共用，保证余震来临时界面被完整刷新。
     */
    private fun bindAlertData(intent: Intent) {
        countDownTimer?.cancel()
        EewVoice.stopAll()
        alarmStarted = false
        countdownTriggered = false

        currentEventId = intent.getStringExtra(EewService.EXTRA_EVENT_ID)
        val place = intent.getStringExtra(EewService.EXTRA_PLACE) ?: "未知地区"
        val mag = intent.getDoubleExtra(EewService.EXTRA_MAG, 0.0)
        val intensityStr = intent.getStringExtra(EewService.EXTRA_INTENSITY) ?: "-"
        val siteIntensity = intensityStr.toDoubleOrNull() ?: 0.0
        currentLevel = warningLevelByIntensity(siteIntensity)

        tvPlace.text = place
        tvMag.text = "M$mag"
        tvLevel.text = currentLevel.label()
        tvLevel.setTextColor(levelColor(currentLevel))
        tvLevel.visibility =
            if (currentLevel == WarningLevel.NONE) android.view.View.GONE else android.view.View.VISIBLE
        tvDamage.text = damageDescription(currentLevel, intensityStr)
        tvDamage.setTextColor(levelColor(currentLevel))
        updateDetail(intent)

        applyLevelTheme(currentLevel)

        val eta = intent.getDoubleExtra(EewService.EXTRA_ETA, 0.0)
        EewVoice.playPhraseLoop(currentLevel)
        startCountdown(eta)
    }

    private fun levelColor(level: WarningLevel): Int = when (level) {
        WarningLevel.RED -> ContextCompat.getColor(this, R.color.level_red)
        WarningLevel.ORANGE -> ContextCompat.getColor(this, R.color.level_orange)
        WarningLevel.YELLOW -> ContextCompat.getColor(this, R.color.level_yellow)
        WarningLevel.BLUE -> ContextCompat.getColor(this, R.color.level_blue)
        WarningLevel.NONE -> ContextCompat.getColor(this, R.color.alert_red)
    }

    /** 四级预警的破坏程度描述（面向公众的警示语） */
    private fun damageDescription(level: WarningLevel, intensity: String): String = when (level) {
        WarningLevel.BLUE -> "预估烈度${intensity}° — 室内多数人有感，悬挂物轻微摆动，一般不会造成破坏"
        WarningLevel.YELLOW -> "预估烈度${intensity}° — 多数人惊慌失措，家具移动，部分房屋可能出现轻微破坏"
        WarningLevel.ORANGE -> "预估烈度${intensity}° — 站立困难，房屋可能发生破坏，请立即采取防护措施！"
        WarningLevel.RED -> "预估烈度${intensity}° — 行动困难，房屋可能严重破坏或倒塌，请立即紧急避险！！"
        WarningLevel.NONE -> ""
    }

    /**
     * 按预警等级设置整屏背景板、状态栏/导航栏颜色与文字配色，
     * 实现“蓝/黄/橙/红 四级预警 ↔ 四色背景板”严格一一对应。
     * 黄色面板较亮，文字改用深色以保证可读性（符合国标预警配色惯例）。
     */
    private fun applyLevelTheme(level: WarningLevel) {
        val bg = levelColor(level)
        val lightBg = (level == WarningLevel.YELLOW)
        themeTextColor = if (lightBg) {
            ContextCompat.getColor(this, R.color.text_primary)
        } else {
            ContextCompat.getColor(this, android.R.color.white)
        }

        // 背景板（根布局）跟随等级
        findViewById<View>(R.id.root_alert).setBackgroundColor(bg)

        // 状态栏 / 导航栏同步为同色，沉浸一致
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = bg
            window.navigationBarColor = bg
        }
        // 浅色背景下让状态栏图标为深色（API 23+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = if (lightBg) {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }

        // 文字配色随等级背景翻转
        tvTitle.setTextColor(themeTextColor)
        tvCountdown.setTextColor(themeTextColor)
        tvPlace.setTextColor(themeTextColor)
        tvMag.setTextColor(themeTextColor)
        tvDetail.setTextColor(themeTextColor)
        tvUnit.setTextColor(themeTextColor)
        tvHint.setTextColor(themeTextColor)
        tvLevel.setTextColor(themeTextColor)
    }

    private fun updateDetail(intent: android.content.Intent) {
        val dist = intent.getDoubleExtra(EewService.EXTRA_DISTANCE, 0.0)
        val intensity = intent.getStringExtra(EewService.EXTRA_INTENSITY) ?: "-"
        val depth = intent.getDoubleExtra(EewService.EXTRA_DEPTH, 0.0)
        tvDetail.text = "震中距约 ${dist.toInt()} km · 预估烈度 ${intensity} · 深度 ${depth.toInt()} km"
    }

    private fun startCountdown(etaSeconds: Double) {
        countDownTimer?.cancel()
        val totalMs = (etaSeconds * 1000).toLong().coerceAtLeast(1000)
        // 优化 #6：记录起点，供 onFinish 比对精度
        countdownStartMs = System.currentTimeMillis()
        tvCountdown.text = ((totalMs + 999) / 1000).toString()

        countDownTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt() + 1
                tvCountdown.text = sec.toString()
                // 精确到 10 秒时启动倒计时播报（10,9,8...1）
                if (sec in 1..10 && !countdownTriggered) {
                    countdownTriggered = true
                    EewVoice.stopPhrase()
                    EewVoice.playCountdown(currentLevel)
                }
            }

            override fun onFinish() {
                tvCountdown.text = "0"
                tvCountdown.setTextColor(themeTextColor)
                tvPlace.text = "警报！请立即避险"
                tvHint.text = "趴下、掩护、抓牢 · 远离玻璃窗与重物\n待震动停止后检查燃气阀门"
                // 优化 #6：记录实际耗时
                val actual = System.currentTimeMillis() - countdownStartMs
                Log.d(TAG, "倒计时结束：预期=${totalMs}ms 实际=${actual}ms")
                EewVoice.stopCountdown()
                if (!alarmStarted) {
                    alarmStarted = true
                    EewVoice.startAlarm(currentLevel)
                }
            }
        }.start()
    }

    private fun boostAlarmVolume() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (_: Exception) { }
    }

    /** 还原 ALARM 流音量（解除告警或页面被销毁时调用） */
    private fun restoreAlarmVolume() {
        if (savedAlarmVolume < 0) return
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
        } catch (_: Exception) { }
        savedAlarmVolume = -1
    }

    private fun vibrate() {
        try {
            vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 400, 300, 400, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (_: Exception) { }
    }

    private fun dismissAlert() {
        EewVoice.stopAll()
        restoreAlarmVolume()
        countDownTimer?.cancel()
        try { vibrator?.cancel() } catch (_: Exception) { }
        try {
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(android.content.Intent(EewService.ACTION_ALERT_DISMISSED))
        } catch (_: Exception) { }
        // 余震提醒：强震后大概率有余震
        if (currentLevel == WarningLevel.ORANGE || currentLevel == WarningLevel.RED) {
            Toast.makeText(this, "余震可能发生，请保持警惕！", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        EewVoice.release()
        restoreAlarmVolume()
        try { vibrator?.cancel() } catch (_: Exception) { }
        LocalBroadcastManager.getInstance(this).unregisterReceiver(refreshReceiver)
    }
}
