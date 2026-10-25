package app.xinghe.tv.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock

/**
 * Some AOSP TV Settings builds show MainSettings as a translucent right-hand panel. Starting it
 * in a separate task leaves that task's black background behind the panel (reported on a CM311-1
 * running a modified Android 9 ATV firmware). An Activity can keep it above its own content.
 *
 * Do not change full-screen/OEM settings, application-context launches, or normal app launches.
 * This only removes a caller-supplied NEW_TASK; it cannot override a target's manifest launchMode.
 */
internal fun settingsLaunchIntent(context: Context, intent: Intent): Intent {
    var caller = context
    while (caller !is Activity && caller is ContextWrapper) {
        val base = caller.baseContext
        if (base === caller) return intent
        caller = base
    }
    if (caller !is Activity) return intent
    val info = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }
        .getOrNull() ?: return intent
    if (info.packageName != "com.android.tv.settings" ||
        info.name != "com.android.tv.settings.MainSettings" ||
        info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
    ) return intent
    return Intent(intent).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
}

/**
 * Pages to open instead when the one just opened closes at once, which Aerial sees as being back
 * in front within a moment of opening it. See SystemProbe.networkSettingsIntents.
 */
internal object LaunchFallback {
    private var rest: List<Intent> = emptyList()
    private var armedAt = 0L

    fun arm(rest: List<Intent>) {
        this.rest = rest
        armedAt = SystemClock.uptimeMillis()
    }

    /** The next page to try, if Aerial is back too soon after the last one; called on resume. */
    fun next(): Intent? {
        val pages = rest
        rest = emptyList()
        if (pages.isEmpty() || SystemClock.uptimeMillis() - armedAt > BOUNCE_MS) return null
        arm(pages.drop(1))
        return pages.first()
    }

    /**
     * Xiaomi MiTV-ANSP0 closed MitvWifiActivity 0.2 s after it opened (issue #331). At 2 s a look at
     * Xiaomi's Wi-Fi page and Back (1.3 s, MiTV-MFTR0) opened Android's after it, and Back from that
     * the settings app.
     */
    private const val BOUNCE_MS = 800L
}
