package net.exent.harmonisk.ui.whack

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import net.exent.harmonisk.data.ProfileRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WhackRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val KEY_BEST = "best_score"
    }

    private val prefs = context.getSharedPreferences("whack_scores", Context.MODE_PRIVATE)

    fun best(profileId: String): Int = prefs.getInt(ProfileRepository.scopedKey(profileId, KEY_BEST), 0)

    fun saveBest(profileId: String, score: Int) {
        if (score <= best(profileId)) return
        prefs.edit { putInt(ProfileRepository.scopedKey(profileId, KEY_BEST), score) }
    }

    fun deleteProfileData(profileId: String) {
        prefs.edit { remove(ProfileRepository.scopedKey(profileId, KEY_BEST)) }
    }
}
