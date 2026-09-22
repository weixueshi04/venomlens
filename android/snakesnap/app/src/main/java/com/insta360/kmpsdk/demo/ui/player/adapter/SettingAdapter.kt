package com.insta360.kmpsdk.demo.ui.player.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.insta360.kmpsdk.demo.databinding.ItemPlayerSettingBinding
import com.insta360.kmpsdk.demo.ui.player.adapter.coerceSettingIndex
import timber.log.Timber
import timber.log.Timber.Forest.d

class SettingAdapter : ListAdapter<Triple<SettingType, List<SettingOption>, Int>, SettingAdapter.VH>(DIFF) {

    private var onItemSelectListener: ((SettingType, SettingOption) -> Unit)? = null
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(ItemPlayerSettingBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class VH(private val binding: ItemPlayerSettingBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Triple<SettingType, List<SettingOption>, Int>, index: Int) {
            binding.tvTitle.setText(item.first.textId)
            var isUserTouch = false
            val listener = object : AdapterView.OnItemSelectedListener, View.OnTouchListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (isUserTouch) {
                        Timber.d("position == $position")
                        val selected = item.second[position]
                        onItemSelectListener?.invoke(item.first, selected)
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onTouch(p0: View?, event: MotionEvent): Boolean {
                    // 触摸开始时标记为用户操作
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        isUserTouch = true
                    }
                    return false // 不拦截触摸事件，保证 Spinner 正常下拉
                }
            }
            binding.spOption.onItemSelectedListener = listener
            binding.spOption.setOnTouchListener(listener)
            binding.spOption.adapter = object : ArrayAdapter<SettingOption>(binding.root.context, android.R.layout.simple_spinner_item, item.second) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    val item = getItem(position)!!
                    if (item.text.isNotEmpty()) {
                        view.text = item.text
                    } else {
                        view.setText(item.textId)
                    }
                    return view
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getDropDownView(position, convertView, parent) as TextView
                    val item = getItem(position)!!
                    if (item.text.isNotEmpty()) {
                        view.text = item.text
                    } else {
                        view.setText(item.textId)
                    }
                    return view
                }
            }
            binding.spOption.setSelection(item.third.coerceSettingIndex(item.second))
        }
    }

    fun setOnItemSelectListener(listener: (SettingType, SettingOption) -> Unit) {
        onItemSelectListener = listener
    }


    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Triple<SettingType, List<SettingOption>, Int>>() {
            override fun areItemsTheSame(a: Triple<SettingType, List<SettingOption>, Int>, b: Triple<SettingType, List<SettingOption>, Int>): Boolean {
                return a == b
            }

            @SuppressLint("DiffUtilEquals")
            override fun areContentsTheSame(a: Triple<SettingType, List<SettingOption>, Int>, b: Triple<SettingType, List<SettingOption>, Int>): Boolean {
                return a.first == b.first && a.third == b.third && a.second == b.second
            }
        }
    }
}
