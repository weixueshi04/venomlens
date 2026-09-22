package com.insta360.kmpsdk.demo.ui.livestream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LiveStreamViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(LiveStreamUiState())
    val uiState: StateFlow<LiveStreamUiState> = _uiState.asStateFlow()

    private val _events = Channel<LiveStreamEvent>(Channel.BUFFERED)
    val events: Flow<LiveStreamEvent> = _events.receiveAsFlow()

    fun onRtmpUrlChange(url: String) {
        _uiState.update { it.copy(rtmpUrl = url) }
    }

    fun onResolutionChange(resolution: Resolution) {
        _uiState.update { it.copy(resolution = resolution) }
    }

    fun onPreviewPhaseChange(phase: PreviewPhase) {
        _uiState.update {
            val livePhase = if (phase !is PreviewPhase.Playing && it.livePhase is LivePhase.Pushing) {
                LivePhase.Idle
            } else {
                it.livePhase
            }
            it.copy(previewPhase = phase, livePhase = livePhase)
        }
    }

    fun onLivePhaseChange(phase: LivePhase) {
        _uiState.update { it.copy(livePhase = phase) }
    }

    fun onStatsUpdate(stats: StreamStats?) {
        _uiState.update { it.copy(stats = stats) }
    }

    fun emitToast(message: String) {
        viewModelScope.launch { _events.send(LiveStreamEvent.ShowToast(message)) }
    }
}

sealed interface LiveStreamEvent {
    data class ShowToast(val message: String) : LiveStreamEvent
}
