package app.xinghe.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import app.xinghe.tv.dream.AerialDream

/**
 * The TV's own screensaver: Android's dream where the firmware uses it (TCL, most Android TVs), or
 * the maker's own app where it runs outside it (Xiaomi HyperOS).
 */
object SystemScreensaver {
    private const val TAG = "AerialScreensaver"
    private const val COMPONENTS = "screensaver_components"
    private const val DEFAULT_COMPONENT = "screensaver_default_component"

    /** Xiaomi's screensaver, which HyperOS starts by its own timer (screen_saver_timeout), not as a dream. */
    const val XIAOMI = "com.xiaomi.mitv.hyper.screensaver"

    /** Android's dream that starts on demand (what "Start now" in Android's settings opens). */
    private val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")

    private fun dreamsOn(context: Context) =
        runCatching { Settings.Secure.getInt(context.contentResolver, "screensaver_enabled", 0) == 1 }.getOrDefault(false)

    private fun component(context: Context): String =
        runCatching { Settings.Secure.getString(context.contentResolver, COMPONENTS) }.getOrNull().orEmpty()

    /** Aerial's own dream is the TV's screensaver (tools/install.sh sets it where dreams are on). */
    fun isAerials(context: Context): Boolean =
        dreamsOn(context) && component(context).split(',').firstOrNull() == ComponentName(context, AerialDream::class.java).flattenToString()

    /** The dream the firmware came with, or null where it has none on record. */
    fun firmwareDream(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, DEFAULT_COMPONENT) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith(context.packageName + "/") }

    /** Starts the TV's dream now, whichever it is; false where that is not possible. */
    fun startDream(context: Context): Boolean {
        if (!dreamsOn(context)) return false
        val intent = Intent(Intent.ACTION_MAIN).setComponent(SOMNAMBULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() == null) return false
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not start the dream", it) }.isSuccess
    }

    /**
     * Starts the firmware's own screensaver now, for Back with "System" chosen: Xiaomi's app where it
     * is installed, else the dream. False where neither can be started.
     */
    fun startFirmware(context: Context): Boolean {
        val pm = context.packageManager
        val xiaomi = runCatching { pm.getPackageInfo(XIAOMI, 0) }.isSuccess
        if (xiaomi) {
            // Its MainActivity is the screensaver. HyperOS starts it from a protected broadcast with
            // these extras; without them it opens with its style picker over it (and an ad).
            // MiTV-MFTR0, screensaver 3.04.115.
            val saver = Intent(Intent.ACTION_MAIN).setClassName(XIAOMI, "$XIAOMI.MainActivity")
                .putExtra("show_saver_list", false)
                .putExtra("show_widget_view", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(saver) }.isSuccess) return true
            Log.w(TAG, "could not open Xiaomi's screensaver")
        }
        return startDream(context)
    }
}
