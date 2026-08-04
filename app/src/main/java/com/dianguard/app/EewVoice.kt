package com.dianguard.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * 地震预警语音播报器（v1.0.8 重构，全部采用用户提供的真实大陆预警录音，不使用 TTS 读数字）。
 *
 * 三段式结构（对齐用户提供的分级录音，直接从音频里取，不额外合成）：
 *   - 第一段（cnee_w_*，短语）：如「有感地震，请勿惊慌」。倒计时前段循环播放。
 *   - 第二段（count_10..count_1，逐字倒计时）：将用户「10秒倒计时」拆成
 *     10/9/.../1 的独立音频，AlertActivity 按剩余秒数每秒播一个，
 *     天然适配 5s/3s 等短告警；不使用 TTS，保证音色统一。
 *   - 第三段（alarm，警报）：来自用户提供的 单独警报声.mp3，倒计时归零后
 *     循环播放，直到用户点「我已安全」。
 *
 * 测试报警页四级试听：直接整段播放用户原始录音 cnee_full_*（短语+10→1+警报，原样不动）。
 *
 * 全程走 ALARM 音频流，锁屏/静音下最大音量。
 */
object EewVoice {

    private const val TAG = "EewVoice"

    // 仅持有 ApplicationContext（见 init 中 context.applicationContext），不会泄漏 Activity；
    // 此处抑制 Lint 对 object 单例静态字段的通用告警。
    @SuppressLint("StaticFieldLeak")
    private lateinit var appCtx: Context

    // 第一段：短语循环
    private var phrasePlayer: MediaPlayer? = null
    // 第二段：倒计时 10→1（播放一次）
    private var countPlayer: MediaPlayer? = null
    // 第三段：警报循环
    private var alarmPlayer: MediaPlayer? = null

    /**
     * 优化 #2：原实现用 16 个独立字段展平存储“等级 × 音效类型”的映射，
     * 新增语言/音效需成倍新增字段。改为单个 Map，按 `等级 × 类型` 自动拼接资源名
     * （cnee_w_/cnee_full_ + 等级小写，仅第一段短语与整段试听仍按等级；
     *  第二段逐字倒计时与第三段警报改为共享资源 count_<n> / alarm），扩展性更好。
     */
    private enum class AudioType(val prefix: String) {
        PHRASE("cnee_w"), FULL("cnee_full")
    }

    private val resMap = mutableMapOf<Pair<WarningLevel, AudioType>, Int>()

    fun init(context: Context) {
        appCtx = context.applicationContext
        val r = appCtx.resources
        val pkg = appCtx.packageName
        fun id(n: String) = r.getIdentifier(n, "raw", pkg)
        // NONE 没有对应资源，统一回退到 BLUE，故只需为 4 个真实等级建索引
        for (level in WarningLevel.entries) {
            if (level == WarningLevel.NONE) continue
            for (type in AudioType.entries) {
                val name = "${type.prefix}_${level.name.lowercase()}"
                resMap[level to type] = id(name)
            }
        }
    }

    /** 取指定等级与类型对应的资源 id；NONE 回退到 BLUE，缺失返回 0（createPlayer 会安全跳过） */
    private fun resFor(level: WarningLevel, type: AudioType): Int {
        val lvl = if (level == WarningLevel.NONE) WarningLevel.BLUE else level
        return resMap[lvl to type] ?: 0
    }

    private fun phraseRes(level: WarningLevel): Int = resFor(level, AudioType.PHRASE)
    private fun fullRes(level: WarningLevel): Int = resFor(level, AudioType.FULL)

    /**
     * 第二段倒计时改为逐字音频：按剩余秒数取对应数字资源 count_<n>（10,9,...,1）。
     * 共享、不分级；由 AlertActivity 按真实 ETA 每秒播一个，天然适配 5s/3s 等短告警。
     */
    private fun countDigitRes(n: Int): Int {
        val name = "count_$n"
        return appCtx.resources.getIdentifier(name, "raw", appCtx.packageName)
    }

    /** 第三段警报：共享 alarm 资源（来自用户提供的 单独警报声.mp3），不分级。 */
    private fun alarmResShared(): Int {
        return appCtx.resources.getIdentifier("alarm", "raw", appCtx.packageName)
    }

