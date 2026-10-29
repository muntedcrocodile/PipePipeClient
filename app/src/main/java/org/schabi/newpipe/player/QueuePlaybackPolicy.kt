package org.schabi.newpipe.player

/** Temporary navigation state. Persistent membership and global history remain separate owners. */
internal class QueuePlaybackPolicy<T>(private val identity: (T) -> String) {
    private var previous: MutableList<T>? = null
    private var head: String? = null
    var failed: Boolean = false
        private set

    fun headChanged(id: String) {
        if (head != id) {
            head = id
            failed = false
        }
    }

    fun playbackFailed() { failed = true }
    fun clearFailure() { failed = false }

    fun resetPrevious() { previous = null }

    fun invalidateHistory(latest: List<T>) {
        val available = latest.map(identity).toSet()
        previous?.removeAll { identity(it) !in available }
    }

    fun previous(current: T?, latest: List<T>): T? {
        if (previous == null) {
            val currentId = current?.let(identity)
            previous = latest.filter { identity(it) != currentId }.toMutableList()
        }
        return previous?.takeIf { it.isNotEmpty() }?.removeAt(0)
    }

    companion object {
        fun consumeCompletion(currentId: String?, headId: String?, failed: Boolean,
                              blocked: Boolean): Boolean =
            !failed && !blocked && currentId != null && currentId == headId

        // A closed unfinished queue restores paused; only the first enqueue starts playback.
        fun enqueueReady(empty: Boolean, playerAttached: Boolean, wasReady: Boolean): Boolean =
            empty || (playerAttached && wasReady)
    }
}
