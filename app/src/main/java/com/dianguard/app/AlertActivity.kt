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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    private lateinit var tvPlace: TextView
    private lateinit var tvDirection: TextView
    private lateinit var tvLevel: TextView
    private lateinit var tvGuidance: TextView
    private lateinit var tvEtaBanner: TextView
    private lateinit var btnDismiss: Button
    private lateinit var tvFooter: TextView

    // 底部"第X报：来自-距离-时间"行所需的、同一事件内不变化的字段（来源/发震时刻）
    private var currentSource: String = ""
    private var currentTimeMs: Long = 0L
    /** 演练事件前缀：后台下发的 eventId 以 DRILL- 开头时显示【演练】，避免误认作真实地震 */
    private var drillPrefix: String = ""

    // 告警页信息行（预估烈度/深度/方位）：bind 时固化方位，修正报仅更新烈度与深度
    private var currentDirection: String = ""
    private var currentDepthKm: Double = 0.0
    // 首报到达时 S 波已抵达（预警盲区）：解除横幅据此显示盲区专属提示
    private var arrivedInBlindZone = false

    // 当前等级对应的文字配色（黄色面板为深色，其余为白色），供计时结束等场景复用
    private var themeTextColor: Int = android.graphics.Color.WHITE

    private var currentEventId: String? = null
    private var currentLevel: WarningLevel = WarningLevel.NONE
    private var alarmStarted = false
    // 倒计时进入最后 10 秒后停止第一段短语、改播音频自带的 10→1（只触发一次）
    private var countdownTriggered = false
    // 拉起告警前记录的 ALARM 流原始音量，解除时还原，避免设备长期处于最大音量
    private var savedAlarmVolume = -1
    // 回放模式（测试版「预警回放」）：解除时不广播清除 EewService 的真实 activeEventId，
    // 也不弹“余震提醒”，避免干扰真实预警链路（P0 修复）
    private var isReplay = false

    // 防抖：倒计时重启和等级切换的最低间隔（毫秒），避免多源修正报频繁打断用户
    private var lastCountdownRestartMs = 0L
    private var lastLevelChangeMs = 0L
    private var lastRefreshEventMs = 0L
    private var lastRefreshMag = 0.0
    private var lastRefreshIntensity = 0.0
    private var lastRefreshEta = 0.0
    private val REFRESH_DEBOUNCE_MS = 3000L

    private var refreshJob: Job? = null

    /**
     * 处理同一事件的修正报（EewAlertManager → AppEvents.refresh → 本方法）。
     * 同事件后续报即使 ETA=0（S 波已到达）也必须更新震级/烈度/报数——
     * 旧逻辑 eta>0 才处理整份报文，导致到达后的官方终报被完全忽略。
     */
    private fun handleRefresh(ev: AppEvents.RefreshEvent) {
        if (ev.eventId != currentEventId || ev.eta < 0) return
        val now = System.currentTimeMillis()
        val newMag = ev.mag
        val newIntensity = ev.intensity.toDoubleOrNull() ?: 0.0
        val reportNum = ev.reportNum

        // 后续报防抖：相同参数连续刷新不超过 3 秒一次。
        // 但震级/烈度/ETA 有显著变化时不受限。
        val magChanged = Math.abs(newMag - lastRefreshMag) >= 0.3
        val intensityChanged = Math.abs(newIntensity - lastRefreshIntensity) >= 0.5
        val etaChanged = Math.abs(ev.eta - lastRefreshEta) > 3.0
        if (!magChanged && !intensityChanged && !etaChanged && now - lastRefreshEventMs < REFRESH_DEBOUNCE_MS) {
            Log.d(TAG, "忽略防抖刷新: 第${reportNum}报 M${"%.1f".format(newMag)} 烈度${"%.1f".format(newIntensity)}")
            return
        }
        lastRefreshEventMs = now
        lastRefreshMag = newMag
        lastRefreshIntensity = newIntensity
        lastRefreshEta = ev.eta

        // 后续报修正 ETA：若被上修到 10 秒以上，允许重新触发倒计时语音播报
        if (ev.eta > 10) countdownTriggered = false
        startCountdown(ev.eta)
        lastCountdownRestartMs = now
        tvPlace.text = ev.place.ifEmpty { tvPlace.text.toString() }

        // 真实场景：官方修正报可能上修也可能下修震级/烈度。
        // 若等级变化，实时更新全屏配色、语音短语和行动指引。
        val newLevel = warningLevelByIntensity(newIntensity)
        if (newLevel != currentLevel) {
            val oldLevel = currentLevel
            Log.i(TAG, "烈度变化: $currentLevel → $newLevel (第${reportNum}报 M${"%.1f".format(newMag)} 烈度${"%.1f".format(newIntensity)})")
            currentLevel = newLevel
            tvLevel.text = currentLevel.label()
            tvLevel.visibility =
                if (currentLevel == WarningLevel.NONE) View.GONE else View.VISIBLE
            tvLevel.setTextColor(levelColor(currentLevel))
            tvGuidance.text = guidanceText(currentLevel)
            tvGuidance.setTextColor(levelColor(currentLevel))
            applyLevelTheme(currentLevel)
            // 等级切换有最低间隔（3 秒），防止多源交叉修正导致反复切换语音/震动
            if (now - lastLevelChangeMs >= REFRESH_DEBOUNCE_MS) {
                lastLevelChangeMs = now
                // 若仍在短语循环阶段（倒计时 10..1 尚未触发），切换到新等级短语
                if (!countdownTriggered) {
                    EewVoice.playPhraseLoop(currentLevel)
                }
                // 仅等级升级时补发震动；下修不应制造新的紧急刺激。
                if (newLevel.ordinal < oldLevel.ordinal) vibrate()
            }
        }
        // 地点与震级合并为一行展示（元素去重），修正报直接更新
        tvPlace.text = "$drillPrefix${ev.place} · ${magText(newMag)}"
        // 底栏随修正报更新：报数/来源/时间/预估烈度/震源深度/距您距离
        currentDepthKm = ev.depth
        tvFooter.text = buildFooter(reportNum, ev.intensity, ev.depth, ev.distance)
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

        // 真实预警时无论 App 是否在前台，都以悬浮窗形式强制弹出（需 SYSTEM_ALERT_WINDOW 权限）。
        // 必须先确认悬浮窗权限已授予：setType 本身不抛异常，若未授权，窗口添加阶段
        // （onResume 后）才抛 BadTokenException 且无法捕获 → 页面拉起即崩溃。
        // debug 包（applicationId 带 .debug 后缀）与正式包权限彼此独立，首次安装必未授权，
        // 未授权时退化为普通全屏 Activity 展示，保证回放/模拟/真实预警至少可用。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && android.provider.Settings.canDrawOverlays(this)) {
            window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }

        setContentView(R.layout.activity_alert)

        tvPlace = findViewById(R.id.tv_place)
        tvDirection = findViewById(R.id.tv_direction)
        tvLevel = findViewById(R.id.tv_level)
        tvGuidance = findViewById(R.id.tv_guidance)
        tvEtaBanner = findViewById(R.id.tv_eta_banner)
        btnDismiss = findViewById(R.id.btn_dismiss)
        tvFooter = findViewById(R.id.tv_footer)

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

        // 订阅修正报事件流（替代 LocalBroadcastManager），生命周期自动取消
        refreshJob = lifecycleScope.launch {
            AppEvents.refresh.collect { handleRefresh(it) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        // 安全隔离（P0 修复）：若当前正在展示真实预警（非回放），而新来的 Intent 是回放，
        // 则忽略该回放，避免回放覆盖真实预警弹窗，导致用户错过真实地震。
        val newIsReplay = intent.getBooleanExtra(EewService.EXTRA_REPLAY, false)
        if (!isReplay && newIsReplay) {
            Log.i(TAG, "回放意图被忽略：当前正在展示真实预警，回放不能覆盖")
            return
        }
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
        isReplay = intent.getBooleanExtra(EewService.EXTRA_REPLAY, false)
        val place = ZhConvert.toSimplified(intent.getStringExtra(EewService.EXTRA_PLACE) ?: "未知地区")
        drillPrefix = if (currentEventId?.startsWith("DRILL-") == true || place.contains("演练")) "【演练】" else ""
        val mag = intent.getDoubleExtra(EewService.EXTRA_MAG, 0.0)
        val intensityStr = intent.getStringExtra(EewService.EXTRA_INTENSITY) ?: "-"
        val siteIntensity = intensityStr.toDoubleOrNull() ?: 0.0
        // 等级按用户脚下预估烈度划分（非震中震级）
        currentLevel = warningLevelByIntensity(siteIntensity)
        // 同一事件内来源与发震时刻不随报数变化，先固化下来供底部行复用
        currentSource = intent.getStringExtra(EewService.EXTRA_SOURCE) ?: ""
        currentTimeMs = intent.getLongExtra(EewService.EXTRA_TIME, 0L)

        tvPlace.text = "$drillPrefix$place · ${magText(mag)}"
        tvLevel.text = currentLevel.label()
        tvLevel.setTextColor(levelColor(currentLevel))
        tvLevel.visibility =
            if (currentLevel == WarningLevel.NONE) android.view.View.GONE else android.view.View.VISIBLE
        tvGuidance.text = guidanceText(currentLevel)
        tvGuidance.setTextColor(levelColor(currentLevel))
        val depthKm = intent.getDoubleExtra(EewService.EXTRA_DEPTH, 0.0)
        val distKm = intent.getDoubleExtra(EewService.EXTRA_DISTANCE, 0.0)
        val epiLat = intent.getDoubleExtra(EewService.EXTRA_EPI_LAT, 999.0)
        val epiLon = intent.getDoubleExtra(EewService.EXTRA_EPI_LON, 999.0)
        // ICL 式方位提示：震中位于用户的哪个方向（有定位且报文带震中坐标时才有）
        currentDirection = if (AppConfig.hasLocation && epiLat != 999.0 && epiLon != 999.0) {
            GeoUtils.bearingDirection(AppConfig.homeLat, AppConfig.homeLon, epiLat, epiLon)
        } else ""
        currentDepthKm = depthKm
        tvDirection.text = if (currentDirection.isNotBlank()) "震中位于您的${currentDirection}方向" else ""
        val eta = intent.getDoubleExtra(EewService.EXTRA_ETA, 0.0)
        // 首报到达时 S 波已抵达 → 用户处于预警盲区（ICL 式盲区提示）
        arrivedInBlindZone = eta <= 0.5
        tvFooter.text = buildFooter(
            intent.getIntExtra(EewService.EXTRA_REPORT_NUM, 0),
            intensityStr, depthKm, distKm
        )

        applyLevelTheme(currentLevel)

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
     * 一句话避险判定（简短一行，ICL 式）。
     * 按本地预估烈度（GB/T 17742-2020 / 中国地震局预警等级标准）给出对应结论，
     * 不展开具体避险教学——紧急避险由警报声、语音播报与应急演练承担。
     */
    private fun guidanceText(level: WarningLevel): String = when (level) {
        WarningLevel.RED -> "强破坏性地震！请立即就地避险"
        WarningLevel.ORANGE -> "破坏性地震！请立即就近避险"
        WarningLevel.YELLOW -> "强有感地震，请注意避险"
        WarningLevel.BLUE -> "轻微有感地震，请保持冷静"
        WarningLevel.NONE -> ""
    }

    /**
     * 底部信息（用户要求：烈度/深度/距离显示在时间后面）：
     * 第X报 · 来自哪里 · 发震时刻 · 预估烈度X度 · 震源深度X公里 · 距您X公里。
     * 来源与发震时刻取自同事件固化的 currentSource / currentTimeMs（刷新报不改变），
     * 报数/烈度/深度/距离随修正报更新。
     */
    private fun buildFooter(reportNum: Int, intensityStr: String, depthKm: Double, distKm: Double): String {
        val reportLabel = if (reportNum <= 0) "首报" else "第${reportNum}报"
        val srcLabel = if (currentSource.isBlank()) "—" else currentSource
        val time = formatOriginTime(currentTimeMs)
        val sb = StringBuilder("$reportLabel · 来自$srcLabel · $time")
        if (intensityStr.isNotBlank() && intensityStr != "-") sb.append(" · 预估烈度${intensityStr}度")
        if (depthKm > 0) sb.append(" · 震源深度${depthKm.toInt()}公里")
        if (distKm > 0) sb.append(" · 距您${distKm.toInt()}公里")
        return sb.toString()
    }

    /** epoch 毫秒 → "HH:mm:ss"（北京时间）；0/负表示未知，返回占位符 */
    private fun formatOriginTime(ms: Long): String {
        if (ms <= 0) return "—"
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.CHINA)
        sdf.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        return sdf.format(Date(ms))
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

        // 状态栏 / 导航栏同步为同色，沉浸一致（minSdk 21 = LOLLIPOP，无需 SDK 守卫）
        window.statusBarColor = bg
        window.navigationBarColor = bg
        // 浅色背景下让状态栏图标为深色（API 23+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = if (lightBg) {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }

        // 文字配色随等级背景翻转（黄色面板用纯黑，其余用白色）
        tvPlace.setTextColor(themeTextColor)
        tvDirection.setTextColor(themeTextColor)
        tvGuidance.setTextColor(themeTextColor)

        // 描边策略：黄底不要阴影；深底用黑影把白字分离出来
        if (lightBg) {
            clearTextShadow()
            // 黄底上小号说明文字容易发虚，稍微加粗一点点
            tvGuidance.paint.isFakeBoldText = true
        } else {
            applyTextOutline()
            tvGuidance.paint.isFakeBoldText = false
        }
    }

    private fun clearTextShadow() {
        tvPlace.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvDirection.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
        tvGuidance.setShadowLayer(0f, 0f, 0f, android.graphics.Color.TRANSPARENT)
    }

    /**
     * 给关键文字叠加黑色描边光晕。
     * 仅用于红/橙/蓝等深色背景，把白字从彩色背景中分离出来。
     * （等级横幅与倒计时横幅都在深色半透明条上，对比已足够，无需描边。）
     */
    private fun applyTextOutline() {
        tvPlace.setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        tvDirection.setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
        tvGuidance.setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
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
        // 倒计时提示横幅（主视觉）：初始 X 秒后到达
        tvEtaBanner.text = "地震波将于 ${((totalMs + 999) / 1000)} 秒后到达"

        countDownTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000).toInt() + 1
                // 提示横幅随秒数实时更新（ICL 式，主视觉）
                tvEtaBanner.text = "地震波将于 $sec 秒后到达"
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
                EewVoice.stopCountdown()
                if (!alarmStarted) {
                    alarmStarted = true
                    EewVoice.startAlarm()
                }
            }
        }.start()
    }

    /** 倒计时归零或 S 波已到达时的统一 UI（ICL 式）：横幅切为到达/盲区状态 */
    private fun showArrived() {
        tvEtaBanner.text = if (arrivedInBlindZone) "您位于预警盲区 · 地震波已到达，请立即避险"
                           else "地震波已抵达 · 请立即避险"
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
        // 回放模式：不广播清除 EewService 的 activeEventId，避免干扰真实预警链路
        if (!isReplay) {
            try {
                AppEvents.postAlertDismissed()
            } catch (_: Exception) { }
            // 余震提醒：强震后大概率有余震
            if (currentLevel == WarningLevel.ORANGE || currentLevel == WarningLevel.RED) {
                Toast.makeText(this, "余震可能发生，请保持警惕！", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }

    /**
     * 页面进入后台（如用户接听电话 / 被系统覆盖）时立即停止震动，
     * 避免持续震动穿透到其他界面。返回前台由 bindAlertData / handleRefresh 重新拉起震动。
     * （v1.4.3 补充：原实现仅 onDestroy 释放，onStop 场景会持续震动。）
     */
    override fun onStop() {
        super.onStop()
        try { vibrator?.cancel() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        refreshJob?.cancel()
        EewVoice.release()
        restoreAlarmVolume()
        try { vibrator?.cancel() } catch (_: Exception) { }
    }
}
