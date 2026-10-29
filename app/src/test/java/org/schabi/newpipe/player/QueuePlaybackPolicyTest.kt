package org.schabi.newpipe.player

import org.junit.Assert.*
import org.junit.Test

class QueuePlaybackPolicyTest {
    @Test fun previousKeepsStableOrderAcrossReplayRecencyChanges() {
        val policy = QueuePlaybackPolicy<String> { it }
        assertEquals("B", policy.previous("C", listOf("C", "B", "A")))
        policy.invalidateHistory(listOf("B", "C", "A"))
        assertEquals("A", policy.previous("B", listOf("B", "C", "A")))
        assertNull(policy.previous("A", listOf("A", "B", "C")))
        policy.resetPrevious() // A forward transition permits a fresh history snapshot.
        assertEquals("B", policy.previous("C", listOf("C", "B", "A")))
    }

    @Test fun historyDeletionAndDisabledHistoryInvalidateBackwardCandidates() {
        val policy = QueuePlaybackPolicy<String> { it }
        assertEquals("C", policy.previous("D", listOf("C", "B", "A")))
        policy.invalidateHistory(listOf("C", "A")) // B was deleted.
        assertEquals("A", policy.previous("C", listOf("C", "A")))
        policy.resetPrevious()
        assertEquals("C", policy.previous("D", listOf("C", "A")))
        policy.invalidateHistory(emptyList()) // Recording off or depth zero.
        assertNull(policy.previous("C", emptyList()))
    }

    @Test fun errorsStaleCompletionAndRemoteBoundariesNeverConsume() {
        assertTrue(QueuePlaybackPolicy.consumeCompletion("A", "A", false, false))
        assertFalse(QueuePlaybackPolicy.consumeCompletion("A", "B", false, false))
        assertFalse(QueuePlaybackPolicy.consumeCompletion("A", "A", true, false))
        assertFalse(QueuePlaybackPolicy.consumeCompletion("A", "A", false, true))
        assertFalse(QueuePlaybackPolicy.consumeCompletion(null, null, false, false))
    }

    @Test fun nextAndHistoryReplayAfterFailureAllowNewHeadToComplete() {
        for (nextHead in listOf("B", "previous-history-video")) {
            val policy = QueuePlaybackPolicy<String> { it }
            policy.headChanged("A")
            policy.playbackFailed()
            assertFalse(QueuePlaybackPolicy.consumeCompletion("A", "A", policy.failed, false))
            policy.headChanged("A") // Ordinary refresh does not silently retry a failed head.
            assertTrue(policy.failed)
            policy.headChanged(nextHead) // Next or Previous introduces a different occurrence.
            assertTrue(QueuePlaybackPolicy.consumeCompletion(nextHead, nextHead, policy.failed, false))
        }
    }

    @Test fun enqueueStartsOnlyEmptyQueueAndPreservesAttachedPlaybackReadiness() {
        assertTrue(QueuePlaybackPolicy.enqueueReady(true, false, false))
        assertFalse(QueuePlaybackPolicy.enqueueReady(false, false, true))
        assertFalse(QueuePlaybackPolicy.enqueueReady(false, true, false))
        assertTrue(QueuePlaybackPolicy.enqueueReady(false, true, true))
    }
}
