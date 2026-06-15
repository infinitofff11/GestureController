package com.example.gesturecontroller.model

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

sealed class GestureUiState {
    object Idle : GestureUiState()
    object NoHand : GestureUiState()
    data class Detecting(val landmarks: List<NormalizedLandmark>) : GestureUiState()
    data class GestureConfirmed(val gesture: GestureType) : GestureUiState()
    data class Error(val message: String) : GestureUiState()
    object Loading : GestureUiState()
}
