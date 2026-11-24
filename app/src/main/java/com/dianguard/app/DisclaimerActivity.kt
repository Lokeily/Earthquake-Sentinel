package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * 用户协议与免责声明页（v1.3.3 两步向导 · 建议倒计时）。
 *
 * 为避免一页过长，将两份协议拆成两个步骤依次展示：
 *  - 第一步：《用户服务协议》——建议阅读时长 15 秒
 *  - 第二步：《免责声明协议》——建议阅读时长 20 秒
 *
 * 解锁「同意」仅需一个条件：滑动到页面最底部。
 * 倒计时（建议阅读时间）从打开这份协议那一刻开始递减，仅作为阅读时长的建议提示展示，
 * 不强制用户等待——滑到底即可同意，无论倒计时是否结束；中途滑离底部会重新锁定按钮。
 *
 *  - 首次进入（force 模式）：未全部同意前无法返回主页，点「不同意并退出」或按返回键则退出应用；
 *  - 设置页查看（普通模式）：可随时返回，不强制同意。
 *
 * 全部同意后状态写入 AppConfig.disclaimerAccepted，供首次进入判定与「开启预警」前置校验使用。
 */
class DisclaimerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE = "force"

        /** 第一步《用户服务协议》建议阅读时长（秒），仅提示不强制 */
        private const val READ_SECONDS_SERVICE = 15
        /** 第二步《免责声明协议》建议阅读时长（秒），仅提示不强制 */
        private const val READ_SECONDS_DISCLAIMER = 20

        private const val STEP_SERVICE_AGREEMENT = 0
        private const val STEP_DISCLAIMER = 1
    }

    private var forceMode = false
    private var currentStep = STEP_SERVICE_AGREEMENT

    /** 是否已滑动到页面最底部 */
    private var reachedBottom = false
    /** 倒计时是否已结束（仅影响提示文案，不影响解锁） */
    private var countdownDone = false
    /** 建议阅读剩余秒数（打开协议即开始递减） */
    private var remainSeconds = READ_SECONDS_SERVICE

    private val handler = Handler(Looper.getMainLooper())

    /** 每秒递减一次；打开协议页即启动，仅作为建议时间提示，不阻塞同意 */
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (countdownDone) return
            remainSeconds--
            if (remainSeconds <= 0) {
                remainSeconds = 0
                countdownDone = true
                refreshButtons()
            } else {
                // 未滑到底时持续刷新按钮上的建议时间
                if (!reachedBottom) {
                    btnAgree.text = getString(R.string.agreement_countdown, remainSeconds)
                }
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private lateinit var scrollView: ScrollView
    private lateinit var btnAgree: Button
    private lateinit var btnDisagree: Button
    private lateinit var tvHint: TextView
    private lateinit var tvTitle: TextView
    private lateinit var llServiceAgreement: LinearLayout
    private lateinit var llDisclaimer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disclaimer)

        forceMode = intent.getBooleanExtra(EXTRA_FORCE, false)
        scrollView = findViewById(R.id.scroll_disclaimer)
        btnAgree = findViewById(R.id.btn_agree)
        btnDisagree = findViewById(R.id.btn_disagree)
        tvHint = findViewById(R.id.tv_read_hint)
        tvTitle = findViewById(R.id.tv_disclaimer_title)
        llServiceAgreement = findViewById(R.id.ll_service_agreement)
        llDisclaimer = findViewById(R.id.ll_disclaimer)
        val btnBack = findViewById<View>(R.id.btn_back)

        // 初始化显示第一步：用户服务协议（建议倒计时从此刻开始）
        showStep(STEP_SERVICE_AGREEMENT)

        // 返回键处理（OnBackPressedDispatcher，替代已废弃的 onBackPressed）
        setupBackHandler()

        // 滚动监听：仅记录「是否滑到底部」；建议倒计时独立于滚动，不因滑离重置
        scrollView.viewTreeObserver.addOnScrollChangedListener {
            val atBottom = isAtBottom(scrollView)
            if (atBottom != reachedBottom) {
                reachedBottom = atBottom
                refreshButtons()
            }
        }

        btnAgree.setOnClickListener {
            if (currentStep == STEP_SERVICE_AGREEMENT) {
                // 第一步同意 → 进入第二步：免责声明（建议倒计时重新开始）
                showStep(STEP_DISCLAIMER)
            } else {
                // 全部同意：写入同意状态并结束
                AppConfig.disclaimerAccepted = true
                AppConfig.disclaimerAcceptedVersion = AppConfig.DISCLAIMER_VERSION
                if (forceMode) {
                    startActivity(Intent(this, MainActivity::class.java))
                }
                finish()
            }
        }

        btnDisagree.setOnClickListener { exitApp() }

        if (forceMode) {
            // 强制模式：隐藏返回按钮，展示「不同意并退出」
            btnBack.visibility = View.GONE
            btnDisagree.visibility = View.VISIBLE
        } else {
            btnBack.setOnClickListener { finish() }
        }
    }

    /** 切换到指定步骤：切换可见协议块、重置状态、重置建议倒计时并立即开始递减 */
    private fun showStep(step: Int) {
        currentStep = step
        reachedBottom = false
        countdownDone = false
        // 建议阅读时长：第一份 15 秒，第二份 20 秒
        remainSeconds = if (step == STEP_SERVICE_AGREEMENT) READ_SECONDS_SERVICE else READ_SECONDS_DISCLAIMER
        handler.removeCallbacks(countdownRunnable)

        llServiceAgreement.visibility = if (step == STEP_SERVICE_AGREEMENT) View.VISIBLE else View.GONE
        llDisclaimer.visibility = if (step == STEP_DISCLAIMER) View.VISIBLE else View.GONE

        tvTitle.setText(
            if (step == STEP_SERVICE_AGREEMENT) R.string.service_agreement_title
            else R.string.disclaimer_step_title
        )
        tvHint.setText(
            if (step == STEP_SERVICE_AGREEMENT) R.string.agreement_step_1_hint
            else R.string.agreement_step_2_hint
        )

        // 初始禁用：需滑到底部才可同意；按钮上展示建议阅读时间
        btnAgree.isEnabled = false
        btnAgree.alpha = 0.45f
        btnAgree.text = getString(R.string.agreement_countdown, remainSeconds)
        handler.postDelayed(countdownRunnable, 1000L)

        // 切换协议块后回到顶部，避免残留滚动位置
        scrollView.post { scrollView.scrollTo(0, 0) }
    }

    /**
     * 刷新按钮状态：仅「滑到底部」一个解锁条件。
     *  - 未滑到底且倒计时中：显示「建议阅读时间 X 秒」（禁用）
     *  - 未滑到底且倒计时结束：显示「请滑动至协议底部后同意」（禁用）
     *  - 已滑到底：显示「阅读完毕并同意」（启用，无论倒计时是否结束）
     */
    private fun refreshButtons() {
        if (!reachedBottom) {
            btnAgree.isEnabled = false
            btnAgree.alpha = 0.45f
            if (countdownDone) {
                btnAgree.setText(R.string.agreement_need_scroll_bottom)
            } else {
                btnAgree.text = getString(R.string.agreement_countdown, remainSeconds)
            }
            tvHint.setText(R.string.agreement_need_scroll_bottom)
        } else {
            btnAgree.isEnabled = true
            btnAgree.alpha = 1f
            btnAgree.setText(
                if (currentStep == STEP_SERVICE_AGREEMENT) R.string.agreement_go_next
                else R.string.agreement_read_done
            )
            tvHint.setText(R.string.agreement_read_done)
        }
    }

    /** 判断 ScrollView 是否滑动到底部 */
    private fun isAtBottom(scrollView: ScrollView): Boolean {
        val child = scrollView.getChildAt(0) ?: return false
        val scrollRange = child.height - scrollView.height
        if (scrollRange <= 0) return true
        return scrollView.scrollY >= scrollRange - 8
    }

    /** 返回键：v1.4.3 迁移到 OnBackPressedDispatcher（替代已废弃的 onBackPressed）。 */
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (forceMode) {
                    // 首次进入强制模式：未同意前按返回键 = 不同意，退出应用
                    exitApp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun exitApp() {
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(countdownRunnable)
    }
}
