package com.jervis.ui

import com.jervis.di.RpcConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for connection state — tracks server connectivity, offline status, reconnect.
 */
class ConnectionViewModel(
    private val connectionManager: RpcConnectionManager,
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) {
            println("ConnectionViewModel: uncaught exception: ${e::class.simpleName}: ${e.message}")
        }
    })

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    val isOffline: StateFlow<Boolean> = _state
        .map { it != State.CONNECTED }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), true)

    fun updateState(newState: State) {
        _state.value = newState
    }

    fun setInitialLoading(loading: Boolean) {
        _isInitialLoading.value = loading
    }

    fun manualReconnect() {
        connectionManager.requestReconnect()
    }
}
