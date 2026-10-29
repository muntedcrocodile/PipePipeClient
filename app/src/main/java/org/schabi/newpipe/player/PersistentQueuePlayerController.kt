package org.schabi.newpipe.player

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.google.android.exoplayer2.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import com.google.android.exoplayer2.Player.PositionInfo
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.schabi.newpipe.R
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem
import org.schabi.newpipe.player.playqueue.PersistentPlayQueue
import org.schabi.newpipe.player.playqueue.PersistentQueueManager
import org.schabi.newpipe.player.playqueue.QueueHistory
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.NavigationHelper

/** Playback policy around the single durable queue; membership remains owned by its manager. */
class PersistentQueuePlayerController(private val player: Player) {
    private val subscriptions = CompositeDisposable()
    private val main = Handler(Looper.getMainLooper())
    private var lastCheckpoint = 0L
    private val policy = QueuePlaybackPolicy<PlayerMediaItem> { "${it.serviceId}:${it.url}" }
    private var closing = false
    private var resumeHeadUuid: String? = null
    private var resumePending = false
    private var resumeGeneration = 0L
    private var attachedQueue: PersistentPlayQueue? = null
    private var historyReplayInProgress = false
    private var checkpointInProgress = false
    private var checkpointFailureReported = false
    private val modeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "persistent_queue_enabled") main.post { switchMode() }
    }

    fun start() {
        player.prefs.registerOnSharedPreferenceChangeListener(modeListener)
        subscriptions.add(QueueHistory.getInstance(player.context).observe().subscribe({ latest ->
            policy.invalidateHistory(latest)
        }, { error -> Log.w(Player.TAG, "Queue history unavailable", error) }))
        if (player.isPersistentQueueMode) restorePaused()
    }

    private fun restorePaused() {
        val queue = PersistentQueueManager.getInstance(player.context).queue
        if (QueuePlaybackPolicy.hasUnfinished(queue.isEmpty, queue.isComplete)) {
            player.handleIntent(NavigationHelper.getPlayerIntent(player.context,
                DeviceUtils.getPlayerServiceClass(), queue, false, false))
        }
    }

    private fun switchMode() {
        if (closing) return
        // Checkpoint by queue type: the preference already contains its NEW value here.
        checkpoint()
        attachedQueue?.setBeforeHeadChange(null)
        attachedQueue = null
        player.destroyPlayer()
        NotificationUtil.getInstance().cancelNotificationAndStopForeground(player.service.instance)
        player.playQueue = null
        policy.resetPrevious()
        resumeHeadUuid = null
        resumePending = false
        resumeGeneration++
        if (player.isPersistentQueueMode) restorePaused()
        else player.notifyQueueUpdateToListeners()
    }

    fun close() {
        closing = true
        checkpoint()
        attachedQueue?.setBeforeHeadChange(null)
        attachedQueue = null
        player.prefs.unregisterOnSharedPreferenceChangeListener(modeListener)
        subscriptions.clear()
        main.removeCallbacksAndMessages(null)
    }

    fun attachQueue() {
        attachedQueue?.setBeforeHeadChange(null)
        attachedQueue = player.playQueue as? PersistentPlayQueue
        attachedQueue?.setBeforeHeadChange {
            checkpoint()
            if (!historyReplayInProgress) policy.resetPrevious()
        }
        resumeHead()
    }

    fun newSelection() { policy.resetPrevious() }

    fun playbackFailed() { policy.playbackFailed() }

    /** New occurrences use global resume state; interrupted occurrences keep their own progress. */
    fun resumeHead() {
        val queue = player.playQueue as? PersistentPlayQueue ?: return
        resumeHead(queue)
    }

    fun resumeHead(queue: PersistentPlayQueue) {
        val head = queue.getItem() ?: return
        if (resumeHeadUuid == head.uuid) return
        resumeHeadUuid = head.uuid
        policy.headChanged(head.uuid) // Failure belongs to the occurrence that failed.
        val generation = ++resumeGeneration
        resumePending = false
        if (queue.getRecoveryPosition(head) != org.schabi.newpipe.player.playqueue.PlayQueue.RECOVERY_UNSET
            || !org.schabi.newpipe.player.helper.PlayerHelper.isPlaybackResumeEnabled(player)) return
        resumePending = true
        player.historyController.startPlaybackWithSavedProgress(head) { saved ->
            if (closing || generation != resumeGeneration || queue.getItem()?.uuid != head.uuid) {
                return@startPlaybackWithSavedProgress
            }
            resumePending = false
            if (saved != null && queue.getRecoveryPosition(head) ==
                org.schabi.newpipe.player.playqueue.PlayQueue.RECOVERY_UNSET) {
                try {
                    queue.setRecovery(0, saved)
                } catch (error: RuntimeException) {
                    failure(error)
                    return@startPlaybackWithSavedProgress
                }
                if (player.currentItem?.uuid == head.uuid && !player.exoPlayerIsNull()) {
                    player.simpleExoPlayer.seekTo(saved)
                }
            }
        }
    }

    fun tick() {
        val queue = player.playQueue as? PersistentPlayQueue ?: return
        if (queue.hasBlockedBoundary() && queue.pendingError != null) {
            player.onBufferingFailed()
            return
        }
        if (SystemClock.elapsedRealtime() - lastCheckpoint >= 5000) checkpoint()
    }

    fun checkpoint() {
        val queue = player.playQueue as? PersistentPlayQueue ?: return
        if (player.exoPlayerIsNull() || !player.isPrepared || resumePending || checkpointInProgress) return
        val current = player.currentItem ?: return
        val head = queue.getItem() ?: return
        if (current.uuid != head.uuid || player.simpleExoPlayer.currentMediaItemIndex != 0) return
        checkpointInProgress = true
        try {
            queue.setRecovery(0, player.simpleExoPlayer.contentPosition.coerceAtLeast(0))
            checkpointFailureReported = false
        } catch (error: RuntimeException) {
            // Never call pause()/onBufferingFailed() here: both checkpoint and would recurse.
            Log.e(Player.TAG, "Could not checkpoint persistent queue", error)
            if (!checkpointFailureReported) {
                checkpointFailureReported = true
                Toast.makeText(player.context, error.localizedMessage
                    ?: player.context.getString(R.string.network_error), Toast.LENGTH_LONG).show()
            }
            if (!player.exoPlayerIsNull()) player.simpleExoPlayer.pause()
        } finally {
            checkpointInProgress = false
        }
        lastCheckpoint = SystemClock.elapsedRealtime()
    }

    fun consume(completed: Boolean) {
        val queue = player.playQueue as? PersistentPlayQueue ?: return
        // Duplicate end callbacks cannot consume the newly selected, not yet synchronized head.
        if (completed && !QueuePlaybackPolicy.consumeCompletion(player.currentItem?.uuid,
                queue.getItem()?.uuid, policy.failed, queue.hasBlockedBoundary())) return
        if (completed) player.saveStreamProgressStateCompleted() else player.saveStreamProgressState()
        policy.resetPrevious()
        try {
            queue.consume()
            policy.clearFailure()
        } catch (error: RuntimeException) {
            failure(error)
            return
        }
        if (queue.isEmpty && queue.isComplete) {
            player.onPlaybackShutdown()
        } else if (queue.hasBlockedBoundary() && queue.pendingError == null) {
            queue.fetch()
        }
    }

    fun select(item: PlayerMediaItem) {
        val queue = player.playQueue as? PersistentPlayQueue ?: return
        val index = queue.indexOf(item)
        if (index < 0) return
        checkpoint()
        newSelection()
        try {
            queue.setIndex(index)
        } catch (error: RuntimeException) {
            failure(error)
            return
        }
        player.play()
    }

    fun previous() {
        val queue = player.playQueue as? PersistentPlayQueue ?: return
        val history = QueueHistory.getInstance(player.context)
        val item = policy.previous(queue.getItem(), history.items)
        if (item == null) {
            player.seekToDefault()
            return
        }
        replayHistory(item, true)
    }

    @JvmOverloads
    fun replayHistory(item: PlayerMediaItem, fromPrevious: Boolean = false) {
        if (!fromPrevious) policy.resetPrevious()
        checkpoint()
        val stream = org.schabi.newpipe.extractor.stream.StreamInfoItem(
            item.serviceId, item.url, item.title, item.streamType)
        stream.duration = item.duration
        stream.uploaderName = item.uploader
        stream.uploaderUrl = item.uploaderUrl
        stream.thumbnailUrl = item.thumbnailUrl
        val intent = NavigationHelper.getPlayerIntent(player.context,
            DeviceUtils.getPlayerServiceClass(), SinglePlayQueue(stream), true)
        intent.putExtra(HISTORY_REPLAY, true)
        intent.putExtra(PlayerIntentConstants.PLAYER_TYPE, player.playerType.ordinal)
        historyReplayInProgress = fromPrevious
        try {
            player.handleIntent(intent)
        } finally {
            historyReplayInProgress = false
        }
    }

    /** Return true when Play is waiting for a failed/pending page to be explicitly retried. */
    fun retryPending(): Boolean {
        val queue = player.playQueue as? PersistentPlayQueue ?: return false
        if (queue.hasBlockedBoundary()) {
            player.simpleExoPlayer.playWhenReady = true
            queue.retryPending()
            return true
        }
        if (policy.retryFailedHead()) {
            player.simpleExoPlayer.playWhenReady = true
            player.reloadPlayQueueManager()
            return true
        }
        return false
    }

    fun shuffleUpcoming() {
        val queue = player.playQueue as? PersistentPlayQueue ?: return
        val activity = player.parentActivity
        if (activity != null) {
            subscriptions.add(org.schabi.newpipe.player.playqueue.QueueViewActions.materialize(
                activity, activity.supportFragmentManager, queue, true))
        } else {
            player.context.startActivity(android.content.Intent(player.context,
                PlayQueueActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("persistent_queue_shuffle_requested", true))
        }
    }

    fun failure(error: Throwable) {
        Log.e(Player.TAG, "Persistent queue operation failed", error)
        Toast.makeText(player.context, error.localizedMessage ?: player.context.getString(R.string.network_error),
            Toast.LENGTH_LONG).show()
        player.onBufferingFailed()
    }

    fun onDiscontinuity(old: PositionInfo, next: PositionInfo, reason: Int): Boolean {
        if (player.playQueue !is PersistentPlayQueue) return false
        if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) consume(true)
        else if (old.mediaItemIndex == next.mediaItemIndex) checkpoint()
        // Persistent queues never use ExoPlayer's cursor to change membership or mark completion.
        return true
    }

    companion object {
        const val HISTORY_REPLAY = "persistent_queue_history_replay"
    }
}
