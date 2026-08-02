package com.dianguard.app

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
 */
object HttpClient {
    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
