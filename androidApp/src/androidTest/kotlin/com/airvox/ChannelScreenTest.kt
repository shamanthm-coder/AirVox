package com.airvox

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasFocus
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withClassName
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.endsWith
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelScreenTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun joinWithoutSignalingSetupExplainsWhatIsMissing() {
        onView(withText("Room code")).perform(click())
        onView(allOf(withClassName(endsWith("EditText")), hasFocus())).perform(
            typeText("ride01"),
            closeSoftKeyboard(),
        )
        onView(withText("Password")).perform(click())
        onView(allOf(withClassName(endsWith("EditText")), hasFocus())).perform(
            typeText("secret1"),
            closeSoftKeyboard(),
        )
        onView(withText("Join channel")).perform(click())
        onView(withText(containsString("Bluetooth"))).check(matches(isDisplayed()))
    }
}
