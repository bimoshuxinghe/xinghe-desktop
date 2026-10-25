package app.xinghe.tv.apps

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.xinghe.tv.R
import org.json.JSONObject

/**
 * What is plugged into an input, chosen from the tile's menu (issues #172, #210): the tile then
 * shows that device's colours and [mark] alone, its name on the caption under it. [mark] is filled
 * path data in a [markBox]-unit square: a brand's logo from Simple Icons (CC0, 24 units), otherwise
 * a Material Symbols Rounded shape (Apache 2.0, 960 units), as Aerial's glyphs.
 */
enum class SourceDevice(val key: String, @StringRes val label: Int, val top: Int, val bottom: Int, val mark: String, val markBox: Float = 960f) {
    AppleTv(
        "apple_tv", R.string.device_apple_tv, 0xFF3A3A3C.toInt(), 0xFF0C0C0D.toInt(),
        "M20.57 17.735h-1.815l-3.34-9.203h1.633l2.02 5.987c.075.231.273.9.586 2.012l.297-.997.33-1.006 2.094-6.004H24zm-5.344-.066a5.76 5.76 0 0 1-1.55.207c-1.23 0-1.84-.693-1.84-2.087V9.646h-1.063V8.532h1.121V7.081l1.476-.602v2.062h1.707v1.113H13.38v5.805c0 .446.074.75.214.932.14.182.396.264.75.264.207 0 .495-.041.883-.115zm-7.29-5.343c.017 1.764 1.55 2.358 1.567 2.366-.017.042-.248.842-.808 1.658-.487.71-.99 1.418-1.79 1.435-.783.016-1.03-.462-1.93-.462-.89 0-1.17.445-1.913.478-.758.025-1.344-.775-1.838-1.484-.998-1.451-1.765-4.098-.734-5.88.51-.89 1.426-1.451 2.416-1.46.75-.016 1.468.512 1.93.512.461 0 1.327-.627 2.234-.536.38.016 1.452.157 2.136 1.154-.058.033-1.278.743-1.27 2.219M6.468 7.988c.404-.495.685-1.18.61-1.864-.585.025-1.294.388-1.723.883-.38.437-.71 1.138-.619 1.806.652.05 1.328-.338 1.732-.825Z",
        24f,
    ),
    PlayStation(
        "playstation", R.string.device_playstation, 0xFF1F5FD1.toInt(), 0xFF0A2A72.toInt(),
        "M8.984 2.596v17.547l3.915 1.261V6.688c0-.69.304-1.151.794-.991.636.18.76.814.76 1.505v5.875c2.441 1.193 4.362-.002 4.362-3.152 0-3.237-1.126-4.675-4.438-5.827-1.307-.448-3.728-1.186-5.39-1.502zm4.656 16.241l6.296-2.275c.715-.258.826-.625.246-.818-.586-.192-1.637-.139-2.357.123l-4.205 1.5V14.98l.24-.085s1.201-.42 2.913-.615c1.696-.18 3.785.03 5.437.661 1.848.601 2.04 1.472 1.576 2.072-.465.6-1.622 1.036-1.622 1.036l-8.544 3.107V18.86zM1.807 18.6c-1.9-.545-2.214-1.668-1.352-2.32.801-.586 2.16-1.052 2.16-1.052l5.615-2.013v2.313L4.205 17c-.705.271-.825.632-.239.826.586.195 1.637.15 2.343-.12L8.247 17v2.074c-.12.03-.256.044-.39.073-1.939.331-3.996.196-6.038-.479z",
        24f,
    ),
    // Nintendo and Microsoft had their marks taken out of Simple Icons, and Material has no
    // handheld: one drawn in the symbols' box and weight, a screen between two controllers.
    Switch(
        "switch", R.string.device_switch, 0xFFE8382F.toInt(), 0xFF8E1712.toInt(),
        "M210-680v400h-10q-50 0-85-35t-35-85v-160q0-50 35-85t85-35h10ZM145-595a35 35 0 1 0 0 70a35 35 0 1 0 0-70ZM270-680h420q36 0 36 36v328q0 36-36 36H270q-36 0-36-36v-328q0-36 36-36ZM298-616v272h364v-272H298ZM750-680h10q50 0 85 35t35 85v160q0 50-35 85t-85 35h-10ZM815-435a35 35 0 1 0 0 70a35 35 0 1 0 0-70Z",
    ),
    Xbox("xbox", R.string.device_xbox, 0xFF2E9B3A.toInt(), 0xFF0E4D17.toInt(), CONTROLLER),
    Console("console", R.string.device_console, 0xFF5B5FD6.toInt(), 0xFF2B2D7A.toInt(), CONTROLLER),
    // desktop_windows, settop_component and soundbar.
    Computer(
        "computer", R.string.device_computer, 0xFF4F6B85.toInt(), 0xFF1F2E3D.toInt(),
        "M400-200v-80H160q-33 0-56.5-23.5T80-360v-400q0-33 23.5-56.5T160-840h640q33 0 56.5 23.5T880-760v400q0 33-23.5 56.5T800-280H560v80h40q17 0 28.5 11.5T640-160q0 17-11.5 28.5T600-120H360q-17 0-28.5-11.5T320-160q0-17 11.5-28.5T360-200h40Z",
    ),
    SetTopBox(
        "set_top_box", R.string.device_set_top_box, 0xFFE0912F.toInt(), 0xFF8A4510.toInt(),
        "M160-280q-33 0-56.5-23.5T80-360v-240q0-33 23.5-56.5T160-680h640q33 0 56.5 23.5T880-600v240q0 33-23.5 56.5T800-280H160Zm80-160h160q17 0 28.5-11.5T440-480q0-17-11.5-28.5T400-520H240q-17 0-28.5 11.5T200-480q0 17 11.5 28.5T240-440Zm320 0q17 0 28.5-11.5T600-480q0-17-11.5-28.5T560-520q-17 0-28.5 11.5T520-480q0 17 11.5 28.5T560-440Zm120 0q17 0 28.5-11.5T720-480q0-17-11.5-28.5T680-520q-17 0-28.5 11.5T640-480q0 17 11.5 28.5T680-440Z",
    ),
    Speaker(
        "speaker", R.string.device_speaker, 0xFF6C6F78.toInt(), 0xFF2A2C31.toInt(),
        "M451.5-588.5Q440-577 440-560v80q0 17 11.5 28.5T480-440q17 0 28.5-11.5T520-480v-80q0-17-11.5-28.5T480-600q-17 0-28.5 11.5Zm-103 377Q337-200 320-200h-80q-17 0-28.5-11.5T200-240q0-17 11.5-28.5T240-280h80q17 0 28.5 11.5T360-240q0 17-11.5 28.5Zm400 0Q737-200 720-200h-80q-17 0-28.5-11.5T600-240q0-17 11.5-28.5T640-280h80q17 0 28.5 11.5T760-240q0 17-11.5 28.5ZM280-440q33 0 56.5-23.5T360-520q0-33-23.5-56.5T280-600q-33 0-56.5 23.5T200-520q0 33 23.5 56.5T280-440Zm400 0q33 0 56.5-23.5T760-520q0-33-23.5-56.5T680-600q-33 0-56.5 23.5T600-520q0 33 23.5 56.5T680-440ZM160-320q-33 0-56.5-23.5T80-400v-240q0-33 23.5-56.5T160-720h640q33 0 56.5 23.5T880-640v240q0 33-23.5 56.5T800-320H160Z",
    ),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** Material Symbols' sports_esports. */
private const val CONTROLLER =
    "M182-200q-51 0-79-35.5T82-322l42-300q9-60 53.5-99T282-760h396q60 0 104.5 39t53.5 99l42 300q7 51-21 86.5T778-200q-21 0-39-7.5T706-230l-90-90H344l-90 90q-15 15-33 22.5t-39 7.5Zm498-240q17 0 28.5-11.5T720-480q0-17-11.5-28.5T680-520q-17 0-28.5 11.5T640-480q0 17 11.5 28.5T680-440Zm-80-120q17 0 28.5-11.5T640-600q0-17-11.5-28.5T600-640q-17 0-28.5 11.5T560-600q0 17 11.5 28.5T600-560Zm-290 50v40q0 13 8.5 21.5T340-440q13 0 21.5-8.5T370-470v-40h40q13 0 21.5-8.5T440-540q0-13-8.5-21.5T410-570h-40v-40q0-13-8.5-21.5T340-640q-13 0-21.5 8.5T310-610v40h-40q-13 0-21.5 8.5T240-540q0 13 8.5 21.5T270-510h40Z"

/** A name and a device chosen for one input; either may be unset. */
data class SourceLook(val device: SourceDevice?, val name: String?)

/** The looks chosen for the inputs, by TvInputInfo id, kept in the settings prefs as JSON. */
object SourceLooks {
    private const val PREFS = "settings"
    private const val KEY = "source_looks"
    const val MAX_NAME = 24

    var all by mutableStateOf<Map<String, SourceLook>>(emptyMap()); private set
    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val json = runCatching { JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}") }
            .getOrDefault(JSONObject())
        all = json.keys().asSequence().associateWith { id ->
            val o = json.getJSONObject(id)
            SourceLook(SourceDevice.of(o.optString("device")), o.optString("name").takeIf { it.isNotBlank() })
        }.filterValues { it.device != null || it.name != null }
    }

    fun of(inputId: String): SourceLook? = all[inputId]

    /** Picking a device also names the tile after it; a typed name is dropped. */
    fun chooseDevice(inputId: String, device: SourceDevice?) =
        put(inputId, if (device == null) null else SourceLook(device, null))

    fun rename(inputId: String, name: String?) {
        val clean = name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
        put(inputId, SourceLook(of(inputId)?.device, clean))
    }

    private fun put(inputId: String, look: SourceLook?) {
        all = if (look == null || (look.device == null && look.name == null)) all - inputId else all + (inputId to look)
        val json = JSONObject()
        all.forEach { (id, l) -> json.put(id, JSONObject().put("device", l.device?.key ?: "").put("name", l.name ?: "")) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, json.toString()) }
    }
}
