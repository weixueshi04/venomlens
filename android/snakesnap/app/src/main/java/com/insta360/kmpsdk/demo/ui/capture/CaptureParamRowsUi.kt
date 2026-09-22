package com.insta360.kmpsdk.demo.ui.capture

import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.insta360.kmpsdk.demo.R
import timber.log.Timber

/**
 * 在拍摄参数底部 Sheet 等位置渲染 Spinner 行；与 [CameraCaptureViewModel.onParamSelectionChanged] 配合。
 */
object CaptureParamRowsUi {

    /** 防止 Spinner 重绑选中项时误触发业务回调。 */
    class BinderState {
        var suppressSpinnerCallback: Boolean = false
    }

    // 联动参数会让同一 key 的 optionLabels 长度/内容变化，需把选项一并纳入签名，
    // 触发完整 rebuild 以同步 Spinner adapter，避免 setSelection 越界
    private fun rowSignature(row: CaptureParamRow): Any = row.key to row.optionLabels

    @Suppress("UNCHECKED_CAST")
    private fun logRowDiff(
        container: LinearLayout,
        rows: List<CaptureParamRow>,
    ) {
        val prev = mutableMapOf<String, Pair<List<String>, Int>>()
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i) as? LinearLayout ?: continue
            val tag = child.tag as? Pair<*, *> ?: continue
            val key = tag.first as? String ?: continue
            val opts = tag.second as? List<String> ?: continue
            val sel = (child.getChildAt(1) as? Spinner)?.selectedItemPosition ?: -1
            prev[key] = opts to sel
        }
        val newKeys = rows.map { it.key }.toSet()
        rows.forEach { row ->
            val p = prev[row.key]
            if (p == null) {
                Timber.d(
                    "paramRow add key=%s opts=%s sel=%d",
                    row.key,
                    row.optionLabels,
                    row.selectedIndex,
                )
                return@forEach
            }
            val (prevOpts, prevSel) = p
            if (prevOpts != row.optionLabels) {
                Timber.d(
                    "paramRow opts changed key=%s old=%s new=%s",
                    row.key,
                    prevOpts,
                    row.optionLabels,
                )
            }
            if (prevSel != row.selectedIndex) {
                Timber.d(
                    "paramRow sel changed key=%s %d->%d label=%s",
                    row.key,
                    prevSel,
                    row.selectedIndex,
                    row.optionLabels.getOrNull(row.selectedIndex) ?: "",
                )
            }
        }
        prev.keys.filter { it !in newKeys }.forEach { k ->
            Timber.d("paramRow remove key=%s", k)
        }
    }

    fun render(
        container: LinearLayout,
        rows: List<CaptureParamRow>,
        state: BinderState,
        onParamSelected: (rowKey: String, index: Int) -> Unit,
    ) {
        val context = container.context
        logRowDiff(container, rows)
        val unchanged =
            container.childCount == rows.size &&
                rows.indices.all { i ->
                    container.getChildAt(i).tag == rowSignature(rows[i])
                }
        if (unchanged) {
            var shouldReleaseSuppression = false
            for (i in rows.indices) {
                val row = rows[i]
                val rowView = container.getChildAt(i) as LinearLayout
                (rowView.getChildAt(0) as TextView).text = row.displayLabel
                val spinner = rowView.getChildAt(1) as Spinner
                if (spinner.selectedItemPosition != row.selectedIndex) {
                    state.suppressSpinnerCallback = true
                    shouldReleaseSuppression = true
                    spinner.setSelection(row.selectedIndex.coerceIn(0, row.optionLabels.size - 1))
                }
            }
            if (shouldReleaseSuppression) {
                container.post {
                    state.suppressSpinnerCallback = false
                }
            }
            return
        }

        container.removeAllViews()
        val density = context.resources.displayMetrics.density
        state.suppressSpinnerCallback = true
        for (row in rows) {
            val rowLayout = LinearLayout(context)
            rowLayout.orientation = LinearLayout.VERTICAL
            rowLayout.layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = (12 * density).toInt()
                }
            rowLayout.tag = rowSignature(row)

            val label = TextView(context)
            label.text = row.displayLabel
            label.textSize = 14f
            label.setTextColor(ContextCompat.getColor(context, R.color.demo_primary_text))
            rowLayout.addView(label)

            val spinner = Spinner(context)
            val adapter =
                ArrayAdapter(
                    context,
                    android.R.layout.simple_spinner_item,
                    row.optionLabels,
                )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            spinner.setSelection(row.selectedIndex.coerceIn(0, row.optionLabels.size - 1))
            val rowKey = row.key
            spinner.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        if (state.suppressSpinnerCallback) return
                        onParamSelected(rowKey, position)
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            rowLayout.addView(spinner)
            container.addView(rowLayout)
        }
        container.post {
            state.suppressSpinnerCallback = false
        }
    }
}
