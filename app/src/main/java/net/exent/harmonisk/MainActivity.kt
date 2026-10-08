package net.exent.harmonisk

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import net.exent.harmonisk.data.LocaleManager
import net.exent.harmonisk.data.SettingsRepository
import net.exent.harmonisk.ui.theme.HarmoniskTheme
import net.exent.harmonisk.ui.navigation.NavGraph
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HarmoniskTheme {
                NavGraph()
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        val languageCode = SettingsRepository(newBase).getLanguageCodeSync()
        super.attachBaseContext(LocaleManager.createLocaleContext(newBase, languageCode))
    }
}
