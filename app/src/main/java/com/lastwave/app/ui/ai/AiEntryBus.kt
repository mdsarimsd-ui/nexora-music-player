package com.lastwave.app.ui.ai

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Cross-surface tap: the floating bubble asks any open screen to show the assistant sheet. */
object AiEntryBus {
    private val _openRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val openRequests: SharedFlow<Unit> = _openRequests

    fun requestOpen() {
        _openRequests.tryEmit(Unit)
    }
}
