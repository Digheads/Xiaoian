package com.xiaoian.app.ui.screens

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.xiaoian.app.service.SessionManager
import com.xiaoian.app.service.SessionState
import com.xiaoian.app.service.XiaoianService
import kotlinx.coroutines.flow.StateFlow

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    // In a real app we'd inject this via Hilt/Dagger, but for now we'll 
    // connect to a singleton or just let the service manage it.
    // Wait, the Service manages the SessionManager. We need a way to share the state.
    // Let's create a singleton provider for SessionManager for Phase 1.
    
    val sessionState: StateFlow<SessionState> = com.xiaoian.app.service.SessionManagerProvider.sessionManager.state

    fun startSession(mode: String, de: String) {
        val intent = Intent(getApplication(), XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_START
            putExtra("mode", mode)
            putExtra("de", de)
        }
        getApplication<Application>().startForegroundService(intent)
    }

    fun stopSession() {
        val intent = Intent(getApplication(), XiaoianService::class.java).apply {
            action = XiaoianService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }
}
