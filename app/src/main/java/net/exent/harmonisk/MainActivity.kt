package net.exent.harmonisk

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import net.exent.harmonisk.data.LocaleManager
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.ui.theme.HarmoniskTheme
import net.exent.harmonisk.ui.navigation.NavGraph

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialThemeMode = SettingsRepository.getThemeModeSync(this)
        setContent {
            val themeMode by settingsRepository.themeMode.collectAsStateWithLifecycle(
                initialValue = initialThemeMode
            )
            HarmoniskTheme(themeMode = themeMode) {
                NavGraph()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val languageCode = SettingsRepository.getLanguageCodeSync(newBase)
        super.attachBaseContext(LocaleManager.createLocaleContext(newBase, languageCode))
    }
}
