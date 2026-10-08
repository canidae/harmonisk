package net.exent.harmonisk.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer mode, enabled by holding the home heading, and kept in memory only: closing the app
 * turns it off again.
 */
@Singleton
class DevModeState @Inject constructor() {

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean get() = _enabled.value

    /** Enable or disable developer mode, returning the new state. */
    fun toggle(): Boolean {
        val next = !_enabled.value
        _enabled.value = next
        return next
    }
}
