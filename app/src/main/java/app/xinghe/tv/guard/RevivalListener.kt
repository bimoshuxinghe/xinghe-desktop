package app.xinghe.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Brings Home Guard back after the firmware kills Aerial.
 *
 * Xiaomi force-stops every third-party app when the TV goes to standby, and Android drops a
 * force-stopped app's accessibility services from the enabled list for good. Notification
 * listeners are treated differently: the system keeps the approval and binds the listener again
 * shortly after its process dies. That rebind is the one thing that reliably restarts Aerial
 * after a standby, so it is used for exactly this. No notification is read or touched.
 */
class RevivalListener : NotificationListenerService() {
    private var unlocked: BroadcastReceiver? = null

    override fun onListenerConnected() {
        Log.i("AerialGuard", "revived by the notification listener binding")
        GuardSetup.ensureEnabled(this)
        // Bound before the user is unlocked (Direct Boot): Aerial's preferences are in credential
        // storage, and reading them crashed Aerial at boot, which left the stock launcher in front
        // (TCL 65S515D, Android 9, issue #82). Wait for the unlock.
        if (!GuardSetup.isUserUnlocked(this)) {
            if (unlocked != null) return
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (unlocked !== this) return
                    unregisterReceiver(this)
                    unlocked = null
                    revive()
                }
            }
            unlocked = receiver
            registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            // Unlocked in between: the broadcast is already gone.
            if (GuardSetup.isUserUnlocked(this)) receiver.onReceive(this, Intent(Intent.ACTION_USER_UNLOCKED))
            return
        }
        revive()
    }

    private fun revive() {
        GuardSetup.noteRevival(this)
        // After a crash Android keeps the guard enabled but does not bind it again (TCL MT5879),
        // and nothing else would notice until Aerial is opened: Home went to the TCL launcher.
        Handler(Looper.getMainLooper()).postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
    }

    override fun onDestroy() {
        unlocked?.let { runCatching { unregisterReceiver(it) } }
        unlocked = null
        super.onDestroy()
    }
}
