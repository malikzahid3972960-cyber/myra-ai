package com.example.device

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AssistantLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PhoneAppBridgeTest {

    private lateinit var context: Application
    private lateinit var bridge: PhoneAppBridge

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        registerLaunchableApp("org.telegram.messenger", "Telegram", "org.telegram.ui.LaunchActivity")
        registerLaunchableApp("com.whatsapp", "WhatsApp", "com.whatsapp.Main")
        registerLaunchableApp("com.google.android.youtube", "YouTube", "com.google.android.youtube.HomeActivity")
        bridge = PhoneAppBridge(context)
    }

    private fun registerLaunchableApp(packageName: String, label: String, activityName: String) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(packageName, activityName)
        shadowPm.addActivityIfNotPresent(component)
        shadowPm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
        )
        shadowPm.getInternalMutablePackageInfo(packageName).applicationInfo?.apply {
            nonLocalizedLabel = label
            name = label
        }
    }

    @Test
    fun getAllInstalledApps_listsAllInstalledAppsAndPackageNames() {
        val apps = bridge.getAllInstalledApps()
        val packages = apps.map { it.packageName }
        assertTrue(packages.contains("org.telegram.messenger"))
        assertTrue(packages.contains("com.whatsapp"))
        assertTrue(packages.contains("com.google.android.youtube"))
    }

    @Test
    fun openApp_returnsTrueAndLaunchesInstalledAppsImmediately() {
        registerLaunchableApp("com.facebook.katana", "Facebook", "com.facebook.katana.LoginActivity")

        assertTrue(bridge.openApp("open Facebook"))
        val fbIntent = shadowOf(context).nextStartedActivity
        assertNotNull(fbIntent)
        assertEquals("com.facebook.katana", fbIntent.component?.packageName)

        assertTrue(bridge.openApp("Telegram"))
        val tgIntent = shadowOf(context).nextStartedActivity
        assertNotNull(tgIntent)
        assertEquals("org.telegram.messenger", tgIntent.component?.packageName)
    }

    @Test
    fun openAppWithResult_stripsFillers_matchesUrduAndAliases_andUsesExactReplyMessages() {
        registerLaunchableApp("com.facebook.katana", "Facebook", "com.facebook.katana.LoginActivity")
        registerLaunchableApp("com.facebook.orca", "Messenger", "com.facebook.orca.MainActivity")
        registerLaunchableApp("com.instagram.android", "Instagram", "com.instagram.mainactivity.LauncherActivity")
        registerLaunchableApp("com.zhiliaoapp.musically", "TikTok", "com.ss.android.ugc.aweme.splash.SplashActivity")
        registerLaunchableApp("com.android.chrome", "Chrome", "com.google.android.apps.chrome.Main")
        registerLaunchableApp("com.android.settings", "Settings", "com.android.settings.Settings")
        registerLaunchableApp("com.google.android.calculator", "Calculator", "com.android.calculator2.Calculator")
        registerLaunchableApp("com.google.android.apps.photos", "Photos", "com.google.android.apps.photos.home.HomeActivity")
        registerLaunchableApp("com.google.android.gm", "Gmail", "com.google.android.gm.ConversationListActivityGmail")
        registerLaunchableApp("com.google.android.apps.maps", "Maps", "com.google.android.maps.MapsActivity")
        registerLaunchableApp("org.mozilla.firefox", "Firefox", "org.mozilla.firefox.App")

        val expectedSuccess = listOf(
            "please open Facebook app" to ("com.facebook.katana" to "OK, opening Facebook."),
            "اوپن فیس بک جلدی" to ("com.facebook.katana" to "OK, opening Facebook."),
            "fb" to ("com.facebook.katana" to "OK, opening Facebook."),
            "messenger" to ("com.facebook.orca" to "OK, opening Messenger."),
            "یوٹیوب کھولو" to ("com.google.android.youtube" to "OK, opening YouTube."),
            "انسٹاگرام" to ("com.instagram.android" to "OK, opening Instagram."),
            "insta" to ("com.instagram.android" to "OK, opening Instagram."),
            "ٹیلیگرام کھولیں" to ("org.telegram.messenger" to "OK, opening Telegram."),
            "واٹس ایپ چلاؤ" to ("com.whatsapp" to "OK, opening WhatsApp."),
            "tik tok" to ("com.zhiliaoapp.musically" to "OK, opening TikTok."),
            "ٹک ٹاک" to ("com.zhiliaoapp.musically" to "OK, opening TikTok."),
            "کروم" to ("com.android.chrome" to "OK, opening Chrome."),
            "سیٹنگز" to ("com.android.settings" to "OK, opening Settings."),
            "کیلکولیٹر" to ("com.google.android.calculator" to "OK, opening Calculator."),
            "گیلری" to ("com.google.android.apps.photos" to "OK, opening Photos."),
            "جی میل" to ("com.google.android.gm" to "OK, opening Gmail."),
            "میپس" to ("com.google.android.apps.maps" to "OK, opening Maps."),
            "open firefox" to ("org.mozilla.firefox" to "OK, opening Firefox.")
        )

        for ((query, expected) in expectedSuccess) {
            val result = bridge.openAppWithResult(query)
            assertTrue("Expected success for '$query' but got: ${result.verificationMessage}", result.success)
            assertEquals("Package mismatch for '$query'", expected.first, result.matchedPackageName)
            assertEquals("Reply mismatch for '$query'", expected.second, result.verificationMessage)
        }
    }

    @Test
    fun openAppWithResult_aliasPackageFallbackWorksEvenWhenLabelDiffers() {
        registerLaunchableApp("com.facebook.katana", "Meta App", "com.facebook.katana.LoginActivity")

        val result = bridge.openAppWithResult("open facebook")
        assertTrue(result.success)
        assertEquals("com.facebook.katana", result.matchedPackageName)
        assertEquals("OK, opening Meta App.", result.verificationMessage)
    }

    @Test
    fun openAppWithResult_multipleContainsMatches_picksShortestLabel() {
        registerLaunchableApp("com.facebook.lite", "Facebook Lite", "com.facebook.lite.MainActivity")
        registerLaunchableApp("com.facebook.messenger", "Facebook Messenger", "com.facebook.messenger.MainActivity")
        registerLaunchableApp("com.calc.sci", "Scientific Calculator", "com.calc.SciActivity")
        registerLaunchableApp("com.calc.app", "Calculator", "com.calc.MainActivity")

        val fbResult = bridge.openAppWithResult("facebook")
        assertTrue(fbResult.success)
        assertEquals("com.facebook.lite", fbResult.matchedPackageName)
        assertEquals("OK, opening Facebook Lite.", fbResult.verificationMessage)

        val calcResult = bridge.openAppWithResult("calc")
        assertTrue(calcResult.success)
        assertEquals("com.calc.app", calcResult.matchedPackageName)
        assertEquals("OK, opening Calculator.", calcResult.verificationMessage)
    }

    @Test
    fun openAppWithResult_notFound_returnsExactNotFoundMessage() {
        val result = bridge.openAppWithResult("open Spotify")
        assertFalse(result.success)
        assertEquals(
            "I could not find Spotify among installed apps. Installed apps list is in Settings.",
            result.verificationMessage
        )
    }

    @Test
    fun openAppWithResult_telegramRemoved_returnsNotFoundMessage() {
        shadowOf(context.packageManager).removePackage("org.telegram.messenger")
        val result = bridge.openAppWithResult("telegram")
        assertFalse(result.success)
        assertEquals(
            "I could not find telegram among installed apps. Installed apps list is in Settings.",
            result.verificationMessage
        )
    }

    @Test
    fun openAppWithResult_nonLaunchablePackage_reportsNotLaunchable() {
        val pkgInfo = PackageInfo().apply {
            packageName = "com.test.nonlaunchable"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.test.nonlaunchable"
                nonLocalizedLabel = "Hidden Service"
                name = "Hidden Service"
            }
        }
        shadowOf(context.packageManager).installPackage(pkgInfo)

        val result = bridge.openAppWithResult("Hidden Service")
        assertFalse(result.success)
        assertTrue(result.verificationMessage.contains("not launchable"))
    }

    @Test
    fun handlePromptCommand_generalAndDateQuestions_doNotTriggerAppLaunch() {
        assertNull(bridge.handlePromptCommand("مصنوعی ذہانت کیا ہے؟"))
        assertNull(bridge.handlePromptCommand("What is today's date?"))
        assertNull(bridge.handlePromptCommand("آج کی تاریخ کیا ہے؟"))
    }

    @Test
    fun deviceDateTimeHelper_answersTodayDateInEnglishAndUrduWithoutCalendarRefusal() {
        val fixedCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 16, 14, 30, 0)
        }
        val millis = fixedCal.timeInMillis

        val enReply = DeviceDateTimeHelper.resolveDirectDateTimeQuery(
            prompt = "What is today's date?",
            preferredLanguage = AssistantLanguage.URDU,
            nowMillis = millis
        )
        assertNotNull(enReply)
        assertTrue(enReply!!.contains("November 16, 2026"))

        val urReply = DeviceDateTimeHelper.resolveDirectDateTimeQuery(
            prompt = "آج کی تاریخ کیا ہے؟",
            preferredLanguage = AssistantLanguage.URDU,
            nowMillis = millis
        )
        assertNotNull(urReply)
        assertTrue(urReply!!.contains("16 نومبر 2026"))

        val contextString = DeviceDateTimeHelper.buildLiveClockContext(millis)
        assertTrue(contextString.contains("2026-11-16"))
    }
}
