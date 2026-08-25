package com.dianguard.app

import android.util.Log
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * 全局共享的 OkHttpClient 单例。
 *
 * 优化 #1：原 EewService / AppUpdateChecker / AppUpdater 各自 new 一个 OkHttpClient，
 * 等于同时维护 3 套连接池 + 3 套线程池，对“老手机低功耗常驻”场景是浪费。
 * 三者共享同一个实例：WebSocket 长连接的心跳 / DNS 缓存 / 连接复用互不干扰，
 * 短连接（更新检查 / APK 下载）也能复用底层连接池。
 *
 * 超时配置说明：
 *  - pingInterval 20s：保证 WebSocket 在 readTimeout 之内持续收到心跳帧，不会因“长时间无数据”被误关；
 *  - readTimeout 60s：远大于 pingInterval，长连接安全；短连接若真的卡死也会在 60s 内失败并回退；
 *  - connectTimeout 15s：初始建连的合理上限。
 *
 * 证书固定（Certificate Pinning，R5 修复）：
 *  - 防中间人注入虚假地震预警：即使攻击者控制了网络层，也无法用自签/伪 CA 证书冒充官方数据源；
 *  - 每个域名固定 2 个 SPKI 公钥（leaf 证书 + 中间 CA）：任一匹配即通过，
 *    既防伪冒，又容忍 leaf 证书轮换（中间 CA 长期稳定）——避免证书更新导致预警链路中断；
 *  - 覆盖全部预警/历史数据链路域名：wolfx.jp（实时 EEW + 历史主源）、
 *    chinaeew.cn（ICL 减灾所官方 HTTP 轮询）、usgs.gov / seismicportal.eu（备用源）。
 *
 * ⚠️ 证书固定“降级逃生舱”（R6 修复，P1 健壮性）：
 *  - 痛点：若官方悄悄轮换了 leaf 证书或中间 CA（SPKI 改变），固定校验会**永远失败**，
 *    导致该数据源彻底连不上，且没有任何恢复路径与用户提示——预警链路静默中断。
 *  - 修复：每个被固定的域名单独计数“证书固定连续失败次数”；达到阈值（PIN_FAIL_THRESHOLD）
 *    时判定为“官方证书轮换”，对该域名**降级为系统信任链**（放弃固定、但不放弃连接），
 *    并触发一次强制用户通知（onDegrade 回调）。降级后预警仍可接收，仅失去传输层防伪中间人保护，
 *    待后续版本同步新 SPKI 指纹后自然恢复固定。
 *  - 仅对 SSLPeerUnverifiedException / CertificateException 这类“固定失败”异常计数；
 *    普通网络抖动（超时、连接重置等）不计，避免误降级。
 *
 * 防重放 / 防注入说明：
 *  数据源均为公开无签名接口，无法对报文做请求级签名验证；防伪造依靠
 * 「证书固定（传输层）」+「多源融合交叉验证（EewFusion，应用层）」双层防线：
 *  单源伪造报文会被融合引擎的加权中位数/置信度机制抑制，双源以上一致才触发高等级告警。
 */
object HttpClient {

    private const val TAG = "HttpClient"

    /** 各数据源域名的受信 SPKI 指纹（leaf + 中间 CA）。轮换后需同步更新此处。 */
    private val PINNED_HOSTS = mapOf(
        "ws-api.wolfx.jp" to listOf(
            "sha256/xHkCalA8UHfr3u2AULK+FuxrTDNyrYD6Vp1ctKCMra8=", // leaf (wolfx.jp)
            "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4="  // Google Trust Services WE1
        ),
        "api.wolfx.jp" to listOf(
            "sha256/xHkCalA8UHfr3u2AULK+FuxrTDNyrYD6Vp1ctKCMra8=", // leaf (wolfx.jp，与 ws-api 同证书)
            "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4="  // Google Trust Services WE1
        ),
        "mobile-new.chinaeew.cn" to listOf(
            "sha256/GsoEUuQdbrQT8WdmfDDrokPmsSBfEmn79t2o78T7rOs=",  // leaf (*.chinaeew.cn)
            "sha256/1JBDEIRiGeUpYDPmP59k44+SdOuHxRHBOvpwOqLsrE8="   // WoTrus RSA DV SSL CA 2
        ),
        "earthquake.usgs.gov" to listOf(
            "sha256/T0/8gG0XqPpgEla0kzeEaNz3oh3mO00JU6F74AWj0ZM=",  // leaf (*.usgs.gov)
            "sha256/Wec45nQiFwKvHtuHxSAMGkt19k+uPSw9JlEkxhvYPHk="   // DigiCert Global G2 TLS RSA SHA256 2020 CA1
        ),
        "www.seismicportal.eu" to listOf(
            "sha256/OmeVEgu8rx8NwWJVLHeJjEix5e/LIVM93VbBuAMUA9E=",  // leaf (seismicportal.eu)
            "sha256/nWN7PSep5XDQdge5zK24CnCRXHr3KvzhKEGxsdqCX9E="   // Let's Encrypt YR2
        )
    )

    /** 证书固定连续失败达到该次数 → 判定官方轮换证书 → 降级为系统信任 */
    private const val PIN_FAIL_THRESHOLD = 3

    private val degradeLock = Any()
    private val pinFailCounts = mutableMapOf<String, Int>()
    /** 已降级的域名集合（不再固定其证书，改用系统信任链） */
    private val degradedHosts = mutableSetOf<String>()

    /** 降级回调：由 EewService 设置，用于向用户弹出一次性强提醒 */
    var onDegrade: ((host: String) -> Unit)? = null

    /** 判断某异常是否由证书固定失败引起（沿 cause 链查找） */
    private fun isPinFailure(t: Throwable?): Boolean {
        var cur = t
        while (cur != null) {
            if (cur is SSLPeerUnverifiedException || cur is CertificateException) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * 上报某域名的连接失败。若该失败属于证书固定异常、且累计达到阈值，
     * 则将该域名降级为系统信任链并触发一次用户通知（每个域名仅通知一次）。
     *
     * @param host 失败连接对应的域名（如 "mobile-new.chinaeew.cn"）
     * @param t    失败异常；非固定类异常（超时/重置等）直接忽略
     */
    fun reportFailure(host: String, t: Throwable?) {
        if (!isPinFailure(t)) return
        if (host !in PINNED_HOSTS) return // 仅对已固定域名计数
        synchronized(degradeLock) {
            val c = pinFailCounts.getOrDefault(host, 0) + 1
            pinFailCounts[host] = c
            if (c >= PIN_FAIL_THRESHOLD && !degradedHosts.contains(host)) {
                degradedHosts.add(host)
                Log.w(TAG, "证书固定连续失败 $c 次，降级为系统信任: $host")
                rebuildInstance()
                try { onDegrade?.invoke(host) } catch (_: Exception) { }
            }
        }
    }

    /** 由受信指纹表构建 OkHttp CertificatePinner（未列出的域名 / 已降级域名不固定，保持系统信任链） */
    internal fun buildCertificatePinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        for ((host, pins) in PINNED_HOSTS) {
            if (host in degradedHosts) continue // 降级域名不再固定
            builder.add(host, *pins.toTypedArray())
        }
        return builder.build()
    }

    /** 可重建的共享客户端实例（降级时重新构建以剔除某域名的固定规则） */
    @Volatile var instance: OkHttpClient = buildClient()
        private set

    private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .certificatePinner(buildCertificatePinner())
        .build()

    /** 线程安全地重建客户端（剔除已降级域名的固定规则） */
    private fun rebuildInstance() {
        synchronized(degradeLock) {
            instance = buildClient()
        }
    }
}
