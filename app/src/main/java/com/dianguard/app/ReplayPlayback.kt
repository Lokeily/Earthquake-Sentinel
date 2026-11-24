package com.dianguard.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 回放调度器：复用真实告警的首报 Intent（启动 AlertActivity）+ AppEvents 刷新协议。
 * 不持有 Activity；回放设置页进入后台后仍可按官方实际报文间隔继续发送修正报。
 */
object ReplayPlayback {
    data class Update(val delayMs: Long, val refreshEvent: AppEvents.RefreshEvent)

    private val handler = Handler(Looper.getMainLooper())
    private val scheduled = mutableListOf<Runnable>()

    @Synchronized
    fun start(context: Context, firstAlert: Intent, updates: List<Update>) {
        cancel()
        val app = context.applicationContext
        app.startActivity(firstAlert)
        for (update in updates) {
            lateinit var task: Runnable
            task = Runnable {
                val ev = update.refreshEvent
                AppEvents.postRefresh(ev.eventId, ev.mag, ev.place, ev.distance, ev.eta, ev.intensity, ev.depth, ev.reportNum)
                synchronized(this) { scheduled.remove(task) }
            }
            scheduled += task
            handler.postDelayed(task, update.delayMs.coerceAtLeast(0L))
        }
    }

    @Synchronized
    fun cancel() {
        for (task in scheduled) handler.removeCallbacks(task)
        scheduled.clear()
    }
}
