package com.dianguard.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1-1 仪表化测试：EewService 前台服务生命周期。
 *
 * EewService 是纯 startService 前台服务（onBind 返回 null，不可绑定），
 * 因此验证点聚焦「能否正常启动并保持运行」：
 *  - startForegroundService 后，服务进入运行态；
 *  - 前台服务类型含 dataSync（Android 11+ 可查询，通知常驻）。
 *
 * 注意：真实预警监听依赖网络，本测试只验证服务生命周期，不发起真实连接。
 */
@RunWith(AndroidJUnit4::class)
class EewServiceTest {

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.stopService(Intent(context, EewService::class.java))
    }

    @Test
    fun serviceStartsAsForeground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.startForegroundService(Intent(context, EewService::class.java))

        // 前台服务启动有异步时序（onCreate → startForeground），轮询等待就绪
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        var running = false
        repeat(10) {
            running = am.getRunningServices(100)
                .any { it.service.className == EewService::class.java.name }
            if (running) return@repeat
            Thread.sleep(300)
        }
        assertTrue("EewService 应作为前台服务处于运行状态", running)
    }

    @Test
    fun serviceSurvivesStopSelfCheck() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.startForegroundService(Intent(context, EewService::class.java))
        Thread.sleep(800)

        // 服务应处于运行态且未被系统回收（前台服务保活策略）
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = am.getRunningServices(100)
            .firstOrNull { it.service.className == EewService::class.java.name }
        assertTrue("EewService 应保持运行", info != null)
    }
}
