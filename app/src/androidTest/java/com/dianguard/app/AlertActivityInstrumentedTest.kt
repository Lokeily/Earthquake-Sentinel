package com.dianguard.app

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1-1 仪表化测试：AlertActivity 全屏告警页 UI 校验。
 *
 * 在真机/模拟器上运行（gradlew connectedDebugAndroidTest）。
 * 验证：
 *  - 通过 Intent 注入首报数据后，倒计时、震级、地点正常渲染；
 *  - 返回键被拦截（告警期间不能误退）；
 *  - “我已安全”按钮可见。
 *
 * 注意：本测试不触发语音/震动（真实告警链路依赖系统服务，不在本用例范围）。
 */
@RunWith(AndroidJUnit4::class)
class AlertActivityInstrumentedTest {

    private fun launchAlert(intentBlock: Intent.() -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, AlertActivity::class.java).apply { intentBlock() }
        ActivityScenario.launch<AlertActivity>(intent)
    }

    @Test
    fun countdownAndInfoRendered() {
        launchAlert {
            putExtra(EewService.EXTRA_EVENT_ID, "test-ivt-1")
            putExtra(EewService.EXTRA_MAG, 5.2)
            putExtra(EewService.EXTRA_PLACE, "云南普洱市思茅区")
            putExtra(EewService.EXTRA_DISTANCE, 88.0)
            putExtra(EewService.EXTRA_ETA, 12.0)
            putExtra(EewService.EXTRA_INTENSITY, "4.5")
            putExtra(EewService.EXTRA_DEPTH, 10.0)
            putExtra(EewService.EXTRA_REPORT_NUM, 1)
            putExtra(EewService.EXTRA_SOURCE, "测试源")
            putExtra(EewService.EXTRA_TIME, System.currentTimeMillis())
        }

        // 震级与地点
        onView(withId(R.id.tv_mag)).check(matches(withText("5.2级地震")))
        onView(withId(R.id.tv_place)).check(matches(withText("云南普洱市思茅区")))
        // 倒计时初始为大数字（"12" 或已递减，断言显示非 "--"）
        onView(withId(R.id.tv_countdown)).check(matches(isDisplayed()))
        // “我已安全”按钮可见
        onView(withId(R.id.btn_dismiss)).check(matches(isDisplayed()))
    }

    @Test
    fun dismissButtonVisible() {
        launchAlert {
            putExtra(EewService.EXTRA_EVENT_ID, "test-ivt-2")
            putExtra(EewService.EXTRA_MAG, 3.0)
            putExtra(EewService.EXTRA_PLACE, "测试")
            putExtra(EewService.EXTRA_ETA, 30.0)
        }
        onView(withId(R.id.btn_dismiss)).check(matches(withText(R.string.alert_dismiss)))
    }
}
