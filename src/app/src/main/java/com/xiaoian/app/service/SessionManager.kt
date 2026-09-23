package com.xiaoian.app.service

import com.xiaoian.app.model.Desktop
import com.xiaoian.app.model.DisplayMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SessionState {
    object Idle : SessionState()
    object Starting : SessionState()
    data class Running(val mode: DisplayMode, val de: Desktop, val isLocked: Boolean = false) : SessionState()
    object Stopping : SessionState()
    data class Error(val message: String) : SessionState()
}

class SessionManager {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _setup = MutableStateFlow(SetupProgress())
    /** Step list and progress of the current start; meaningful while [state] is Starting. */
    val setup: StateFlow<SetupProgress> = _setup.asStateFlow()

    private val _retargeting = MutableStateFlow(false)
    /** True while a running session is being switched to a different mode. */
    val retargeting: StateFlow<Boolean> = _retargeting.asStateFlow()

    private val _retargetError = MutableStateFlow<String?>(null)
    /**
     * Why the last retarget was refused -- e.g. the reboot-gated global
     * settings mismatch `do_retarget` reports -- or null once cleared. The
     * session itself is untouched when this is set: a refusal never moves
     * [state] out of the mode it was already running in.
     */
    val retargetError: StateFlow<String?> = _retargetError.asStateFlow()

    fun updateState(newState: SessionState) {
        _state.value = newState
    }

    fun updateSetup(progress: SetupProgress) {
        _setup.value = progress
    }

    fun updateRetargeting(active: Boolean) {
        _retargeting.value = active
    }

    fun updateRetargetError(message: String?) {
        _retargetError.value = message
    }
}
