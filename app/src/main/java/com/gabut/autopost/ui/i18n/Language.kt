package com.gabut.autopost.ui.i18n

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** The two languages the UI ships with. */
enum class AppLanguage(
    val code: String,
    /** Name in that language, so the picker reads correctly whichever one is active. */
    val nativeName: String,
    val englishName: String
) {
    ENGLISH("en", "English", "English"),
    INDONESIAN("id", "Bahasa Indonesia", "Indonesian");

    companion object {
        fun fromCode(code: String?): AppLanguage? =
            entries.firstOrNull { it.code == code }

        /** First launch follows the phone: an Indonesian device starts in Indonesian. */
        fun systemDefault(): AppLanguage {
            val language = Locale.getDefault().language
            return if (language == "in" || language == "id") INDONESIAN else ENGLISH
        }
    }
}

/**
 * The selected language as a Compose state.
 *
 * Deliberately a single global instead of a CompositionLocal: the same string helper is called
 * from the ViewModel, services and notification builders, which have no composition. Reading a
 * `mutableStateOf` inside a composable still subscribes it, so a language switch recomposes every
 * screen that calls [t].
 */
object LanguageState {
    var current: AppLanguage by mutableStateOf(AppLanguage.systemDefault())
        private set

    fun set(language: AppLanguage) {
        current = language
    }
}

/** English text when the UI is English, [id] when it is Indonesian. */
fun t(en: String, id: String): String =
    if (LanguageState.current == AppLanguage.INDONESIAN) id else en

/**
 * A context whose resources resolve in the in-app language.
 *
 * Only string resources need this (notification channel names); every other string in the app is
 * produced by [t]. Without it the channels would follow the phone, not the app.
 */
fun localizedContext(base: Context): Context {
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(appLocale())
    return base.createConfigurationContext(configuration)
}

/** Locale used for dates, so month names follow the in-app language, not the phone. */
fun appLocale(): Locale = when (LanguageState.current) {
    AppLanguage.INDONESIAN -> Locale("id", "ID")
    AppLanguage.ENGLISH -> Locale.ENGLISH
}
