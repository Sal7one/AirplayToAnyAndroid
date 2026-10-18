package com.sal7one.tvtestproject.receiver

import org.junit.Assert.*
import org.junit.Test

class ReceiverPresentationTest {
    @Test fun stoppedReceiverCanBeStartedNotStoppedAgain() {
        val model = ReceiverPresentation.from(ReceiverStatus(ReceiverState.STOPPED, "Stopped"))
        assertEquals(ReceiverAction.START, model.primaryAction)
        assertFalse(model.busy)
        assertFalse(model.canRestart)
        assertFalse(model.needsConfirmation)
    }

    @Test fun failureRetriesByRestartingRatherThanStartingExistingEngine() {
        val model = ReceiverPresentation.from(ReceiverStatus(ReceiverState.ERROR, "Decoder failed", "192.0.2.1"))
        assertEquals(ReceiverAction.RESTART, model.primaryAction)
        assertTrue(model.canRestart)
        assertTrue(model.needsConfirmation)
    }

    @Test fun activeSessionRequiresConfirmationAndUsesStreamingOverlay() {
        val model = ReceiverPresentation.from(ReceiverStatus(ReceiverState.STREAMING, "Mirroring", "192.0.2.1"))
        assertTrue(model.streaming)
        assertTrue(model.needsConfirmation)
        assertEquals(ReceiverAction.STOP, model.primaryAction)
    }

    @Test fun startupAndPendingActionsDisableRepeatedCommands() {
        assertTrue(ReceiverPresentation.from(ReceiverStatus(ReceiverState.STARTING, "Starting")).busy)
        val model = ReceiverPresentation.from(ReceiverStatus(ReceiverState.READY, "Ready"), ReceiverAction.RESTART)
        assertTrue(model.busy)
        assertFalse(model.canRestart)
    }

    @Test fun intermediateRestartStopDoesNotFinishPendingRestart() {
        assertFalse(ReceiverAction.RESTART.isComplete(ReceiverState.STOPPED))
        assertFalse(ReceiverAction.RESTART.isComplete(ReceiverState.STARTING))
        assertTrue(ReceiverAction.RESTART.isComplete(ReceiverState.READY))
        assertTrue(ReceiverAction.STOP.isComplete(ReceiverState.STOPPED))
        assertFalse(ReceiverAction.STOP.isComplete(ReceiverState.READY))
        assertTrue(ReceiverAction.START.isComplete(ReceiverState.ERROR))
    }
}