    private fun alarmAttr() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun speechAttr() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /**
     * 修复 #8：AudioAttributes 必须在 prepare() 之前设置。
     *
     * 原实现用 `MediaPlayer.create()` 创建播放器，该方法内部已经调用过 `prepare()`，
     * 之后才 `setAudioAttributes()` 实际上已无效——导致部分设备警报不走 STREAM_ALARM，
     * 在勿扰/静音模式下地震预警完全无声。
     *
     * 这里改为手动流程：new → setAudioAttributes（先）→ setDataSource → prepare（后）→ start，
     * 并挂上 OnErrorListener 记录错误（修复 #9：原实现对 MediaPlayer 异常完全静默）。
     */
    @Synchronized
    private fun createPlayer(resId: Int, attr: AudioAttributes, loop: Boolean): MediaPlayer? {
        if (resId == 0) return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(attr) // ← 必须在 prepare 之前
                setDataSource(appCtx, Uri.parse("android.resource://${appCtx.packageName}/$resId"))
                prepare()
                isLooping = loop
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer 播放错误 what=$what extra=$extra res=$resId")
                    true
                }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 MediaPlayer 失败 res=$resId", e)
            null
        }
    }

    /** 第一段（短语）循环播放，直到调用 stopPhrase / playCountDigit / startAlarm / stopAll。 */
    @Synchronized
    fun playPhraseLoop(level: WarningLevel) {
        stopCountdown()
        try { alarmPlayer?.release() } catch (_: Exception) { }
        alarmPlayer = null
        try { phrasePlayer?.release() } catch (_: Exception) { }
        phrasePlayer = null
        phrasePlayer = createPlayer(phraseRes(level), speechAttr(), loop = true)
    }

    /** 停止第一段短语（不启动警报），用于切换到倒计时数字播报。 */
    @Synchronized
    fun stopPhrase() {
        try { phrasePlayer?.stop(); phrasePlayer?.release() } catch (_: Exception) { }
        phrasePlayer = null
    }

    /**
     * 第二段（逐字倒计时）：播放第 n 秒对应的数字音频 count_<n>（n=10..1）。
     * 由 AlertActivity 在倒计时每秒 onTick 时调用，天然适配任意剩余秒数
     * （5s 告警→播 5,4,3,2,1；3s→播 3,2,1）。每次先停上一段，避免叠加。
     */
    @Synchronized
    fun playCountDigit(n: Int) {
        try { countPlayer?.stop(); countPlayer?.release() } catch (_: Exception) { }
        countPlayer = null
        val resId = countDigitRes(n)
        if (resId == 0) return
        countPlayer = createPlayer(resId, speechAttr(), loop = false)
    }

    /** 停止倒计时播报。 */
    @Synchronized
    fun stopCountdown() {
        try { countPlayer?.stop(); countPlayer?.release() } catch (_: Exception) { }
        countPlayer = null
    }

    /** 倒计时结束：停止短语与倒计时，开始第三段警报循环（共享 alarm 资源）。 */
    @Synchronized
    fun startAlarm() {
        stopPhrase()
        stopCountdown()
        try { alarmPlayer?.release() } catch (_: Exception) { }
        alarmPlayer = null
        val resId = alarmResShared()
        if (resId == 0) return
        alarmPlayer = createPlayer(resId, alarmAttr(), loop = true)
    }

    /** 测试报警页：整段播放用户原始分级录音（短语+10→1+警报，原样不动），播完即停。 */
    @Synchronized
    fun demoLevel(level: WarningLevel) {
        stopAll()
        phrasePlayer = createPlayer(fullRes(level), alarmAttr(), loop = false)
    }

    /** 停止所有语音与警报（测试页停止 / 用户点「我已安全」）。 */
    @Synchronized
    fun stopAll() {
        try { phrasePlayer?.stop(); phrasePlayer?.release() } catch (_: Exception) { }
        try { countPlayer?.stop(); countPlayer?.release() } catch (_: Exception) { }
        try { alarmPlayer?.stop(); alarmPlayer?.release() } catch (_: Exception) { }
        phrasePlayer = null
        countPlayer = null
        alarmPlayer = null
    }

    fun release() = stopAll()
}
