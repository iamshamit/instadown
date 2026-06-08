package com.instadown.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

enum class ThemeMode { LIGHT, OLED }

// Instagram gradient: purple → pink → orange (135deg, 3 stops)
val IgGradientColors = listOf(
    Color(0xFF833AB4),
    Color(0xFFE1306C),
    Color(0xFFF77737),
)

private val LightColors = lightColorScheme(
    primary             = Color(0xFF7B3FBD),  // section labels, accents
    onPrimary           = Color(0xFFFFFFFF),
    primaryContainer    = Color(0xFFECEAF4),
    onPrimaryContainer  = Color(0xFF160D29),
    background          = Color(0xFFF0EDF8),
    surface             = Color(0xFFFFFFFF),
    surfaceVariant      = Color(0xFFECEAF4),  // surface2 / pill-off / disabled bg
    onSurface           = Color(0xFF160D29),
    onSurfaceVariant    = Color(0xFF6A5F80),  // secondary text
    error               = Color(0xFFEF4444),
    errorContainer      = Color(0x14EF4444),  // rgba(239,68,68,0.08)
    onErrorContainer    = Color(0xFFEF4444),
    outline             = Color(0xFFE0DBF0),  // border
    outlineVariant      = Color(0xFFEAE6F4),  // divider
)

private val OledColors = darkColorScheme(
    primary             = Color(0xFFA880D8),  // section labels, accents
    onPrimary           = Color(0xFF1A003E),
    primaryContainer    = Color(0xFF1B1B1B),
    onPrimaryContainer  = Color(0xFFFFFFFF),
    background          = Color(0xFF000000),
    surface             = Color(0xFF111111),
    surfaceVariant      = Color(0xFF1B1B1B),  // surface2 / pill-off / disabled bg
    onSurface           = Color(0xFFFFFFFF),
    onSurfaceVariant    = Color(0xFF888888),  // secondary text
    error               = Color(0xFFEF4444),
    errorContainer      = Color(0x14EF4444),  // rgba(239,68,68,0.08)
    onErrorContainer    = Color(0xFFEF4444),
    outline             = Color(0xFF282828),  // border
    outlineVariant      = Color(0xFF1E1E1E),  // divider
)

object ThemePrefs {
    private const val PREFS_NAME        = "instadown_prefs"
    private const val KEY_THEME         = "theme_mode"
    private const val KEY_STORAGE_URI   = "storage_uri"
    private const val KEY_STORAGE_LABEL = "storage_label"

    private lateinit var prefs: SharedPreferences
    private val _mode: MutableState<ThemeMode> = mutableStateOf(ThemeMode.OLED)

    val mode: State<ThemeMode> get() = _mode

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _mode.value = ThemeMode.valueOf(
            prefs.getString(KEY_THEME, ThemeMode.OLED.name) ?: ThemeMode.OLED.name,
        )
    }

    fun setMode(mode: ThemeMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    fun getStorageUri(): String? = prefs.getString(KEY_STORAGE_URI, null)
    fun getStorageLabel(): String = prefs.getString(KEY_STORAGE_LABEL, "Downloads/InstaDown") ?: "Downloads/InstaDown"

    fun setStorage(uri: String, label: String) {
        prefs.edit().putString(KEY_STORAGE_URI, uri).putString(KEY_STORAGE_LABEL, label).apply()
    }

    fun clearStorage() {
        prefs.edit().remove(KEY_STORAGE_URI).remove(KEY_STORAGE_LABEL).apply()
    }
}

@Composable
fun InstaDownTheme(content: @Composable () -> Unit) {
    val colors = when (ThemePrefs.mode.value) {
        ThemeMode.LIGHT -> LightColors
        ThemeMode.OLED  -> OledColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
