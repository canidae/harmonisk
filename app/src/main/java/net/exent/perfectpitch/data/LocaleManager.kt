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
        Language("de", "Deutsch"),
        Language("fr", "Fran\u00e7ais"),
        Language("it", "Italiano"),
        Language("es", "Espa\u00f1ol"),
        Language("sv", "Svenska"),
        Language("da", "Dansk"),
        Language("fi", "Suomi"),
        Language("is", "\u00cdslenska"),
        Language("uk", "\u0423\u043a\u0440\u0430\u0457\u043d\u0441\u044c\u043a\u0430"),
        Language("zh", "\u4e2d\u6587"),
        Language("ja", "\u65e5\u672c\u8a9e"),
        Language("hi", "\u0939\u093f\u0928\u094d\u0926\u0940"),
        Language("bn", "\u09ac\u09be\u0982\u09b2\u09be"),
        Language("ko", "\ud55c\uad6d\uc5b4"),
        Language("ar", "\u0627\u0644\u0639\u0631\u0628\u064a\u0629"),
        Language("pt", "Portugu\u00eas")
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
