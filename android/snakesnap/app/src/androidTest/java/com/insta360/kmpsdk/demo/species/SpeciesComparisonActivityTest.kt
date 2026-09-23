package com.insta360.kmpsdk.demo.species

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import com.insta360.kmpsdk.demo.scrollToContent as scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.R
import com.insta360.kmpsdk.demo.hospital.HospitalDirectoryActivity
import org.hamcrest.CoreMatchers.`is`
import org.hamcrest.Matcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpeciesComparisonActivityTest {
    @Test
    fun unknownIdShowsEmptyStateSafetyAndUsableHospitalLink() = withScreen("missing_species") { scenario, monitor ->
        onView(withId(R.id.species_empty)).check(matches(isDisplayed()))
        onView(withId(R.id.species_details)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.species_safety)).check(matches(withText(SpeciesCatalog.SAFETY_WARNING)))
        onView(withId(R.id.species_result_source)).check(matches(withText(ComparisonResultSource.MOCK.badge)))
        onView(withId(R.id.species_hospital)).perform(click())
        assertEquals(HospitalDirectoryActivity::class.java.name, monitor.attempts.single().component?.className)
        scenario.onActivity { activity ->
            assertFalse(activity.packageManager.getActivityInfo(
                ComponentName(activity, SpeciesComparisonActivity::class.java), 0,
            ).exported)
            assertTrue(activity.findViewById<TextView>(R.id.species_review).text.contains(SpeciesCatalog.REVIEW_WARNING))
        }
    }

    @Test
    fun actualEmptyProfileDoesNotInventDescriptionOrReferenceImages() = withScreen("lampropeltis_californiae") { _, _ ->
        onView(withId(R.id.species_common_name)).check(matches(withText("加州王蛇")))
        onView(withId(R.id.species_scientific_name)).check(matches(withText("Lampropeltis californiae")))
        onView(withId(R.id.species_profile_empty)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.species_profile)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withText("暂无带来源和授权的离线参考图。")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun emptyObjectProfileDoesNotFallBackToEnglishOrTechnicalNotes() = withScreen { scenario, _ ->
        scenario.onActivity { activity ->
            val json = originalEntry(activity, "pseudagkistrodon_rudis")
                .put("comparisonProfile", JSONObject().put("techNotes", "MUST_NOT_RENDER_TECH"))
                .put("englishCommonName", "MUST_NOT_RENDER_ENGLISH")
            activity.renderCatalog(SpeciesCatalog.parse(JSONArray().put(json).toString()))
        }
        waitForLoad()
        onView(withId(R.id.species_profile_empty)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.species_profile)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        scenario.onActivity { activity ->
            val texts = allText(activity.findViewById(R.id.species_scroll))
            assertFalse(texts.contains("MUST_NOT_RENDER"))
        }
    }

    @Test
    fun bundledReferencesShowExactAttributionEvenWhenFileIsMissing() = withScreen { scenario, _ ->
        scenario.onActivity { activity ->
            val catalog = bundledCatalog(activity)
            assertEquals(6, catalog.entries.size)
            val entry = requireNotNull(catalog.find("pseudagkistrodon_rudis"))
            assertEquals(2, entry.referenceImages.size)
            assertTrue(activity.findViewById<TextView>(R.id.species_aliases).text.contains("伪腹蛇"))
            assertTrue(activity.findViewById<TextView>(R.id.species_review).text.contains("不表示医疗或描述资料已经审核"))
            val cards = activity.findViewById<LinearLayout>(R.id.species_images)
            entry.referenceImages.forEachIndexed { index, reference ->
                val attribution = cards.findViewWithTag<TextView>("reference-attribution:$index")
                if (!reference.attributionConsistent) {
                    assertTrue(attribution.text.contains("来源记录不一致"))
                    assertFalse(attribution.text.contains("haocong_cat"))
                    assertNull(cards.findViewWithTag<ImageView>("reference-image:$index").drawable)
                    return@forEachIndexed
                }
                assertTrue(attribution.text.contains(requireNotNull(reference.role)))
                assertTrue(attribution.text.contains(requireNotNull(reference.source)))
                assertTrue(attribution.text.contains(requireNotNull(reference.rights)))
                assertTrue(attribution.text.contains(requireNotNull(reference.sourcePage)))
                assertEquals(0, attribution.autoLinkMask)
                assertFalse(attribution.linksClickable)
                val image = cards.findViewWithTag<ImageView>("reference-image:$index")
                val status = cards.findViewWithTag<TextView>("reference-status:$index")
                val bitmap = (image.drawable as? BitmapDrawable)?.bitmap
                if (bitmap == null) {
                    assertEquals(View.GONE, image.visibility)
                    assertEquals(View.VISIBLE, status.visibility)
                    assertTrue(status.text.contains("图片缺失或无法解码"))
                } else {
                    assertEquals(View.VISIBLE, image.visibility)
                    assertEquals(View.GONE, status.visibility)
                    assertTrue(bitmap.width <= 1024 && bitmap.height <= 1024)
                }
            }
        }
    }

    @Test
    fun onlyConsistentReferenceImagesAreBundledAndDecode() = withScreen { scenario, _ ->
        scenario.onActivity { activity ->
            val catalog = bundledCatalog(activity)
            val all = catalog.entries.flatMap { entry -> entry.referenceImages.map { entry to it } }
            assertEquals(7, all.count { it.second.attributionConsistent })
            assertEquals(2, all.count { !it.second.attributionConsistent })
            all.forEach { (entry, reference) ->
                if (reference.attributionConsistent) {
                    val bitmap = OfflineReferenceImages.decode(activity.assets, entry.speciesId, reference, 8 * 1024 * 1024)
                    assertNotNull("Packaged image must decode: ${reference.file}", bitmap)
                    bitmap?.recycle()
                } else {
                    val exists = runCatching { activity.assets.open(requireNotNull(reference.file)).use { } }.isSuccess
                    assertFalse("Conflicting attribution image must not be packaged", exists)
                }
            }
        }
    }

    @Test
    fun teamOwnedLabelAndDraftWarningRemainVisible() = withScreen("pantherophis_guttatus") { scenario, _ ->
        scenario.onActivity { activity ->
            val attribution = activity.findViewById<LinearLayout>(R.id.species_images)
                .findViewWithTag<TextView>("reference-attribution:0")
            assertTrue(attribution.text.contains("团队自有"))
            assertTrue(attribution.text.contains("团队 2026-09-19 自贡调研实拍"))
            assertTrue(activity.findViewById<TextView>(R.id.species_review).text.contains(SpeciesCatalog.DRAFT_WARNING))
        }
    }

    @Test
    fun missingFileKeepsAttributionAndExplicitPlaceholder() = withScreen { scenario, _ ->
        scenario.onActivity { activity ->
            val json = originalEntry(activity, "pseudagkistrodon_rudis")
            val image = json.getJSONArray("referenceImages").getJSONObject(0)
                // 合法白名单路径（发布层 card_images），但确实没有被打包进 assets：验证「缺图仍保留署名 + 显式占位」
                .put("file", "${SpeciesCatalog.CARD_IMAGE_ROOT}/pseudagkistrodon_rudis/test_missing_never_bundled.jpg")
                .put("attributionConsistent", true)
            json.put("referenceImages", JSONArray().put(image))
            activity.renderCatalog(SpeciesCatalog.parse(JSONArray().put(json).toString()))
        }
        waitForLoad()
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.species_images)
            assertNull(cards.findViewWithTag<ImageView>("reference-image:0").drawable)
            assertTrue(cards.findViewWithTag<TextView>("reference-status:0").text.contains("图片缺失或无法解码"))
            assertTrue(cards.findViewWithTag<TextView>("reference-attribution:0").text.contains("作者 haocong_cat"))
        }
    }

    @Test
    fun missingRightsRefusesLoadingEvenForRealBundledPath() = withScreen { scenario, _ ->
        scenario.onActivity { activity ->
            val json = originalEntry(activity, "pseudagkistrodon_rudis")
            val image = json.getJSONArray("referenceImages").getJSONObject(0).apply {
                remove("rights")
                put("attributionConsistent", true)
            }
            json.put("referenceImages", JSONArray().put(image))
            activity.renderCatalog(SpeciesCatalog.parse(JSONArray().put(json).toString()))
        }
        waitForLoad()
        scenario.onActivity { activity ->
            val cards = activity.findViewById<LinearLayout>(R.id.species_images)
            assertNull(cards.findViewWithTag<ImageView>("reference-image:0").drawable)
            assertTrue(cards.findViewWithTag<TextView>("reference-status:0").text.contains("来源或授权缺失"))
            assertTrue(cards.findViewWithTag<TextView>("reference-attribution:0").text.contains("iNaturalist"))
        }
    }

    @Test
    fun mockSourceAndExpandedLookAlikesSurviveRecreationUsingOnlySmallExtras() =
        withScreen("gloydius_brevicaudus") { scenario, _ ->
            onView(withId(R.id.species_result_source)).check(matches(isDisplayed()))
                .check(matches(withText(ComparisonResultSource.MOCK.badge)))
            onView(withId(R.id.species_look_alikes)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.species_look_alikes_toggle)).perform(scrollTo(), click())
            onView(withId(R.id.species_look_alikes)).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.recreate()
            waitForLoad()
            onView(withId(R.id.species_result_source)).check(matches(withText(ComparisonResultSource.MOCK.badge)))
            onView(withId(R.id.species_look_alikes)).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.onActivity { activity ->
                val extras = requireNotNull(activity.intent.extras)
                assertEquals(setOf(SpeciesComparisonActivity.EXTRA_SPECIES_ID, SpeciesComparisonActivity.EXTRA_RESULT_SOURCE), extras.keySet())
                assertEquals("gloydius_brevicaudus", extras.getString(SpeciesComparisonActivity.EXTRA_SPECIES_ID))
                assertEquals("MOCK", extras.getString(SpeciesComparisonActivity.EXTRA_RESULT_SOURCE))
                assertTrue(activity.findViewById<TextView>(R.id.species_review).text.contains(SpeciesCatalog.DRAFT_WARNING))
            }
        }

    @Test
    fun nullAndUnrecognizedSourcesNeverDisplayLive() {
        listOf(null, "unrecognized").forEach { source ->
            withScreen(source = source) { scenario, _ ->
                onView(withId(R.id.species_result_source)).check(matches(withText(ComparisonResultSource.UNKNOWN.badge)))
                scenario.recreate()
                waitForLoad()
                onView(withId(R.id.species_result_source)).check(matches(withText(ComparisonResultSource.UNKNOWN.badge)))
            }
        }
    }

    @Test
    fun cacheSourceRemainsDistinctFromLive() = withScreen(source = "CACHE") { _, _ ->
        onView(withId(R.id.species_result_source)).check(matches(withText(ComparisonResultSource.CACHE.badge)))
    }

    @Test
    fun externalLinkStartsOnlyOnTapAndIsInterceptedBeforeAnyBrowserLaunch() = withScreen { scenario, monitor ->
        val url = firstExternalUrl(scenario)
        assertTrue(monitor.attempts.isEmpty())
        onView(withTagValue(`is`<Any>("external-ref:$url"))).perform(scrollTo(), click())
        val outgoing = monitor.attempts.single()
        assertEquals(Intent.ACTION_VIEW, outgoing.action)
        assertEquals(setOf(Intent.CATEGORY_BROWSABLE), outgoing.categories)
        assertEquals(url, outgoing.dataString)
        assertNull(outgoing.component)
        assertNull(outgoing.`package`)
        assertNull(outgoing.extras)
    }

    @Test
    fun unsafeExternalIntentFactoryDoesNotProduceLaunchableIntents() {
        listOf("http://example.invalid", "https://user@example.invalid", "https://example.invalid/%0a",
            "intent://example.invalid", "https://example.invalid\\evil").forEach {
            assertNull(SpeciesExternalLinks.intent(it))
        }
        assertNotNull(SpeciesExternalLinks.intent("https://example.invalid"))
    }

    @Test
    fun topBackFinishesEvenForMissingSpecies() = withScreen("missing_species") { scenario, _ ->
        val destroyed = CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
            })
        }
        onView(withId(R.id.species_back)).perform(click())
        assertTrue(destroyed.await(5, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    private fun withScreen(
        speciesId: String = "pseudagkistrodon_rudis",
        source: String? = "MOCK",
        failure: RuntimeException? = null,
        block: (ActivityScenario<SpeciesComparisonActivity>, BlockingStarts) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = BlockingStarts(failure)
        instrumentation.addMonitor(monitor)
        try {
            val intent = SpeciesComparisonActivity.intent(instrumentation.targetContext, speciesId, source ?: "UNKNOWN")
            if (source == null) intent.removeExtra(SpeciesComparisonActivity.EXTRA_RESULT_SOURCE)
            ActivityScenario.launch<SpeciesComparisonActivity>(intent).use { scenario ->
                waitForLoad()
                assertTrue("No automatic outgoing Activity is allowed", monitor.attempts.isEmpty())
                block(scenario, monitor)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun waitForLoad() {
        onView(withId(R.id.species_loading)).perform(object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
            override fun getDescription() = "Wait for offline catalog and bounded reference decoding"
            override fun perform(uiController: UiController, view: View) {
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (view.visibility != View.GONE && SystemClock.uptimeMillis() < deadline) {
                    uiController.loopMainThreadForAtLeast(25)
                }
                assertEquals("Offline comparison did not finish loading", View.GONE, view.visibility)
            }
        })
    }

    private fun bundledCatalog(activity: Activity): SpeciesCatalog = SpeciesCatalog.parse(
        activity.assets.open(SpeciesCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() },
    )

    private fun originalEntry(activity: Activity, id: String): JSONObject {
        val json = activity.assets.open(SpeciesCatalog.ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }.single { it.getString("speciesId") == id }
    }

    private fun firstExternalUrl(scenario: ActivityScenario<SpeciesComparisonActivity>): String {
        var url = ""
        scenario.onActivity { url = requireNotNull(bundledCatalog(it).find("pseudagkistrodon_rudis")).externalRefs.first().url }
        return url
    }

    private fun allText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is android.view.ViewGroup -> (0 until view.childCount).joinToString("\n") { allText(view.getChildAt(it)) }
        else -> ""
    }

    private class BlockingStarts(private val failure: RuntimeException?) : Instrumentation.ActivityMonitor() {
        val attempts = mutableListOf<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.component?.className == SpeciesComparisonActivity::class.java.name) return null
            attempts += Intent(intent)
            failure?.let { throw it }
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }
}
