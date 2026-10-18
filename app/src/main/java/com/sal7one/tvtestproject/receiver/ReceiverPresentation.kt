package com.sal7one.tvtestproject.receiver

enum class ReceiverAction {
    START, STOP, RESTART;

    fun isComplete(state: ReceiverState): Boolean = state == ReceiverState.ERROR || when (this) {
        STOP -> state == ReceiverState.STOPPED
        START, RESTART -> state in setOf(ReceiverState.READY, ReceiverState.CONNECTED, ReceiverState.STREAMING)
    }
}

data class ReceiverPresentation(
    val primaryAction: ReceiverAction,
    val busy: Boolean,
    val canRestart: Boolean,
    val needsConfirmation: Boolean,
    val streaming: Boolean,
) {
    companion object {
        fun from(status: ReceiverStatus, pending: ReceiverAction? = null): ReceiverPresentation {
            val busy = pending != null || status.state == ReceiverState.STARTING
            return ReceiverPresentation(
                primaryAction = when (status.state) {
                    ReceiverState.STOPPED -> ReceiverAction.START
                    ReceiverState.ERROR -> ReceiverAction.RESTART
                    else -> ReceiverAction.STOP
                },
                busy = busy,
                canRestart = !busy && status.state != ReceiverState.STOPPED,
                needsConfirmation = status.state != ReceiverState.STOPPED &&
                    (status.peer != null || status.state == ReceiverState.CONNECTED || status.state == ReceiverState.STREAMING),
                streaming = status.state == ReceiverState.STREAMING,
            )
        }
    }
}
