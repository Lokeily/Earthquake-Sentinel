package com.dianguard.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * 免责声明与用户协议页（v1.1.0 全新发布）。
 * 仅展示作用，无后台逻辑；"我已阅读并同意" 的状态由 HomeFragment 的首次开启对话框维护。
 */
class DisclaimerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disclaimer)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
    }
}