package net.exent.harmonisk.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleManager {
    private const val SYSTEM = "system"
    private const val NORWEGIAN = "nb"
    private val NORWEGIAN_TAGS = setOf("nb", "nn", "no")

    val supportedLanguages = listOf(
        Language(SYSTEM, "Auto"),
        Language("en", "English"),
        Language(NORWEGIAN, "Norsk")
    )

    fun createLocaleContext(base: Context, languageCode: String): Context {
        val locale = resolveLocale(languageCode)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    fun resolveLocale(languageCode: String): Locale =
        if (languageCode in supportedLanguages.map { it.code } && languageCode != SYSTEM) {
            Locale.forLanguageTag(languageCode)
        } else {
            getDeviceBestMatch()
        }

    private fun getDeviceBestMatch(): Locale =
        if (Locale.getDefault().language in NORWEGIAN_TAGS) Locale.forLanguageTag(NORWEGIAN) else Locale.ENGLISH

    data class Language(
        val code: String,
        val displayName: String
    )
}
