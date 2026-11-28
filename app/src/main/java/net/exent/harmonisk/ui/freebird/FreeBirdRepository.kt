package net.exent.harmonisk.ui.freebird

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import net.exent.harmonisk.data.ProfileRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FreeBirdRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    companion object {
        private const val KEY_BEST_SCORE = "best_score"
        private const val KEY_BEST_DISTANCE = "best_distance_tenths"
    }

    private val prefs = context.getSharedPreferences("freebird_scores", Context.MODE_PRIVATE)

    fun bestDistance(profileId: String): Int {
        val key = ProfileRepository.scopedKey(profileId, KEY_BEST_DISTANCE)
        if (prefs.contains(key)) return prefs.getInt(key, 0)
        val legacy = prefs.getInt(ProfileRepository.scopedKey(profileId, KEY_BEST_SCORE), 0)
        if (legacy <= 0) return 0
        val tenths = legacy * 10
        prefs.edit { putInt(key, tenths) }
        return tenths
    }

    fun saveBestDistance(profileId: String, tenths: Int) {
        if (tenths <= bestDistance(profileId)) return
        prefs.edit { putInt(ProfileRepository.scopedKey(profileId, KEY_BEST_DISTANCE), tenths) }
    }

    fun deleteProfileData(profileId: String) {
        prefs.edit {
            remove(ProfileRepository.scopedKey(profileId, KEY_BEST_SCORE))
            remove(ProfileRepository.scopedKey(profileId, KEY_BEST_DISTANCE))
        }
    }
}
