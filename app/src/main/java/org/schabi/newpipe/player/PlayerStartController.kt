package org.schabi.newpipe.player

import android.content.Intent
import android.view.View
import android.widget.Toast
import org.schabi.newpipe.player.playqueue.PersistentQueueManager
import org.schabi.newpipe.player.playqueue.PersistentPlayQueue
import com.google.android.exoplayer2.C
import org.schabi.newpipe.R
import org.schabi.newpipe.player.helper.PlayerHelper
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.SerializedCache

/**
 * Owns playback start: resolving a service [Intent] into a start decision (replace, enqueue,
 * enqueue-next, seek-to-timestamp, resume-from-history or fresh init) and the actual playback
 * re-initialization that tears the current session down and binds the new [PlayQueue].
 *
 * [Player] keeps the primitives this controller cannot own: the ExoPlayer instance, the player
 * type and audio-only flags, and the per-player-type view setup. The controller reads and
 * reassigns them through [Player] accessors, exactly as the old inline code did.
 */
class PlayerStartController(private val player: Player) {

    fun handleIntent(intent: Intent) {
        val intentStartupTraceId = PlaybackStartupTrace.fromIntent(intent)
        if (intentStartupTraceId > 0) {
            player.setStartupTraceId(intentStartupTraceId)
            PlaybackStartupTrace.mark(intentStartupTraceId, "service_intent_received")
        }
        // fail fast if no play queue was provided
        val queueCache = intent.getStringExtra(PlayerIntentConstants.PLAY_QUEUE_KEY) ?: return
        var newQueue = SerializedCache.getInstance().take(queueCache, PlayQueue::class.java)
            ?: return

        val enqueue = intent.getBooleanExtra(PlayerIntentConstants.ENQUEUE, false)
        val enqueueNext = intent.getBooleanExtra(PlayerIntentConstants.ENQUEUE_NEXT, false)
        val building = enqueue || enqueueNext
        var firstEnqueue = building && (player.playQueue == null || player.playQueue!!.isEmpty)
        val oldPlayerType = player.playerType
        if (player.isPersistentQueueMode) {
            val owner = PersistentQueueManager.getInstance(player.context)
            firstEnqueue = building && owner.queue.isEmpty
            player.persistentQueueController.checkpoint()
            try {
                newQueue = when {
                    building -> owner.enqueue(newQueue, enqueueNext)
                    newQueue is PersistentPlayQueue -> owner.queue
                    else -> owner.playNow(newQueue)
                }
            } catch (error: Exception) {
                player.persistentQueueController.failure(error)
                return
            }
            player.persistentQueueController.resumeHead(newQueue as PersistentPlayQueue)
            if (!intent.getBooleanExtra(PersistentQueuePlayerController.HISTORY_REPLAY, false)
                && !building) player.persistentQueueController.newSelection()
            if (building) {
                Toast.makeText(player.context,
                    if (enqueueNext) R.string.enqueued_next else R.string.enqueued,
                    Toast.LENGTH_SHORT).show()
                if (player.playQueue === newQueue && !player.exoPlayerIsNull()) return
                // A closed but unfinished persistent queue restores paused for queue building.
                intent.putExtra(PlayerIntentConstants.PLAY_WHEN_READY,
                    QueuePlaybackPolicy.enqueueReady(firstEnqueue, false, false))
            }
        }
        player.setPlayerType(if (firstEnqueue) PlayerService.PlayerType.VIDEO
            else if (building && player.playQueue != null) oldPlayerType
            else PlayerHelper.retrievePlayerTypeFromIntent(intent))
        // We need to setup audioOnly before super(), see "sourceOf"
        player.setAudioOnly(player.audioPlayerSelected())

        val playQueue = player.playQueue

        // Resolve enqueue intents
        if (!player.isPersistentQueueMode && enqueue && playQueue != null) {
            playQueue.append(newQueue.streams)
            return

            // Resolve enqueue next intents
        } else if (!player.isPersistentQueueMode && enqueueNext && playQueue != null
        ) {
            val currentIndex = playQueue.index
            val oldSize = playQueue.size()
            playQueue.append(newQueue.streams)
            newQueue.streams.indices.forEach { offset ->
                playQueue.move(oldSize + offset, currentIndex + 1 + offset)
            }
            return
        }

        val parametersBuilder = player.trackSelector.buildUponParameters()
        parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, player.audioPlayerSelected())
        parametersBuilder.setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, player.audioPlayerSelected())
        val preferredAudioLanguage = player.prefs.getString(
            player.context.getString(R.string.preferred_audio_language_key), "original")!!
        if ("original" == preferredAudioLanguage) {
            parametersBuilder.setPreferredAudioLanguages()
        } else {
            parametersBuilder.setPreferredAudioLanguages(preferredAudioLanguage)
        }
        player.trackSelector.setParameters(parametersBuilder)

        val savedParameters = PlayerHelper.retrievePlaybackParametersFromPrefs(player)
        val playbackSpeed = savedParameters.speed
        val playbackPitch = savedParameters.pitch
        val playbackSkipSilence = player.prefs.getBoolean(
            player.context.getString(R.string.playback_skip_silence_key),
            player.playbackSkipSilence,
        )

        val samePlayQueue = playQueue != null && playQueue == newQueue
        val repeatMode = RepeatShuffleController.fromExoPlayerRepeatMode(
            intent.getIntExtra(
                PlayerIntentConstants.REPEAT_MODE,
                RepeatShuffleController.toExoPlayerRepeatMode(player.repeatMode),
            )
        )
        val playWhenReady = intent.getBooleanExtra(
            PlayerIntentConstants.PLAY_WHEN_READY, true
        )
        val isMuted = intent.getBooleanExtra(
            PlayerIntentConstants.IS_MUTED, player.isMuted()
        )

        /*
         * TODO As seen in #7427 this does not work:
         * There are 3 situations when playback shouldn't be started from scratch (zero timestamp):
         * 1. User pressed on a timestamp link and the same video should be rewound to the timestamp
         * 2. User changed a player from, for example. main to popup, or from audio to main, etc
         * 3. User chose to resume a video based on a saved timestamp from history of played videos
         * In those cases time will be saved because re-init of the play queue is a not an instant
         *  task and requires network calls
         * */
        // seek to timestamp if stream is already playing
        if (!player.isPersistentQueueMode && !player.exoPlayerIsNull()
            && newQueue.size() == 1 && newQueue.getItem() != null
            && playQueue != null && playQueue.size() == 1 && playQueue.getItem() != null
            && newQueue.getItem()!!.url == playQueue.getItem()!!.url
            && newQueue.getRecoveryPosition(newQueue.getItem()!!) != PlayQueue.RECOVERY_UNSET
        ) {
            // Player can have state = IDLE when playback is stopped or failed
            // and we should retry in this case
            if (player.simpleExoPlayer.playbackState
                == com.google.android.exoplayer2.Player.STATE_IDLE
            ) {
                player.simpleExoPlayer.prepare()
            }
            if (player.shouldSeek()) {
                player.simpleExoPlayer.seekTo(
                    playQueue.index,
                    newQueue.getRecoveryPosition(newQueue.getItem()!!),
                )
            }
            player.simpleExoPlayer.setPlayWhenReady(playWhenReady)
        } else if (!player.exoPlayerIsNull()
            && samePlayQueue
            && playQueue != null
            && !playQueue.isDisposed
        ) {
            // Do not re-init the same PlayQueue. Save time
            // Player can have state = IDLE when playback is stopped or failed
            // and we should retry in this case
            if (player.simpleExoPlayer.playbackState
                == com.google.android.exoplayer2.Player.STATE_IDLE
            ) {
                player.simpleExoPlayer.prepare()
            }
            player.simpleExoPlayer.setPlayWhenReady(playWhenReady)
        } else if (!player.isPersistentQueueMode
            && intent.getBooleanExtra(PlayerIntentConstants.RESUME_PLAYBACK, false)
            && PlayerHelper.isPlaybackResumeEnabled(player)
            && !samePlayQueue
            && !newQueue.isEmpty()
            && newQueue.getItem() != null
            && newQueue.getRecoveryPosition(newQueue.getItem()!!) == PlayQueue.RECOVERY_UNSET
        ) {
            player.historyController.startPlaybackWithSavedProgress(
                newQueue.getItem()!!
            ) { savedMillis ->
                if (savedMillis != null) {
                    // resume playback only if the stream was not played to the end
                    newQueue.setRecovery(newQueue.index, savedMillis)
                }
                initPlayback(newQueue, repeatMode, playbackSpeed, playbackPitch,
                    playbackSkipSilence, playWhenReady, isMuted)
            }
        } else {
            // Good to go...
            // In a case of equal PlayQueues we can re-init old one but only when it is disposed
            initPlayback(
                if (samePlayQueue) playQueue!! else newQueue, repeatMode, playbackSpeed,
                playbackPitch, playbackSkipSilence, playWhenReady, isMuted
            )
        }

        if (oldPlayerType != player.playerType && player.playQueue != null) {
            player.setRecovery()
            player.reloadPlayQueueManager()
        }

        player.setupElementsVisibility()
        player.setupElementsSize()

        if (player.audioPlayerSelected()) {
            player.service.removeViewFromParent()
        } else if (player.popupPlayerSelected()) {
            player.binding.root.visibility = View.VISIBLE
            player.initPopup()
            player.initPopupCloseOverlay()
            player.binding.playPauseButton.requestFocus()
        } else {
            player.binding.root.visibility = View.VISIBLE
            player.initVideoPlayer()
            player.closeItemsList()
            // Android TV: without it focus will frame the whole player
            player.binding.playPauseButton.requestFocus()

            // Note: This is for automatically playing (when "Resume playback" is off), see #6179
            if (player.getPlayWhenReady()) {
                player.play()
            } else {
                player.pause()
            }
        }
        NavigationHelper.sendPlayerStartedEvent(player.context)
        if (firstEnqueue) NavigationHelper.playOnMainPlayer(player.context, newQueue, true)
    }

    private fun initPlayback(
        queue: PlayQueue,
        repeatMode: RepeatMode,
        playbackSpeed: Float,
        playbackPitch: Float,
        playbackSkipSilence: Boolean,
        playOnReady: Boolean,
        muted: Boolean,
    ) {
        PlaybackStartupTrace.mark(player.startupTraceId, "player_init_started")
        player.destroyPlayer()
        player.initPlayer(playOnReady)

        player.playQueue = queue
        queue.init()
        player.reloadPlayQueueManager()
        PlaybackStartupTrace.mark(player.startupTraceId, "media_source_manager_ready")

        player.queueController.initAdapters()

        player.simpleExoPlayer.setVolume(if (muted) 0f else 1f)
        val playQueue = player.playQueue
        if (playQueue != null) {
            player.simpleExoPlayer.setShuffleModeEnabled(playQueue.isShuffled)
            player.setupMediaSession()
        }

        player.setRepeatMode(repeatMode)
        // #6825 - Ensure that the shuffle-button is in the correct state on the UI
        player.repeatShuffleController.updateShuffleButton()
        player.setPlaybackParameters(playbackSpeed, playbackPitch, playbackSkipSilence)

        player.notifyQueueUpdateToListeners()
    }
}
