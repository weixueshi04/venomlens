package com.insta360.kmpsdk.demo

import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import com.insta360.kmpsdk.demo.scrollToContent as scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.care.CaseRecordActivity
import com.insta360.kmpsdk.demo.recognition.MockRecognitionActivity
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MockRecognitionActivityTest {
    @Test
    fun allSevenScenariosShowDistinctOutcomes() {
        ActivityScenario.launch(MockRecognitionActivity::class.java).use {
            listOf(
                "有候选（1 个）" to "玉米蛇",
                "不确定但有多个候选" to "加州王蛇",
                "无法判断" to "画面模糊",
                "未检测到蛇" to "未检测到蛇，不代表现场安全",
                "识别处理中（202）" to "手动查询一次",
                "上游超时（504）" to "UPSTREAM_TIMEOUT",
                "模型输出违规（502）" to "INVALID_MODEL_OUTPUT",
            ).forEach { (label, expected) ->
                onView(withText(label)).perform(scrollTo(), click())
                if (expected == "手动查询一次") {
                    onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText("识别任务："))
                    onView(withId(R.id.manual_refresh)).check(matches(withText(expected)))
                } else {
                    onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText(expected))
                }
            }
        }
    }

    @Test
    fun pendingCompletesOnlyAfterManualRefresh() {
        ActivityScenario.launch(MockRecognitionActivity::class.java).use {
            onView(withText("识别处理中（202）")).perform(scrollTo(), click())
            onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText("识别任务："))
            onView(withId(R.id.manual_refresh)).perform(scrollTo(), click())
            onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText("玉米蛇"))
        }
    }

    @Test
    fun cancelledRequestCannotOverwriteResult() {
        ActivityScenario.launch(MockRecognitionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<LinearLayout>(R.id.mock_scenario_host).getChildAt(0).performClick()
                activity.findViewById<Button>(R.id.cancel_request).performClick()
            }
            onView(withId(R.id.mock_result)).perform(scrollTo(), object : ViewAction {
                override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
                override fun getDescription() = "Wait beyond simulated response delivery"
                override fun perform(uiController: UiController, view: View) {
                    uiController.loopMainThreadForAtLeast(800)
                }
            }).check(matches(withText("已取消模拟请求。")))
        }
    }

    @Test
    fun rotatingDuringRequestDoesNotDeliverIntoDestroyedActivity() {
        ActivityScenario.launch(MockRecognitionActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<LinearLayout>(R.id.mock_scenario_host).getChildAt(0).performClick()
            }
            scenario.recreate()
            onView(withId(R.id.mock_result)).perform(scrollTo())
                .check(matches(withText("选择上方场景查看模拟结果。")))
        }
    }

    @Test
    fun pendingAndFailedRecognitionDoNotBlockInjuryEntry() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(CaseRecordActivity::class.java.name,
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch(MockRecognitionActivity::class.java).use {
                listOf("识别处理中（202）" to "识别任务：", "上游超时（504）" to "UPSTREAM_TIMEOUT",
                    "未检测到蛇" to "未检测到蛇，不代表现场安全").forEach { (label, expected) ->
                    onView(withText(label)).perform(scrollTo(), click())
                    onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText(expected))
                    onView(withId(R.id.record_injury)).perform(scrollTo(), click())
                }
                onView(withId(R.id.not_bitten)).perform(scrollTo(), click())
                assertEquals(4, monitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun comparisonEntryUsesCandidateAndClearsWithNextResult() {
        ActivityScenario.launch(MockRecognitionActivity::class.java).use { scenario ->
            onView(withText("有候选（1 个）")).perform(scrollTo(), click())
            onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText("玉米蛇"))
            scenario.onActivity {
                assertEquals(1, it.findViewById<LinearLayout>(R.id.species_comparison_host).childCount)
            }
            onView(withText("玉米蛇 · 离线比对资料（非诊断）")).perform(scrollTo(), click())
            onView(withId(R.id.species_common_name)).perform(awaitText("玉米蛇"))
            onView(withId(R.id.species_result_source)).check(matches(withText("结果来源：MOCK · 模拟结果")))
            onView(withId(R.id.species_back)).perform(click())
            onView(withText("未检测到蛇")).perform(scrollTo(), click())
            onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText("未检测到蛇，不代表现场安全"))
            scenario.onActivity {
                assertEquals(0, it.findViewById<LinearLayout>(R.id.species_comparison_host).childCount)
            }
        }
    }

    private fun awaitText(expected: String) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
        override fun getDescription() = "Wait for recognition text: $expected"
        override fun perform(uiController: UiController, view: View) {
            val deadline = SystemClock.uptimeMillis() + 5000
            while (!(view as TextView).text.contains(expected) && SystemClock.uptimeMillis() < deadline) {
                uiController.loopMainThreadForAtLeast(50)
            }
            assertTrue("Missing result: $expected", view.text.contains(expected))
        }
    }
}
