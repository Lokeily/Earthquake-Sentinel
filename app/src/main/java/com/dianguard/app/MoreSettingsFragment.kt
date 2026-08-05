package com.dianguard.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * 更多设置（二级菜单，v1.3.1）：由设置页「更多设置」行下钻进入。
 * 顶部「返回」或系统返回键均 pop 回设置页。
 * 内容：通用（系统自检 / 检查更新 / 清空预警历史）、关于（免责声明与用户协议 / 版本号）。
 */
class MoreSettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_more_settings, container, false)

        val ivBack = root.findViewById<View>(R.id.iv_back)
        val rowSelfCheck = root.findViewById<View>(R.id.row_selfcheck)
        val rowUpdate = root.findViewById<View>(R.id.row_update)
        val rowClear = root.findViewById<View>(R.id.row_clear)
        val rowDisclaimer = root.findViewById<View>(R.id.row_disclaimer)
        val tvAboutVersion = root.findViewById<TextView>(R.id.tv_about_version)

        ivBack.setOnClickListener { activity?.supportFragmentManager?.popBackStack() }
        rowSelfCheck.setOnClickListener { SelfCheck.showDialog(requireContext()) }
        rowUpdate.setOnClickListener { checkUpdate() }
        rowClear.setOnClickListener { confirmClearHistory() }
        rowDisclaimer.setOnClickListener {
            startActivity(Intent(requireContext(), DisclaimerActivity::class.java))
        }

        try {
            tvAboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
        } catch (_: Exception) {
            tvAboutVersion.text = ""
        }
        return root
    }

    // ===================== 检查更新（手动） =====================

    private fun checkUpdate() {
        Toast.makeText(requireContext(), "正在检查更新…", Toast.LENGTH_SHORT).show()
        AppUpdateChecker.check { info ->
            val act = activity ?: return@check
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                if (info.available) {
                    AppUpdater.showUpdateDialog(act as AppCompatActivity, info)
                } else {
                    Toast.makeText(requireContext(), getString(R.string.update_none), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ===================== 清空预警历史记录（带确认弹窗） =====================

    private fun confirmClearHistory() {
        if (QuakeHistory.all().isEmpty()) {
            Toast.makeText(requireContext(), "暂无历史记录", Toast.LENGTH_SHORT).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.history_clear) { _, _ ->
                HistoryFragment.clearAll()
                Toast.makeText(requireContext(), "已清空预警历史记录", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
