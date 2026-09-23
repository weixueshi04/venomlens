package com.insta360.kmpsdk.demo.hospital

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.Spanned
import android.text.style.URLSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import com.insta360.kmpsdk.demo.scrollToContent as scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.R
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class HospitalDirectoryActivityTest {
    @Test
    fun productionAssetHasExplicitEmptyStateAndHelpEntry() = withDirectory { scenario ->
        scenario.onActivity { activity ->
            val asset = activity.assets.open(HospitalDirectory.ASSET_NAME).bufferedReader().use { it.readText() }
            val result = HospitalDirectory.parse(asset) as HospitalDirectoryResult.Loaded
            assertTrue("Production must not contain fictional hospital fixtures", result.hospitals.isEmpty())
            assertEquals(0, result.rejectedEntries)
            assertEquals(0, activity.findViewById<LinearLayout>(R.id.hospital_list).childCount)
        }
        onView(withId(R.id.hospital_notice)).check(matches(withText(
            "蛇伤救治相关医院信息；接诊及血清情况请电话确认")))
        onView(withId(R.id.hospital_empty)).perform(scrollTo()).check(matches(isDisplayed()))
            .check(matches(withText("尚无经过来源核验的医院资料，请勿依赖空目录延误求助")))
        onView(withId(R.id.hospital_directory_error)).check(matches(withEffectiveVisibility(Visibility.GONE)))
        onView(withId(R.id.hospital_emergency_dial)).perform(scrollTo())
            .check(matches(withText("中国大陆急救 120：打开拨号界面")))
        onView(withId(R.id.hospital_region_notice)).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun directoryEmergencyEntryEmitsOnlyDialIntentAndIsBlocked() = withDirectory {
        withBlockedStarts { monitor ->
            onView(withId(R.id.hospital_emergency_dial)).perform(scrollTo(), click())
            assertDialIntent(monitor, "120")
        }
    }

    @Test
    fun fictionalHospitalEntryDisplaysLiteralHistoryAndEmitsSafeDialIntent() = withDirectory { scenario ->
        scenario.onActivity { it.renderDirectory(fictionalDirectory()) }
        onView(withId(R.id.hospital_name)).perform(scrollTo())
            .check(matches(withText("测试虚构医院（非真实医疗机构）")))
        scenario.onActivity { activity ->
            val details = activity.findViewById<TextView>(R.id.hospital_details)
            assertTrue(details.text.contains("历史提及≠适配当前蛇种"))
            assertTrue(details.text.contains("不代表当前库存或接诊能力"))
            assertTrue(details.text.contains("<b>仅测试历史提及</b>"))
            assertEquals(0, details.autoLinkMask)
            assertFalse(details.linksClickable)
            val spans = (details.text as? Spanned)?.getSpans(0, details.text.length, URLSpan::class.java)
            assertTrue(spans.isNullOrEmpty())
        }
        withBlockedStarts { monitor ->
            onView(withId(R.id.hospital_dial)).perform(scrollTo(), click())
            assertDialIntent(monitor, "+12025550100")
        }
    }

    @Test
    fun copyEmergencyNumberUsesPlainSensitiveClipboardAndVisibleConfirmation() = withDirectory { scenario ->
        try {
            withBlockedStarts { monitor ->
                onView(withId(R.id.hospital_emergency_copy)).perform(scrollTo(), click())
                assertClipboard(scenario, "120")
                assertTrue(monitor.attempts.isEmpty())
            }
            onView(withId(R.id.hospital_action_message)).perform(scrollTo())
                .check(matches(withText(R.string.hospital_emergency_copied)))
        } finally {
            clearClipboard(scenario)
        }
    }

    @Test
    fun hospitalCopyButtonsCopyExactAddressAndFormattedPhoneWithoutStartingAnything() = withDirectory { scenario ->
        scenario.onActivity { it.renderDirectory(fictionalDirectory()) }
        try {
            withBlockedStarts { monitor ->
                onView(withId(R.id.hospital_copy_address)).perform(scrollTo(), click())
                assertClipboard(scenario, "测试虚构地址（请勿前往）")
                onView(withId(R.id.hospital_copy_phone)).perform(scrollTo(), click())
                assertClipboard(scenario, "+1 (202) 555-0100")
                assertTrue(monitor.attempts.isEmpty())
            }
        } finally {
            clearClipboard(scenario)
        }
    }

    @Test
    fun missingDialerHasVisibleErrorAndLeavesCopyAvailable() = withDirectory {
        withBlockedStarts(ActivityNotFoundException("test-only missing dialer")) { monitor ->
            onView(withId(R.id.hospital_emergency_dial)).perform(scrollTo(), click())
            assertDialIntent(monitor, "120")
            onView(withId(R.id.hospital_action_message)).perform(scrollTo()).check(matches(isDisplayed()))
                .check(matches(withText(R.string.hospital_no_dialer)))
            onView(withId(R.id.hospital_emergency_copy)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun blockedDialerHasVisibleError() = withDirectory {
        withBlockedStarts(SecurityException("test-only blocked dialer")) { monitor ->
            onView(withId(R.id.hospital_emergency_dial)).perform(scrollTo(), click())
            assertDialIntent(monitor, "120")
            onView(withId(R.id.hospital_action_message)).perform(scrollTo())
                .check(matches(withText(R.string.hospital_dial_blocked)))
        }
    }

    @Test
    fun readAndParseFailuresDoNotMasqueradeAsValidEmptyDirectories() = withDirectory { scenario ->
        listOf(
            HospitalDirectory.load({ throw IOException("test-only missing asset") }) to R.string.hospital_read_error,
            HospitalDirectory.parse("{") to R.string.hospital_parse_error,
        ).forEach { (result, message) ->
            scenario.onActivity { it.renderDirectory(result) }
            onView(withId(R.id.hospital_directory_error)).perform(scrollTo()).check(matches(isDisplayed()))
                .check(matches(withText(message)))
            onView(withId(R.id.hospital_empty)).check(matches(withEffectiveVisibility(Visibility.GONE)))
            onView(withId(R.id.hospital_emergency_dial)).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun rejectedEntriesHaveVisibleValidationWarningAndNoContactRows() = withDirectory { scenario ->
        scenario.onActivity { it.renderDirectory(HospitalDirectory.parse("{\"hospitals\":[{}]}")) }
        onView(withId(R.id.hospital_directory_error)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.hospital_empty)).perform(scrollTo()).check(matches(isDisplayed()))
        scenario.onActivity { assertEquals(0, it.findViewById<LinearLayout>(R.id.hospital_list).childCount) }
    }

    @Test
    fun backButtonFinishesDirectory() = withDirectory { scenario ->
        val destroyed = java.util.concurrent.CountDownLatch(1)
        scenario.onActivity { activity ->
            activity.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) destroyed.countDown()
            })
        }
        onView(withId(R.id.hospital_back)).perform(scrollTo(), click())
        assertTrue("Back did not destroy the directory", destroyed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun dialIntentFactoryRejectsUnsafeNumbersWithoutStartingActivities() {
        listOf("*123#", "tel:120", "120%23", "120;123", "https://fixture.invalid").forEach {
            try {
                HospitalContactActions.dialIntent(it)
                fail("Unsafe number accepted")
            } catch (_: IllegalArgumentException) {
                // Construction only. No startActivity call occurs in this test.
            }
        }
    }

    private fun assertDialIntent(monitor: BlockingStartMonitor, number: String) {
        assertEquals("Exactly one outgoing Activity request expected", 1, monitor.attempts.size)
        val intent = monitor.attempts.single()
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertEquals(Uri.fromParts("tel", number, null), intent.data)
        assertEquals(number, intent.data?.schemeSpecificPart)
        assertNull(intent.data?.fragment)
        assertNull(intent.component)
    }

    private fun assertClipboard(scenario: ActivityScenario<HospitalDirectoryActivity>, expected: String) {
        scenario.onActivity { activity ->
            val clipboard = requireNotNull(activity.getSystemService(ClipboardManager::class.java))
            val clip = requireNotNull(clipboard.primaryClip)
            assertEquals(1, clip.itemCount)
            assertEquals(expected, clip.getItemAt(0).text.toString())
            assertNull(clip.getItemAt(0).intent)
            assertNull(clip.getItemAt(0).uri)
            assertTrue(clip.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val outgoing = HospitalContactActions.sensitiveClip("test", expected)
                assertTrue("Outgoing clipboard payload must be marked sensitive",
                    outgoing.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true)
            }
        }
        dismissClipboardOverlay()
    }

    private fun dismissClipboardOverlay() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val info = automation.serviceInfo
        val previousFlags = info.flags
        try {
            info.flags = previousFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
            automation.windows.mapNotNull { it.root }
                .filter { it.packageName == "com.android.systemui" }
                .forEach { root ->
                    root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/dismiss_button")
                        .forEach { it.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) }
                }
        } finally {
            info.flags = previousFlags
            automation.serviceInfo = info
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun clearClipboard(scenario: ActivityScenario<HospitalDirectoryActivity>) {
        scenario.onActivity { requireNotNull(it.getSystemService(ClipboardManager::class.java)).clearPrimaryClip() }
    }

    private fun withDirectory(block: (ActivityScenario<HospitalDirectoryActivity>) -> Unit) {
        dismissClipboardOverlay()
        ActivityScenario.launch(HospitalDirectoryActivity::class.java).use { scenario ->
            onView(withId(R.id.hospital_loading)).perform(object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
                override fun getDescription() = "Wait for offline hospital asset loading"
                override fun perform(uiController: UiController, view: View) {
                    val deadline = SystemClock.uptimeMillis() + 5000
                    while (view.visibility != View.GONE && SystemClock.uptimeMillis() < deadline) {
                        uiController.loopMainThreadForAtLeast(25)
                    }
                    assertEquals("Directory load did not complete", View.GONE, view.visibility)
                }
            })
            block(scenario)
        }
    }

    private fun withBlockedStarts(
        failure: RuntimeException? = null,
        block: (BlockingStartMonitor) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = BlockingStartMonitor(failure)
        instrumentation.addMonitor(monitor)
        try {
            block(monitor)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** Intercepts ALL starts, not just ACTION_DIAL, so a regression cannot place a real call. */
    private class BlockingStartMonitor(private val failure: RuntimeException?) : Instrumentation.ActivityMonitor() {
        val attempts = mutableListOf<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
            attempts += Intent(intent)
            failure?.let { throw it }
            return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
        }
    }

    // Reserved test phone range and .invalid source; this fixture never enters production assets.
    private fun fictionalDirectory() = HospitalDirectory.parse(
        """{
          "hospitals": [{
            "hospitalId": "fictional-device-test-only",
            "name": "测试虚构医院（非真实医疗机构）",
            "address": "测试虚构地址（请勿前往）",
            "phone": "+1 (202) 555-0100",
            "source": "https://hospital-fixture.invalid/source",
            "checkedAt": "2024-02-29",
            "antivenomInfoStatus": "historical_mention",
            "antivenomNote": "<b>仅测试历史提及</b> https://note-fixture.invalid/record"
          }]
        }""".trimIndent(),
        LocalDate.of(2025, 1, 31),
    )
}
