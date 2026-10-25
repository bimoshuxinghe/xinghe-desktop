package app.xinghe.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.xinghe.tv.MainActivity
import app.xinghe.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

/** The network page Control Center opens first; the rest are tried if it closes at once. */
private fun networkFirst(context: Context) = SystemProbe.networkSettingsIntents(context).first()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XgimiSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val pm = shadowOf(context.packageManager)
    private val service = ComponentName("com.android.newsettings", "com.xgimi.newsettings.SettingService")

    @Test
    fun settingsSelectsExportedServiceWithoutAnyActivity() {
        pm.addServiceIfNotPresent(service).exported = true
        assertService(SystemProbe.settingsIntent(context), "Settings://com.xgimi.misckey.main/mainPage")
    }

    @Test
    fun networkSelectsExportedServiceWithoutAnyActivity() {
        pm.addServiceIfNotPresent(service).exported = true
        assertService(networkFirst(context), "Settings://com.xgimi.settings.net/wifi_misckey")
    }

    @Test
    fun absentServicePreservesEverySettingsCandidateAndItsPriority() {
        assertLegacyCandidates(settingsCandidates(), SystemProbe::settingsIntent)
    }

    @Test
    fun absentServicePreservesEveryNetworkCandidateAndItsPriority() {
        assertLegacyCandidates(networkCandidates(), ::networkFirst)
    }

    @Test
    fun unexportedServicePreservesActivityFallbacks() {
        pm.addServiceIfNotPresent(service).exported = false
        assertTrue(settingsCandidates().last().filterEquals(SystemProbe.settingsIntent(context)))
        assertTrue(networkCandidates().last().filterEquals(networkFirst(context)))
    }

    @Test
    fun serviceTakesPriorityOverResolvableActivitiesAndStartsAsService() {
        pm.addServiceIfNotPresent(service).exported = true
        val settings = settingsCandidates().first()
        val network = networkCandidates().first()
        pm.addActivityIfNotPresent(settings.component!!).exported = true
        pm.addActivityIfNotPresent(network.component!!).exported = true
        val caller = RecordingContext()
        SystemProbe.startSettingsIntent(caller, SystemProbe.settingsIntent(caller))
        SystemProbe.startSettingsIntent(caller, networkFirst(caller))
        assertEquals(2, caller.services.size)
        assertService(caller.services[0], "Settings://com.xgimi.misckey.main/mainPage")
        assertService(caller.services[1], "Settings://com.xgimi.settings.net/wifi_misckey")
        assertTrue(caller.activities.isEmpty())
    }

    @Test
    fun failedServiceFallsBackToTheOriginalActivityForEachEntry() {
        pm.addServiceIfNotPresent(service).exported = true
        val settings = settingsCandidates().first()
        val network = networkCandidates().first()
        pm.addActivityIfNotPresent(settings.component!!).exported = true
        pm.addActivityIfNotPresent(network.component!!).exported = true
        for (throws in listOf(false, true)) {
            val caller = RecordingContext(serviceResult = null, serviceThrows = throws)
            SystemProbe.startSettingsIntent(caller, SystemProbe.settingsIntent(caller))
            SystemProbe.startSettingsIntent(caller, networkFirst(caller))
            assertEquals(2, caller.services.size)
            assertEquals(2, caller.activities.size)
            assertTrue(settings.filterEquals(caller.activities[0]))
            assertTrue(network.filterEquals(caller.activities[1]))
        }
    }

    @Test
    fun absentServiceStartsTheOriginalActivity() {
        val caller = RecordingContext()
        SystemProbe.startSettingsIntent(caller, SystemProbe.settingsIntent(caller))
        SystemProbe.startSettingsIntent(caller, networkFirst(caller))
        assertTrue(caller.services.isEmpty())
        assertEquals(2, caller.activities.size)
        assertTrue(settingsCandidates().last().filterEquals(caller.activities[0]))
        assertTrue(networkCandidates().last().filterEquals(caller.activities[1]))
    }

    @Test
    fun mainActivityStartsSettingsAndNetworkAsServicesWithoutUnavailableToast() {
        pm.addServiceIfNotPresent(service).exported = true
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        for ((select, page) in listOf(
            SystemProbe::settingsIntent to "Settings://com.xgimi.misckey.main/mainPage",
            ::networkFirst to "Settings://com.xgimi.settings.net/wifi_misckey",
        )) {
            open(activity, select(activity))
            assertEquals(0, ShadowToast.shownToastCount())
            val started = shadowOf(activity).nextStartedService
            assertNotNull("MainActivity must start the settings service", started)
            assertService(started, page)
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun mainActivityShowsUnavailableToastWhenServiceAndActivityBothFail() {
        pm.addServiceIfNotPresent(service).exported = true
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val caller = RecordingContext(serviceThrows = true)
        ReflectionHelpers.setField(activity, "mBase", caller)
        listOf(SystemProbe::settingsIntent, ::networkFirst).forEachIndexed { index, select ->
            open(activity, select(activity))
            assertEquals(index + 1, caller.services.size)
            assertEquals(index + 1, ShadowToast.shownToastCount())
            assertEquals(context.getString(R.string.unavailable), ShadowToast.getTextOfLatestToast())
            assertNull(shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun pictureSettingsKeepsLegacyActivityFallbackWhenXgimiServiceIsPresent() {
        pm.addServiceIfNotPresent(service).exported = true
        val settings = settingsCandidates().first()
        pm.addActivityIfNotPresent(settings.component!!).exported = true
        val application = shadowOf(RuntimeEnvironment.getApplication())
        application.checkActivities(true)
        assertTrue(SystemProbe.openPictureSettings(context))
        assertTrue(settings.filterEquals(application.nextStartedActivity))
        assertNull(application.nextStartedService)
    }

    private fun open(activity: MainActivity, intent: Intent) {
        MainActivity::class.java.getDeclaredMethod("open", Intent::class.java).apply {
            isAccessible = true
        }.invoke(activity, intent)
    }

    private inner class RecordingContext(
        val serviceResult: ComponentName? = service,
        val serviceThrows: Boolean = false,
    ) : ContextWrapper(context) {
        val services = mutableListOf<Intent>()
        val activities = mutableListOf<Intent>()

        override fun startService(intent: Intent): ComponentName? {
            services += intent
            if (serviceThrows) throw SecurityException("Service access denied")
            return serviceResult
        }

        override fun startActivity(intent: Intent) {
            activities += intent
        }
    }

    private fun assertService(intent: Intent, page: String) {
        assertEquals(service, intent.component)
        assertEquals("com.xgimi.settings.SETTINGS", intent.action)
        assertEquals(page, intent.getStringExtra("data"))
        assertEquals("com.xgimi.misckey", intent.getStringExtra("packageName"))
        assertTrue(intent.hasExtra("unlocked"))
        assertEquals(false, intent.getBooleanExtra("unlocked", true))
        assertNull(intent.data)
    }

    private fun assertLegacyCandidates(candidates: List<Intent>, select: (Context) -> Intent) {
        val components = candidates.mapIndexed { index, intent ->
            val component = intent.component ?: ComponentName("test.settings", "test.settings.Page$index")
            pm.addActivityIfNotPresent(component).exported = true
            if (intent.component == null) {
                val filter = android.content.IntentFilter(intent.action).apply {
                    intent.data?.let { uri ->
                        addDataScheme(uri.scheme)
                        addDataAuthority(uri.host, null)
                        addDataPath(uri.path, android.os.PatternMatcher.PATTERN_LITERAL)
                    }
                }
                pm.addIntentFilterForActivity(component, filter)
            }
            component
        }
        candidates.forEachIndexed { index, expected ->
            val actual = select(context)
            assertTrue("Candidate $index: $actual", expected.filterEquals(actual))
            assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, actual.flags)
            assertNull(actual.extras)
            pm.removeActivity(components[index])
        }
        assertTrue(candidates.last().filterEquals(select(context)))
    }

    private fun settingsCandidates() = listOf(
        explicit("com.xiaomi.mitv.settings.SETTINGS", "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity"),
        Intent(Settings.ACTION_SETTINGS),
        explicit(Intent.ACTION_MAIN, "com.tcl.settings", "com.tcl.settings.ui.MainActivity"),
        explicit(Intent.ACTION_MAIN, "com.pptv.setting", "com.pptv.setting.root.SettingsActivity"),
        Intent(Intent.ACTION_VIEW, Uri.parse("Settings://com.xgimi.misckey.main/mainPage")),
        explicit(Intent.ACTION_MAIN, "com.helios.setting", "com.helios.activity.StartActivity"),
    )

    private fun networkCandidates() = listOf(
        explicit("com.xiaomi.mitv.settings.WIFI_SETTINGS", "com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity"),
        Intent(Settings.ACTION_WIFI_SETTINGS),
        explicit("com.pptv.intent.action.WIFI_ACTIVITY", "com.pptv.setting", "com.pptv.setting.wifi.WifiWorkActivity"),
        Intent(Intent.ACTION_VIEW, Uri.parse("Settings://com.xgimi.settings.net/wifi_misckey")),
        Intent(Settings.ACTION_SETTINGS),
        explicit(Intent.ACTION_MAIN, "com.tcl.settings", "com.tcl.settings.ui.MainActivity"),
        explicit(Intent.ACTION_MAIN, "com.helios.setting", "com.helios.activity.StartActivity"),
    )

    private fun explicit(action: String, pkg: String, name: String) =
        Intent(action).setComponent(ComponentName(pkg, name))
}
