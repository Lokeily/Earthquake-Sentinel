package com.dianguard.app

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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
 *    chinaeew.cn（ICL 减灾所官方 HTTP 轮询）、usgs.gov / seismicportal.eu（备用源）、
 *    bigdatacloud.net（逆地理编码）。
 *
 * 防重放 / 防注入说明：
 *  数据源均为公开无签名接口，无法对报文做请求级签名验证；防伪造依靠
 *  「证书固定（传输层）」+「多源融合交叉验证（EewFusion，应用层）」双层防线：
 *  单源伪造报文会被融合引擎的加权中位数/置信度机制抑制，双源以上一致才触发高等级告警。
 */
object HttpClient {

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
        ),
        "api.bigdatacloud.net" to listOf(
            "sha256/LAsG76f/x3AAVi7gWwU7oa8z/UfOBDrFBaOvxAcRQo0=",  // leaf (*.bigdatacloud.net)
            "sha256/42b9RNOnyb3tlC0KYtNPA3KKpJluskyU6aG+CipUmaM="   // Thawte TLS RSA CA G1
        )
    )

    /** 由受信指纹表构建 OkHttp CertificatePinner（未列出的域名不固定，保持系统信任链） */
    fun buildCertificatePinner(): CertificatePinner {
        val builder = CertificatePinner.Builder()
        for ((host, pins) in PINNED_HOSTS) {
            builder.add(host, *pins.toTypedArray())
        }
        return builder.build()
    }

    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .certificatePinner(buildCertificatePinner())
            .build()
    }
}
