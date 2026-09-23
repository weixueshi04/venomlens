package com.insta360.kmpsdk.demo

import android.view.View
import android.widget.ScrollView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import org.hamcrest.Matcher
import org.junit.Assert.assertTrue

fun scrollToContent() = object : ViewAction {
    override fun getConstraints(): Matcher<View> = withEffectiveVisibility(Visibility.VISIBLE)
    override fun getDescription() = "Scroll content coordinates into the visible viewport"
    override fun perform(uiController: UiController, view: View) {
        uiController.loopMainThreadUntilIdle()
        var parent = view.parent
        while (parent !is ScrollView && parent is View) parent = parent.parent
        val scroll = parent as? ScrollView ?: throw AssertionError("View has no ScrollView ancestor")
        val childPosition = IntArray(2)
        val scrollPosition = IntArray(2)
        repeat(2) {
            view.getLocationInWindow(childPosition)
            scroll.getLocationInWindow(scrollPosition)
            scroll.scrollTo(scroll.scrollX, scroll.scrollY + childPosition[1] - scrollPosition[1] - scroll.paddingTop)
            uiController.loopMainThreadForAtLeast(80)
        }
        assertTrue("Scrolled view is not visible", isDisplayed().matches(view))
    }
}
