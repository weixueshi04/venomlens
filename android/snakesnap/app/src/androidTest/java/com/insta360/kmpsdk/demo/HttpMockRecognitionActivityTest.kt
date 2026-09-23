package com.insta360.kmpsdk.demo

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import com.insta360.kmpsdk.demo.scrollToContent as scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.insta360.kmpsdk.demo.recognition.MockRecognitionActivity
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HttpMockRecognitionActivityTest {
    @Test
    fun cleartextPolicyAllowsLoopbackAndCameraButNotOtherHosts() {
        val policy = android.security.NetworkSecurityPolicy.getInstance()
        listOf("127.0.0.1", "localhost", "::1", "192.168.42.1", "aware.insta360.camera").forEach {
            assertTrue("Expected local HTTP permission for $it", policy.isCleartextTrafficPermitted(it))
        }
        listOf("example.com", "192.168.1.2", "127.0.0.1.example.com").forEach {
            org.junit.Assert.assertFalse("Unexpected HTTP permission for $it", policy.isCleartextTrafficPermitted(it))
        }
    }

    @Test
    fun selectedImageConsentSevenHttpScenariosAndExplicitRefresh() {
        val proxyUrl = InstrumentationRegistry.getArguments().getString("mockProxyUrl")
        assumeNotNull(proxyUrl)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "http-mock-${UUID.randomUUID()}.jpg")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)) }
        } finally {
            bitmap.recycle()
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val pickerFilter = IntentFilter(Intent.ACTION_GET_CONTENT).apply {
            addDataType("image/*")
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        val picker = instrumentation.addMonitor(pickerFilter,
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri)), true)
        try {
            ActivityScenario.launch(MockRecognitionActivity::class.java).use {
                onView(withId(R.id.select_image)).perform(scrollTo(), click())
                onView(withId(R.id.image_status)).perform(scrollTo(), awaitText("已准备 JPEG"))
                onView(withId(R.id.use_http_mock)).perform(scrollTo(), click())
                onView(withId(R.id.proxy_base_url)).perform(scrollTo(), replaceText(proxyUrl!!), closeSoftKeyboard())
                onView(withText("有候选（1 个）")).perform(scrollTo(), click())
                onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText("未同意发送，未创建图片请求"))
                onView(withId(R.id.record_injury)).perform(scrollTo()).check(matches(isDisplayed()))
                onView(withId(R.id.upload_consent)).perform(scrollTo(), click())
                listOf(
                    "有候选（1 个）" to "玉米蛇",
                    "不确定但有多个候选" to "加州王蛇",
                    "无法判断" to "画面模糊",
                    "未检测到蛇" to "未检测到蛇，不代表现场安全",
                    "上游超时（504）" to "UPSTREAM_TIMEOUT",
                    "模型输出违规（502）" to "INVALID_MODEL_OUTPUT",
                    "识别处理中（202）" to "识别任务：",
                ).forEach { (label, expected) ->
                    onView(withText(label)).perform(scrollTo(), click())
                    onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText(expected), awaitText("MOCK"))
                }
                onView(withId(R.id.mock_result)).perform(object : ViewAction {
                    override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
                    override fun getDescription() = "Check pending remains pending beyond the proxy's default idle timeout"
                    override fun perform(uiController: UiController, view: View) {
                        uiController.loopMainThreadForAtLeast(7000)
                        assertTrue((view as TextView).text.contains("识别任务："))
                    }
                })
                onView(withId(R.id.manual_refresh)).perform(scrollTo(), click())
                onView(withId(R.id.mock_result)).perform(scrollTo(), awaitText("玉米蛇"), awaitText("MOCK"))
            }
        } finally {
            instrumentation.removeMonitor(picker)
            file.delete()
        }
    }

    private fun awaitText(expected: String) = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isAssignableFrom(TextView::class.java)
        override fun getDescription() = "Wait for HTTP mock text: $expected"
        override fun perform(uiController: UiController, view: View) {
            val deadline = SystemClock.uptimeMillis() + 6000
            while (!(view as TextView).text.contains(expected) && SystemClock.uptimeMillis() < deadline) {
                uiController.loopMainThreadForAtLeast(50)
            }
            assertTrue("Missing HTTP result: $expected", view.text.contains(expected))
        }
    }
}
