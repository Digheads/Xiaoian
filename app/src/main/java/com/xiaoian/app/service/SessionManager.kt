package com.xiaoian.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class SessionState {
    object Idle : SessionState()
    data class Installing(val progress: String) : SessionState()
    object Starting : SessionState()
    data class Running(val mode: String, val de: String, val isLocked: Boolean = false) : SessionState()
    object Stopping : SessionState()
    data class Error(val message: String) : SessionState()
}

class SessionManager {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun updateState(newState: SessionState) {
        _state.value = newState
    }
}
