package app.xinghe.tv.system

import android.content.ClipboardManager

/**
 * Compose casts getSystemService(CLIPBOARD_SERVICE) to ClipboardManager when it attaches its view,
 * and some firmwares ship without the clipboard service: Aerial crashed before drawing anything
 * (Skyworth 8R37_G320, Android 8.0, issue #95). Aerial has no text field, so nothing copies or
 * pastes; the stand-in only gets past that cast. Its constructor needs the missing service, hence
 * an instance allocated without running it.
 */
object MissingClipboard {
    val standIn: ClipboardManager? by lazy {
        runCatching {
            val unsafe = Class.forName("sun.misc.Unsafe")
            val instance = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            unsafe.getMethod("allocateInstance", Class::class.java).invoke(instance, ClipboardManager::class.java) as ClipboardManager
        }.getOrNull()
    }
}
