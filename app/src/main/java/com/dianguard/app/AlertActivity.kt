package com.dianguard.app

import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
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
 *   屏幕显示倒计时大数字；进入最后 10 秒后停短语、按剩余秒数逐秒播放对应数字
 *   （10,9,...,1）音频，5s/3s 等短告警同样只播到对应数字 →
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

    // 根据屏幕高度动态计算的最大倒计时字号，避免大数字把下方信息挤出可视区
    private var maxCountdownSp: Float = 72f

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

                // 真实场景：震级随报数递增（首报 M4.0 → 终报 M7.0）。
                // 若新震级导致预警等级变化，实时更新全屏配色、语音短语和行动指引。
                val newMag = intent.getDoubleExtra(EewService.EXTRA_MAG, 0.0)
                val newIntensity = intent.getStringExtra(EewService.EXTRA_INTENSITY)?.toDoubleOrNull() ?: 0.0
                val newLevel = warningLevelByIntensity(newIntensity)
                val reportNum = intent.getIntExtra(EewService.EXTRA_REPORT_NUM, 0)
                if (newLevel != currentLevel) {
                    Log.i(TAG, "烈度变化: $currentLevel → $newLevel (第${reportNum}报 M${newMag} 烈度${"%.1f".format(newIntensity)})")
                    currentLevel = newLevel
                    tvLevel.text = currentLevel.label()
                    tvLevel.setTextColor(levelColor(currentLevel))
                    tvDamage.text = damageDescription(currentLevel)
                    tvDamage.setTextColor(levelColor(currentLevel))
                    applyLevelTheme(currentLevel)
                    // 若仍在短语循环阶段（倒计时 10..1 尚未触发），切换到新等级短语
                    if (!countdownTriggered) {
                        EewVoice.playPhraseLoop(currentLevel)
                    }
                    // 等级升级时补发一次震动，确保用户感知
                    vibrate()
                }
                tvMag.text = magText(newMag)
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

        // 按屏幕高度给倒计时一个大号但不过分的字号：占屏幕高度约 16%，最多 76sp，
        // 避免大数字在短屏/竖屏上把下方信息挤出或自身被裁切。
        val dm = resources.displayMetrics
        val screenHeightDp = dm.heightPixels / dm.density
        maxCountdownSp = (screenHeightDp * 0.16f).coerceIn(48f, 76f)

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
        val place = ZhConvert.toSimplified(intent.getStringExtra(EewService.EXTRA_PLACE) ?: "未知地区")
        val mag = intent.getDoubleExtra(EewService.EXTRA_MAG, 0.0)
        val intensityStr = intent.getStringExtra(EewService.EXTRA_INTENSITY) ?: "-"
        val siteIntensity = intensityStr.toDoubleOrNull() ?: 0.0
        // 等级按用户脚下预估烈度划分（非震中震级）
        currentLevel = warningLevelByIntensity(siteIntensity)

        tvPlace.text = place
        tvMag.text = magText(mag)
        tvLevel.text = currentLevel.label()
        tvLevel.setTextColor(levelColor(currentLevel))
        tvLevel.visibility =
            if (currentLevel == WarningLevel.NONE) android.view.View.GONE else android.view.View.VISIBLE
        tvDamage.text = damageDescription(currentLevel)
        tvDamage.setTextColor(levelColor(currentLevel))
        // "地震预警"标题与上方倒计时功能重复，去掉不啰嗦
        tvTitle.visibility = android.view.View.GONE
        updateDetail(intent)

        applyLevelTheme(currentLevel)

        val eta = intent.getDoubleExtra(EewService.EXTRA_ETA, 0.0)
        // 若 S 波已几乎到达（<=0.5s），直接显示“地震波已抵达”并拉警报，不播前置短语
        if (eta > 0.5) {
            EewVoice.playPhraseLoop(currentLevel)
        }
        startCountdown(eta)
    }

    private fun levelColor(level: WarningLevel): Int = ContextCompat.getColor(this, level.colorRes())

    /** 震级文案：面向公众，直接写“X.X级地震”，不用专业的“M X.X”写法 */
    private fun magText(mag: Double): String = "%.1f级地震".format(mag)

    /**
     * 四级预警破坏描述 + 避险指引。
     * 烈度区间与破坏情况对齐《中国地震烈度表》(GB/T 17742-2020) 及
     * 中国地震局预警等级标准（红≥7°灾害性 / 橙5-6°灾害性 / 黄3-4°告知性 / 蓝<3°告知性）。
     */
    private fun damageDescription(level: WarningLevel): String = when (level) {
        WarningLevel.BLUE ->
            "预估烈度 1-2 度：多数人无感或轻微有感\n保持冷静，无需惊慌"
        WarningLevel.YELLOW ->
            "预估烈度 3-4 度：室内多数人有感，悬挂物明显摆动\n就地避险，远离玻璃窗"
        WarningLevel.ORANGE ->
            "预估烈度 5-6 度：多数人站立不稳、惊逃户外，少数轻家具移动\n立即趴下，保护头部！"
        WarningLevel.RED ->
            "预估烈度 7 度及以上：物品掉落、家具倾倒，房屋可能破坏\n趴下、掩护、抓牢！！"
        WarningLevel.NONE -> ""
    }

    /**
     * 按预警等级设置整屏背景板、状态栏/导航栏颜色与文字配色，
     * 实现“蓝/黄/橙/红 四级预警 ↔ 四色背景板”严格一一对应。
     *
     * 可读性策略：
     * - 黄底极亮，文字用纯黑 + 无阴影，避免黑影把深色字糊成一圈；
     * - 红/橙/蓝底较深，文字用白色 + 黑色描边光晕，让白字从彩色背景中分离。
     */
    private fun applyLevelTheme(level: WarningLevel) {
        val bg = levelColor(level)
        val lightBg = (level == WarningLevel.YELLOW)
        themeTextColor = if (lightBg) {
            android.graphics.Color.BLACK
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

        // 文字配色随等级背景翻转（黄色面板用纯黑，其余用白色）
        tvTitle.setTextColor(themeTextColor)
        tvCountdown.setTextColor(themeTextColor)
        tvPlace.setTextColor(themeTextColor)
        tvMag.setTextColor(themeTextColor)
        tvDetail.setTextColor(themeTextColor)
        tvUnit.setTextColor(themeTextColor)
        // 底部操作区面板恒为半透明黑底（#E6000000），因此其文字恒为白色，
        // 不随等级翻转，避免黄底预警时出现“黑字压黑底”看不见的问题。
        tvHint.setTextColor(android.graphics.Color.WHITE)
        tvDamage.setTextColor(themeTextColor)
        tvLevel.setTextColor(themeTextColor)

        // 等级徽标背景与文字保持足够对比：黄底用白底深灰边徽标，深底用半透明白徽标
        tvLevel.background = makeLevelBadge(level)

        // 描边策略：黄底不要阴影；深底用黑影把白字分离出来
        if (lightBg) {
            clearTextShadow()
            // 黄底上小号说明文字容易发虚，稍微加粗一点点
            tvDetail.paint.isFakeBoldText = true
            tvDamage.paint.isFakeBoldText = true
        } else {
            applyTextOutline()
            tvDetail.paint.isFakeBoldText = false
            tvDamage.paint.isFakeBoldText = false
        }
    }

    private fun makeLevelBadge(level: WarningLevel): android.graphics.drawable.GradientDrawable {
        val drawable = android.graphics.drawable.GradientDrawable()
        drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        drawable.cornerRadius = 48f
        if (level == WarningLevel.YELLOW) {
            drawable.setColor(android.graphics.Color.WHITE)
            drawable.setStroke(2, android.graphics.Color.parseColor("#E5E5EA"))
        } else {
            drawable.setColor(android.graphics.Color.parseColor("#33FFFFFF"))
            drawable.setStroke(2, android.graphics.Color.parseColor("#CCFFFFFF"))
        }
        return drawable
    }

    private fun clearTextShadow() {
        tvCountdown.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvMag.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvPlace.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvTitle.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvLevel.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvDetail.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvDamage.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
    }

    /**
     * 给倒计时大数字与关键文字叠加黑色描边光晕。
     * 仅用于红/橙/蓝等深色背景，把白字从彩色背景中分离出来。
     */
    private fun applyTextOutline() {
        tvCountdown.setShadowLayer(10f, 0f, 0f, android.graphics.Color.BLACK)
        tvMag.setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        tvPlace.setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        tvTitle.setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
        tvLevel.setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK)
    }

    private fun updateDetail(intent: android.content.Intent) {
        val dist = intent.getDoubleExtra(EewService.EXTRA_DISTANCE, 0.0)
        val depth = intent.getDoubleExtra(EewService.EXTRA_DEPTH, 0.0)
        // 五要素规范（中国地震局）：预警震级=震级 M（由 tvMag 展示）；
        // EXTRA_INTENSITY 是用户所在地【预估烈度】，不得标注为"震级"。
        val intensity = intent.getStringExtra(EewService.EXTRA_INTENSITY) ?: "-"
        tvDetail.text = "震中距约 ${dist.toInt()} km · 预估烈度 ${intensity}度 · 深度 ${depth.toInt()} km"
    }

    private fun startCountdown(etaSeconds: Double) {
        countDownTimer?.cancel()
        // S 波已到达或几乎到达：直接呈现“地震波已抵达”，不再显示 0 秒
        if (etaSeconds <= 0.5) {
            showArrived()
            if (!alarmStarted) {
                alarmStarted = true
                EewVoice.startAlarm()
            }
            return
        }

        val totalMs = (etaSeconds * 1000).toLong().coerceAtLeast(1000)
        // 优化 #6：记录起点，供 onFinish 比对精度
        countdownStartMs = System.currentTimeMillis()
        // 倒计时进行中：恢复大字号并显示「秒」单位；归零后会被 onFinish 隐藏
        tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, maxCountdownSp)
        tvUnit.visibility = View.VISIBLE
        tvCountdown.text = ((totalMs + 999) / 1000).toString()

        countDownTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt() + 1
                tvCountdown.text = sec.toString()
                // 进入 1..10 后逐秒播对应数字（10,9,...,1），天然适配 5s/3s 等短告警
                if (sec in 1..10) {
                    if (!countdownTriggered) {
                        countdownTriggered = true
                        EewVoice.stopPhrase()
                    }
                    EewVoice.playCountDigit(sec)
                }
            }

            override fun onFinish() {
                showArrived()
                // 优化 #6：记录实际耗时
                val actual = System.currentTimeMillis() - countdownStartMs
                Log.d(TAG, "倒计时结束：预期=${totalMs}ms 实际=${actual}ms")
                EewVoice.stopCountdown()
                if (!alarmStarted) {
                    alarmStarted = true
                    EewVoice.startAlarm()
                }
            }
        }.start()
    }

    /** 倒计时归零或 S 波已到达时的统一 UI：显示「地震波已抵达」，隐藏「秒」单位 */
    private fun showArrived() {
        tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        tvCountdown.text = "地震波已抵达"
        tvCountdown.setTextColor(themeTextColor)
        tvUnit.visibility = View.GONE
        // 震中地点是核心信息，倒计时结束后继续保持显示，绝不被替换
        tvHint.text = "趴下、掩护、抓牢 · 远离玻璃窗与重物\n待震动停止后检查燃气阀门"
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
