package app.xinghe.tv.apps

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONObject

/**
 * Names typed for apps on the phone page, by package name, kept in the settings prefs as JSON.
 * Home shows them in place of the app's own label (HomeViewModel.arrange); the tile art never
 * carries an app's name, so nothing is drawn again. Inputs are named in SourceLooks, folders in
 * LayoutStore.
 */
object AppNames {
    private const val PREFS = "settings"
    private const val KEY = "app_names"
    const val MAX_NAME = 24

    var all by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private lateinit var app: Context

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val json = runCatching { JSONObject(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: "{}") }
            .getOrDefault(JSONObject())
        all = json.keys().asSequence().associateWith { json.optString(it) }.filterValues { it.isNotBlank() }
    }

    fun of(key: String): String? = all[key]

    /** A blank name goes back to the app's own. Call on the main thread: Home watches [all]. */
    fun rename(key: String, name: String?) {
        val clean = name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
        all = if (clean == null) all - key else all + (key to clean)
        val json = JSONObject()
        all.forEach { (k, v) -> json.put(k, v) }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, json.toString()) }
    }
}
