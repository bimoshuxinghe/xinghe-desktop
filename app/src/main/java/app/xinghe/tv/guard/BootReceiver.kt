package app.xinghe.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.xinghe.tv.MainActivity

/**
 * After a real reboot: restore Home Guard, and if the firmware still hands Home to its own
 * launcher, open Aerial. Starting an activity from a receiver needs the "display over other apps"
 * permission on Android 10+, which tools/install.sh grants.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exported for the boot broadcasts only; anything else could use it to pull Aerial forward.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        GuardSetup.ensureEnabled(context)
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        // Also when Aerial is the default Home: some firmwares start their own launcher at boot
        // regardless (Skyworth/Coolita 8R145, issue #43).
        // A guard that is already running looks at what is in front before taking Home: by the time
        // this arrives, an HDMI-CEC source may already have switched the TV to its input. It still
        // has to look: on Xiaomi MiTV-MTEQ0 (Android 10) it is bound ~20 s before BOOT_COMPLETED,
        // never sees PatchWall's windows, and the take-over at bind time did not stick (issue #154).
        if (HomeGuardService.onBootCompleted()) return
        runCatching {
            context.startActivity(MainActivity.bringBackIntent(context))
        }
    }
}
