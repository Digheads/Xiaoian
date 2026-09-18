package com.xiaoian.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SessionState {
    object Idle : SessionState()
    object Starting : SessionState()
    data class Running(val mode: String, val de: String, val isLocked: Boolean = false) : SessionState()
    object Stopping : SessionState()
    data class Error(val message: String) : SessionState()
}

class SessionManager {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _setup = MutableStateFlow(SetupProgress())
    /** Step list and progress of the current start; meaningful while [state] is Starting. */
    val setup: StateFlow<SetupProgress> = _setup.asStateFlow()

    fun updateState(newState: SessionState) {
        _state.value = newState
    }

    fun updateSetup(progress: SetupProgress) {
        _setup.value = progress
    }
}
