package com.insta360.kmpsdk.demo.species

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.insta360.kmpsdk.demo.databinding.ActivitySpeciesComparisonBinding
import com.insta360.kmpsdk.demo.hospital.HospitalDirectoryActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class SpeciesComparisonActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySpeciesComparisonBinding
    private var imageJob: Job? = null
    private var lookAlikesExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySpeciesComparisonBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        lookAlikesExpanded = savedInstanceState?.getBoolean(STATE_LOOK_ALIKES) ?: false
        binding.speciesBack.setOnClickListener { finish() }
        binding.speciesHospital.setOnClickListener {
            try {
                startActivity(Intent(this, HospitalDirectoryActivity::class.java))
            } catch (_: ActivityNotFoundException) {
                showMessage("就医目录暂不可用；被咬伤请立即就医。")
            } catch (_: SecurityException) {
                showMessage("无法打开就医目录；被咬伤请立即就医。")
            }
        }
        binding.speciesSafety.text = SpeciesCatalog.SAFETY_WARNING
        binding.speciesResultSource.text = ComparisonResultSource.from(intent.getStringExtra(EXTRA_RESULT_SOURCE)).badge
        lifecycleScope.launch {
            val catalog = withContext(Dispatchers.IO) {
                try {
                    assets.open(SpeciesCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
                        val text = StringBuilder()
                        val buffer = CharArray(4096)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read == -1) break
                            require(text.length + read <= SpeciesCatalog.MAX_JSON_CHARACTERS)
                            text.append(buffer, 0, read)
                        }
                        SpeciesCatalog.parse(text.toString())
                    }
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: SecurityException) {
                    null
                }
            }
            if (catalog == null) {
                showEmpty("离线比对资料缺失或格式不可用，按未知处理。")
            } else {
                renderCatalog(catalog)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_LOOK_ALIKES, lookAlikesExpanded)
        super.onSaveInstanceState(outState)
    }

    internal fun renderCatalog(catalog: SpeciesCatalog) {
        imageJob?.cancel()
        binding.speciesImages.removeAllViews()
        binding.speciesExternalRefs.removeAllViews()
        val entry = catalog.find(intent.getStringExtra(EXTRA_SPECIES_ID))
        if (entry == null) {
            showEmpty("未找到该候选的离线物种资料，按未知处理；不会补写比对内容。")
            return
        }
        binding.speciesEmpty.isVisible = false
        binding.speciesDetails.isVisible = true
        binding.speciesReview.text = entry.reviewNotice
        binding.speciesCommonName.text = entry.commonName
        binding.speciesScientificName.text = entry.scientificName
        binding.speciesAliases.isVisible = entry.aliases.isNotEmpty()
        binding.speciesAliases.text = entry.aliases.joinToString("\n") {
            buildString {
                append("别名：${it.alias}")
                it.region?.let { region -> append("（$region）") }
                it.source?.let { source -> append("\n别名来源：$source") }
            }
        }
        binding.speciesCatalogSource.text = "名称资料来源：${entry.source ?: "未提供"}"
        renderProfile(entry.comparisonProfile, catalog)
        val pendingImages = entry.referenceImages.mapIndexed { index, reference ->
            addReference(index, entry, reference)
        }
        if (pendingImages.isEmpty()) binding.speciesImages.addView(text("暂无带来源和授权的离线参考图。"))
        entry.externalRefs.forEach { reference ->
            binding.speciesExternalRefs.addView(linkButton("${reference.name}\n${reference.url}", reference.url).apply {
                tag = "external-ref:${reference.url}"
            })
        }
        if (entry.externalRefs.isEmpty()) binding.speciesExternalRefs.addView(text("暂无可安全打开的外部参考链接。"))
        if (entry.rejectedExternalRefs > 0) {
            binding.speciesExternalRefs.addView(text("部分外部链接未通过 HTTPS 安全检查，已禁用。"))
        }
        binding.speciesLoading.isVisible = pendingImages.isNotEmpty()
        imageJob = lifecycleScope.launch {
            var remainingBytes = OfflineReferenceImages.SCREEN_BYTE_BUDGET
            for (row in pendingImages) {
                if (!row.reference.canLoad) continue
                if (remainingBytes < 128 * 128 * 4) {
                    row.status.text = "图片未加载：已达到本页离线图片内存上限。"
                    continue
                }
                val bitmap = withContext(Dispatchers.IO) {
                    OfflineReferenceImages.decode(assets, entry.speciesId, row.reference, remainingBytes)
                }
                if (bitmap == null) {
                    row.status.text = "图片缺失或无法解码（离线资源不可用）。"
                } else {
                    remainingBytes -= bitmap.allocationByteCount
                    row.image.setImageBitmap(bitmap)
                    row.image.isVisible = true
                    row.status.isVisible = false
                }
            }
            binding.speciesLoading.isVisible = false
        }
    }

    private fun renderProfile(profile: ComparisonProfile?, catalog: SpeciesCatalog) {
        binding.speciesProfileEmpty.isVisible = profile == null
        binding.speciesProfile.isVisible = profile != null
        if (profile == null) return
        binding.speciesHook.isVisible = profile.hook != null
        binding.speciesHook.text = profile.hook
        binding.speciesChecklist.text = profile.layChecklist.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { "• $it" } ?: "暂无可供比对的观察清单。"
        binding.speciesDoNot.text = "不要这样做（文案待审核）\n" +
            (profile.doNot.takeIf { it.isNotEmpty() }?.joinToString("\n") { "• $it" }
                ?: "暂无条目；仍须遵守页面顶部安全提醒。")
        binding.speciesLookAlikes.text = profile.layLookAlikes.takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") {
                val name = catalog.find(it.speciesId)?.commonName ?: "关联物种资料缺失，按未知处理"
                "$name\n${it.layHowToTell}"
            } ?: "暂无易混淆项资料；不表示不存在易混淆物种。"
        updateLookAlikes()
        binding.speciesLookAlikesToggle.setOnClickListener {
            lookAlikesExpanded = !lookAlikesExpanded
            updateLookAlikes()
        }
    }

    private fun updateLookAlikes() {
        binding.speciesLookAlikes.isVisible = lookAlikesExpanded
        binding.speciesLookAlikesToggle.text = if (lookAlikesExpanded) "收起易混淆项（待审核）" else "展开易混淆项（待审核）"
    }

    private fun addReference(index: Int, entry: SpeciesEntry, reference: ReferenceImage): ImageRow {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "reference:$index"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.parseColor("#EEEFE7"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
        }
        val attribution = text(buildString {
            if (!reference.attributionConsistent) {
                append("参考图暂缓展示：署名或来源记录不一致，待团队复核。")
                return@buildString
            }
            appendLine("用途：${reference.role ?: "未提供"}")
            appendLine("来源：${reference.source ?: "缺失，禁止加载"}")
            append("授权：${reference.rights ?: "缺失，禁止加载"}")
            reference.sourcePage?.let { append("\n来源页面：$it") }
            reference.note?.let { append("\n备注：$it") }
        }).apply { tag = "reference-attribution:$index" }
        val image = ImageView(this).apply {
            tag = "reference-image:$index"
            layoutParams = LinearLayout.LayoutParams(-1, dp(240))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "${entry.commonName}；${reference.role ?: "参考图"}；仅用于比对照片"
            isVisible = false
        }
        val status = text(when {
            !reference.attributionConsistent -> "图片未加载：来源待复核。"
            !reference.hasAttribution -> "图片未加载：来源或授权缺失。"
            reference.file == null -> "图片未加载：离线路径无效。"
            else -> "正在加载离线参考图…"
        }).apply { tag = "reference-status:$index" }
        card.addView(attribution)
        card.addView(image)
        card.addView(status)
        reference.sourcePage?.takeIf { reference.attributionConsistent }?.let { sourcePage ->
            if (SpeciesCatalog.safeExternalUrl(sourcePage) != null) {
                card.addView(linkButton("手动打开图片来源网页（需网络）", sourcePage))
            } else {
                card.addView(text("来源页面链接未通过 HTTPS 安全检查，已禁用。"))
            }
        }
        binding.speciesImages.addView(card)
        return ImageRow(reference, image, status)
    }

    private fun linkButton(label: String, url: String) = AppCompatButton(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnClickListener { openExternal(url) }
    }

    private fun openExternal(url: String) {
        val link = SpeciesExternalLinks.intent(url)
        if (link == null) {
            showMessage("链接未通过 HTTPS 安全检查，未打开。")
            return
        }
        try {
            startActivity(link)
        } catch (_: ActivityNotFoundException) {
            showMessage("没有可用的浏览器，未打开外部链接。")
        } catch (_: SecurityException) {
            showMessage("浏览器访问被阻止，未打开外部链接。")
        }
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(Color.parseColor("#263C36"))
        typeface = Typeface.DEFAULT
        autoLinkMask = 0
        linksClickable = false
        setPadding(0, dp(8), 0, dp(8))
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun showEmpty(message: String) {
        binding.speciesLoading.isVisible = false
        binding.speciesDetails.isVisible = false
        binding.speciesReview.text = "${SpeciesCatalog.REVIEW_WARNING}\n按未知处理。"
        binding.speciesEmpty.text = message
        binding.speciesEmpty.isVisible = true
    }

    private fun showMessage(message: String) {
        binding.speciesActionMessage.text = message
        binding.speciesActionMessage.isVisible = true
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ImageRow(val reference: ReferenceImage, val image: ImageView, val status: TextView)

    companion object {
        const val EXTRA_SPECIES_ID = "com.insta360.kmpsdk.demo.species.SPECIES_ID"
        const val EXTRA_RESULT_SOURCE = "com.insta360.kmpsdk.demo.species.RESULT_SOURCE"
        private const val STATE_LOOK_ALIKES = "species.lookAlikesExpanded"

        fun intent(context: Context, speciesId: String, resultSource: String): Intent =
            Intent(context, SpeciesComparisonActivity::class.java)
                .putExtra(EXTRA_SPECIES_ID, speciesId)
                .putExtra(EXTRA_RESULT_SOURCE, resultSource)
    }
}

internal object SpeciesExternalLinks {
    fun intent(url: String): Intent? = SpeciesCatalog.safeExternalUrl(url)?.let {
        Intent(Intent.ACTION_VIEW, Uri.parse(it)).addCategory(Intent.CATEGORY_BROWSABLE)
    }
}
