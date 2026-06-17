package net.exent.perfectpitch.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    private const val SYSTEM = "system"

    val supportedLanguages = listOf(
        Language("system", "Auto"),
        Language("en", "English"),
        Language("nb", "Norsk"),
        Language("de", "Deutsch")
    )

    fun createLocaleContext(base: Context, languageCode: String): Context {
        val locale = resolveLocale(languageCode)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun resolveLocale(languageCode: String): Locale {
        if (languageCode == SYSTEM) {
            return getDeviceBestMatch()
        }
        return Locale.forLanguageTag(languageCode)
    }

    private fun getDeviceBestMatch(): Locale {
        val deviceLocale = Locale.getDefault()
        val supported = supportedLanguages.map { it.code }
        return if (deviceLocale.language in supported) {
            deviceLocale
        } else {
            Locale.ENGLISH
        }
    }

    data class Language(
        val code: String,
        val displayName: String
    )
}
