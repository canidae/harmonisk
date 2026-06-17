package net.exent.perfectpitch

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.exent.perfectpitch.data.LocaleManager
import net.exent.perfectpitch.data.SettingsRepository
import net.exent.perfectpitch.ui.theme.PerfectPitchTheme
import net.exent.perfectpitch.ui.navigation.NavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PerfectPitchTheme {
                NavGraph()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val languageCode = SettingsRepository(newBase).getLanguageCodeSync()
        super.attachBaseContext(LocaleManager.createLocaleContext(newBase, languageCode))
    }
}
