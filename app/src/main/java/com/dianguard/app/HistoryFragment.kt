package com.dianguard.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 预警历史记录页（v1.0.17）：底部导航“预警历史”内容区。
 * 展示 QuakeHistory 持久化的全部记录（最近 7 天 / 最多 100 条），
 * 按时间倒序，标注「已告警 / 备用源速报 / 已记录」，并提供清空入口。
 */
class HistoryFragment : Fragment() {

    private lateinit var historyList: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var btnClear: Button

    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_history, container, false)
        historyList = root.findViewById(R.id.history_list)
        tvEmpty = root.findViewById(R.id.tv_history_empty)
        tvCount = root.findViewById(R.id.tv_history_count)
        btnClear = root.findViewById(R.id.btn_history_clear)
        btnClear.setOnClickListener { confirmClear() }
        return root
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    /** 重新读取并渲染历史记录 */
    private fun rebuild() {
        val records = QuakeHistory.all()
        historyList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())

        tvCount.text = getString(R.string.history_count, records.size)
        tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        for (r in records) {
            val row = inflater.inflate(R.layout.history_item, historyList, false)
            val place = row.findViewById<TextView>(R.id.item_place)
            val meta = row.findViewById<TextView>(R.id.item_meta)
            val badge = row.findViewById<TextView>(R.id.item_badge)

            place.text = if (r.place.isBlank()) "未知地区" else r.place
            val mag = if (r.magnitude > 0) "M%.1f级".format(r.magnitude) else "震级未知"
            val intensity = if (r.intensity.isBlank()) "" else " · 烈度${r.intensity}"
            val dist = if (r.distanceKm > 0) " · 震中约%.0fkm".format(r.distanceKm) else ""
            val src = if (r.sourceName.isBlank()) "" else " · 源：${r.sourceName}"
            meta.text = "${dateFmt.format(Date(r.timeMs))}  $mag$intensity$dist$src"

            when {
                r.triggered -> {
                    badge.text = getString(R.string.history_badge_alerted)
                    badge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.alert_red)
                    badge.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                r.backup -> {
                    badge.text = getString(R.string.history_badge_backup)
                    badge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.source_orange)
                    badge.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
                else -> {
                    badge.text = getString(R.string.history_badge_logged)
                    badge.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.ios_label_secondary)
                    badge.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
                }
            }
            historyList.addView(row)
        }
    }

    private fun confirmClear() {
        if (QuakeHistory.all().isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.history_clear) { _, _ ->
                QuakeHistory.clear()
                rebuild()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
