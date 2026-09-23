package com.insta360.kmpsdk.demo.care

import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import com.insta360.kmpsdk.demo.scrollToContent as scrollTo
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.R
import org.hamcrest.Matcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaseRecordActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun rebuildingPreservesInjuryAndUserComparisonAfterRecognitionFailure() {
        val snapshot = "【MOCK · 识别失败】\n错误码：UPSTREAM_TIMEOUT"
        val intent = CaseRecordActivity.intent(context, null, null, snapshot, arrayListOf("候选甲", "候选乙"), true)
        ActivityScenario.launch<CaseRecordActivity>(intent).use { scenario ->
            awaitReady()
            scenario.onActivity { activity ->
                assertEquals(0, activity.findViewById<Spinner>(R.id.case_candidate_spinner).selectedItemPosition)
                activity.findViewById<EditText>(R.id.case_bite_time).setText("合成测试：约10分钟前")
                activity.findViewById<EditText>(R.id.case_body_part).setText("合成测试：左脚踝")
                activity.findViewById<EditText>(R.id.case_symptoms).setText("合成测试：局部不适")
                activity.findViewById<EditText>(R.id.case_age).setText("30")
                activity.findViewById<EditText>(R.id.case_blood_type).setText("A+")
                activity.findViewById<EditText>(R.id.case_emergency_name).setText("张三")
                activity.findViewById<EditText>(R.id.case_emergency_phone).setText("13800138000")
                activity.findViewById<EditText>(R.id.case_emergency_relationship).setText("配偶")
                activity.findViewById<EditText>(R.id.case_location).setText("合成测试地点")
                activity.findViewById<Spinner>(R.id.case_candidate_spinner).setSelection(2)
            }
            instrumentation.waitForIdleSync()
            scenario.recreate()
            awaitReady()
            scenario.onActivity { activity ->
                assertEquals("合成测试：约10分钟前", activity.findViewById<EditText>(R.id.case_bite_time).text.toString())
                assertEquals("合成测试：左脚踝", activity.findViewById<EditText>(R.id.case_body_part).text.toString())
                assertEquals("合成测试：局部不适", activity.findViewById<EditText>(R.id.case_symptoms).text.toString())
                assertEquals("30", activity.findViewById<EditText>(R.id.case_age).text.toString())
                assertEquals("A+", activity.findViewById<EditText>(R.id.case_blood_type).text.toString())
                assertEquals("张三", activity.findViewById<EditText>(R.id.case_emergency_name).text.toString())
                assertEquals("13800138000", activity.findViewById<EditText>(R.id.case_emergency_phone).text.toString())
                assertEquals("配偶", activity.findViewById<EditText>(R.id.case_emergency_relationship).text.toString())
                assertEquals("合成测试地点", activity.findViewById<EditText>(R.id.case_location).text.toString())
                assertEquals(2, activity.findViewById<Spinner>(R.id.case_candidate_spinner).selectedItemPosition)
                assertTrue(activity.findViewById<TextView>(R.id.case_recognition_snapshot).text.contains(snapshot))
                assertTrue(activity.findViewById<Button>(R.id.case_save).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.case_back).isEnabled)
                assertTrue(activity.findViewById<Button>(R.id.case_hospitals).isEnabled)
            }
        }
    }

    @Test
    fun unbittenWithNoResultStillAllowsRecordingAndAlwaysWarnsAboutSafety() {
        ActivityScenario.launch<CaseRecordActivity>(
            CaseRecordActivity.intent(context, null, null, "", arrayListOf(), false),
        ).use { scenario ->
            awaitReady()
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.case_branch_message).text.contains("保持距离"))
                assertTrue(activity.findViewById<TextView>(R.id.case_branch_message).text.contains("不等于安全"))
                assertEquals(View.GONE, activity.findViewById<View>(R.id.case_bite_fields).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.case_medical_reminder).visibility)
                assertTrue(activity.findViewById<TextView>(R.id.case_medical_reminder).text.contains("不生成诊断"))
                assertTrue(activity.findViewById<Button>(R.id.case_save).isEnabled)
                assertEquals(0, activity.findViewById<Spinner>(R.id.case_candidate_spinner).selectedItemPosition)
            }
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun entryUsesUriRatherThanImageBytesAndLimitsCandidates() {
        val uri = Uri.parse("content://synthetic.test/photo/1")
        val intent = CaseRecordActivity.intent(context, uri, "2026-09-01T00:00:00Z", "pending", arrayListOf("甲", "乙", "丙", "丁"), true)
        assertEquals(uri, intent.getParcelableExtra<Uri>(CaseRecordActivity.EXTRA_URI))
        assertEquals(3, intent.getStringArrayListExtra(CaseRecordActivity.EXTRA_LABELS)!!.size)
        intent.extras!!.keySet().forEach { assertFalse(intent.extras!!.get(it) is ByteArray) }
        assertEquals(CaseRecordActivity::class.java.name, intent.component!!.className)
        assertTrue(CaseRecordActivity.latestIntent(context).getBooleanExtra(CaseRecordActivity.EXTRA_LATEST, false))
    }

    private fun awaitReady() {
        onView(withId(R.id.case_save)).perform(scrollTo(), object : ViewAction {
            override fun getConstraints(): Matcher<View> = isAssignableFrom(Button::class.java)
            override fun getDescription() = "Wait for local record initialization"
            override fun perform(uiController: UiController, view: View) {
                val deadline = SystemClock.uptimeMillis() + 5000
                while (!view.isEnabled && SystemClock.uptimeMillis() < deadline) {
                    uiController.loopMainThreadForAtLeast(50)
                }
                assertTrue("Local record form did not become ready", view.isEnabled)
            }
        })
    }
}
