package org.schabi.newpipe.info_list;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.about.AboutActivity;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.LocalItemListAdapter;
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.player.PlayerIntentConstants;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.util.SerializedCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Tests swipe settings and production callback sequencing without starting a player service. */
@RunWith(AndroidJUnit4.class)
public class VideoSwipeActionsTest {
    private Context context;
    private SharedPreferences prefs;
    private final Map<String, Object> original = new HashMap<>();
    private static final String[] KEYS = {"video_swipe_enabled", "video_swipe_left_action",
        "video_swipe_right_action", "video_swipe_left_action_playlist",
        "video_swipe_right_action_playlist", "video_swipe_left_action_history",
        "video_swipe_right_action_history", "local_playlist_swipe_action_key"};

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final SharedPreferences.Editor editor = prefs.edit();
        for (final String key : KEYS) {
            original.put(key, prefs.getAll().get(key));
            editor.remove(key);
        }
        editor.commit();
    }

    @After
    public void restorePreferences() {
        final SharedPreferences.Editor editor = prefs.edit();
        for (final String key : KEYS) {
            final Object value = original.get(key);
            if (value instanceof Boolean) {
                editor.putBoolean(key, (Boolean) value);
            } else if (value instanceof String) {
                editor.putString(key, (String) value);
            } else {
                editor.remove(key);
            }
        }
        editor.commit();
    }

    private StreamInfoItem video(final String title) {
        return new StreamInfoItem(0, "https://example.com/" + title, title, StreamType.VIDEO_STREAM);
    }

    @Test
    public void sparseVideoWithoutUploaderCanBecomeDistinctQueueOccurrences() {
        final StreamInfoItem sparse = video("sparse");
        assertNull(sparse.getUploaderName());
        final var first = org.schabi.newpipe.player.mediaitem.PlayerMediaItem.forStreamInfoItem(sparse);
        final var second = org.schabi.newpipe.player.mediaitem.PlayerMediaItem.forStreamInfoItem(sparse);
        assertEquals("", first.getUploaderName());
        assertEquals(sparse.getUrl(), first.getUrl());
        assertEquals(sparse.getName(), first.getTitle());
        assertEquals(-1, first.getDuration());
        assertNull(first.getUploaderUrl());
        org.junit.Assert.assertNotEquals(first.getUuid(), second.getUuid());
        assertNull(sparse.getUploaderName());
    }

    @Test
    public void queueLinkFromBookmarkedHomeTabUsesActivityContainer() {
        assertQueueLinkNavigation(true);
    }

    @Test
    public void explicitVideoOpenWaitsForMetadataWithoutStartingAnUnattachedService() {
        onMain(() -> {
            final var fragment = new org.schabi.newpipe.fragments.detail.VideoDetailFragment();
            // No metadata, activity or service yet: an explicit request must survive this
            // startup order instead of trying to bind before extraction has completed.
            fragment.openVideoPlayer(true);
            try {
                final Field pending = fragment.getClass().getDeclaredField("pendingVideoOpen");
                final Field fullscreen = fragment.getClass().getDeclaredField("pendingVideoFullscreen");
                pending.setAccessible(true);
                fullscreen.setAccessible(true);
                assertEquals(true, pending.getBoolean(fragment));
                assertEquals(true, fullscreen.getBoolean(fragment));
                fragment.openVideoPlayer(false);
                assertEquals(true, pending.getBoolean(fragment));
                assertEquals(false, fullscreen.getBoolean(fragment));
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
    }

    @Test
    public void playerMetadataCompletesAnExplicitPendingVideoOpenOnce() {
        onMain(() -> org.schabi.newpipe.player.helper.PlayerHolder.getInstance().stopService());
        final Activity launched = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, org.schabi.newpipe.MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            onMain(() -> {
                final var activity = (FragmentActivity) launched;
                final var queue = new org.schabi.newpipe.player.playqueue.SinglePlayQueue(video("pending"));
                // The collapsed fragment has no initial URL and does not start network extraction.
                final var fragment = org.schabi.newpipe.fragments.detail.VideoDetailFragment
                        .getInstanceInCollapsedState();
                final var manager = activity.getSupportFragmentManager();
                manager.beginTransaction().replace(R.id.fragment_player_holder, fragment).commitNow();
                try {
                    final Class<?> type = fragment.getClass();
                    final Field pending = type.getDeclaredField("pendingVideoOpen");
                    final Field autoplay = type.getDeclaredField("autoPlayEnabled");
                    pending.setAccessible(true);
                    autoplay.setAccessible(true);
                    writeField(fragment, "playQueue", queue);
                    fragment.setAutoPlay(true);
                    fragment.openVideoPlayer(true);
                    assertEquals(true, pending.getBoolean(fragment));
                    final var info = new org.schabi.newpipe.extractor.stream.StreamInfo(
                            0, "https://example.com/pending", "https://example.com/pending",
                            StreamType.VIDEO_STREAM, "pending", "pending", 0);
                    info.setUploaderName("test uploader");
                    info.setThumbnailUrl("");
                    fragment.onMetadataUpdate(info, queue);
                    assertEquals("Player-delivered metadata must complete the pending open",
                            false, pending.getBoolean(fragment));
                    assertEquals("Metadata must retain the explicit playback request",
                            true, autoplay.getBoolean(fragment));
                    fragment.onMetadataUpdate(info, queue);
                    assertEquals(false, pending.getBoolean(fragment));
                } catch (final ReflectiveOperationException error) {
                    throw new AssertionError(error);
                } finally {
                    manager.beginTransaction().remove(fragment).commitNow();
                }
            });
        } finally {
            onMain(() -> {
                launched.finish();
                org.schabi.newpipe.player.helper.PlayerHolder.getInstance().stopService();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }

    @Test
    public void retainedDetailViewModelKeepsPausedTransportWithoutNewPlaybackIntent() {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.requestExplicitPlayback();
            fixture.complete(fixture.info("A"), fixture.generation());
            fixture.assertOpened("A", true);
            fixture.player.playPause();
            assertEquals(false, fixture.player.getPlayWhenReady());
            final Object retained = readField(fixture.fragment, "vm");

            fixture.recreateRetainingViewModel();
            assertSame(retained, readField(fixture.fragment, "vm"));
            // Rebinding reports matching metadata, so the autoplay flag is still true.
            fixture.fragment.onMetadataUpdate(fixture.info("A"), fixture.queue());
            assertEquals(true, readField(fixture.fragment, "autoPlayEnabled"));
            fixture.fragment.restoreRetainedView();
            assertEquals("Retained-info rendering must not send PLAY_WHEN_READY=true",
                    1, fixture.activity.intents.size());
            assertEquals(false, fixture.player.getPlayWhenReady());
        });
    }

    @Test
    public void retainedDetailViewModelStillCompletesExplicitPendingOpenOnce() {
        withVideoOpen(fixture -> {
            fixture.requestExplicitPlayback();
            writeField(fixture.fragment, "binding", null);
            // Production metadata arrives with no view, then the same model gets a new view.
            fixture.fragment.onMetadataUpdate(fixture.info("A"), fixture.queue());
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertEquals(0, fixture.activity.intents.size());
            final Object retained = readField(fixture.fragment, "vm");
            fixture.recreateRetainingViewModel();
            assertSame(retained, readField(fixture.fragment, "vm"));
            fixture.fragment.restoreRetainedView();
            fixture.assertOpened("A", true);
            fixture.fragment.restoreRetainedView();
            assertEquals(1, fixture.activity.intents.size());
        });
    }

    @Test
    public void selectingAnotherVideoCancelsDeferredFullscreenWithoutAutoplayingIt() {
        withVideoOpen(fixture -> {
            fixture.fragment.setAutoPlay(true);
            fixture.fragment.openVideoPlayer(true);
            fixture.fragment.setAutoPlay(false);
            fixture.select("B");
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
            fixture.complete(fixture.info("B"), fixture.generation());
            assertEquals(0, fixture.activity.intents.size());
            assertEquals(false, readField(fixture.fragment, "pendingVideoFullscreen"));
        });
    }

    @Test
    public void closingDetailCancelsDeferredOpenAndIgnoresBothLateMetadataPaths() {
        withVideoOpen(fixture -> {
            fixture.fragment.openVideoPlayer(true);
            final long generation = fixture.generation();
            final PlayQueue oldQueue = fixture.queue();
            invokeDetail(fixture.fragment, "cleanUp");
            fixture.complete(fixture.info("A"), generation);
            fixture.fragment.onMetadataUpdate(fixture.info("A"), oldQueue);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
        });
    }

    @Test
    public void staleWorkerCannotConsumeANewRequestEvenWhenTheSameVideoIsReselected() {
        withVideoOpen(fixture -> {
            fixture.fragment.openVideoPlayer(true);
            final long firstGeneration = fixture.generation();
            fixture.select("B");
            fixture.select("A");
            fixture.fragment.setAutoPlay(true);
            fixture.fragment.openVideoPlayer(false);
            fixture.complete(fixture.info("A"), firstGeneration);
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            fixture.complete(fixture.info("A"), fixture.generation());
            fixture.assertOpened("A", true);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
            assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED,
                    readField(fixture.fragment, "bottomSheetState"));
        });
    }

    @Test
    public void workerMetadataCompletesDeferredFullscreenWhenExtractionCanonicalizesTheUrl() {
        withVideoOpen(fixture -> {
            fixture.fragment.openVideoPlayer(true);
            final var canonical = new org.schabi.newpipe.extractor.stream.StreamInfo(
                    0, "https://example.com/canonical-A", fixture.info("A").getUrl(),
                    StreamType.VIDEO_STREAM, "A", "A", 0);
            fixture.complete(canonical, fixture.generation());
            fixture.assertOpened("A", true);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
            assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED,
                    readField(fixture.fragment, "bottomSheetState"));
        });
    }

    @Test
    public void unrelatedPlayerMetadataCannotConsumeDeferredOpenForTheSelectedStream() {
        withVideoOpen(fixture -> {
            fixture.fragment.openVideoPlayer(true);
            fixture.fragment.onMetadataUpdate(fixture.info("B"), fixture.queue());
            final var otherService = new org.schabi.newpipe.extractor.stream.StreamInfo(
                    1, fixture.info("A").getUrl(), fixture.info("A").getUrl(),
                    StreamType.VIDEO_STREAM, "A", "A", 0);
            fixture.fragment.onMetadataUpdate(otherService, fixture.queue());
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            final var selected = fixture.info("A");
            fixture.fragment.onMetadataUpdate(selected, fixture.queue());
            fixture.assertOpened("A", true);
            assertEquals(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED,
                    readField(fixture.fragment, "bottomSheetState"));
            fixture.fragment.onMetadataUpdate(selected, fixture.queue());
            assertEquals("The same player metadata must not open twice",
                    1, fixture.activity.intents.size());
        });
    }

    @Test
    public void workerMetadataCompletesDeferredSwitchWithLatestNotificationPause() {
        assertDeferredSwitchState(false, true);
    }

    @Test
    public void playerMetadataCompletesDeferredSwitchWithLatestNotificationPause() {
        assertDeferredSwitchState(true, true);
    }

    @Test
    public void workerMetadataKeepsRequestedPlaybackWhileTheSourceIsBuffering() {
        assertDeferredSwitchState(false, false);
    }

    @Test
    public void playerMetadataKeepsRequestedPlaybackWhileTheSourceIsBuffering() {
        assertDeferredSwitchState(true, false);
    }

    @Test
    public void serviceConnectionKeepsTheDeferredSwitchOriginAfterNotificationPause() {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.fragment.switchToVideoPlayer(false);
            fixture.player.playPause();
            fixture.fragment.onServiceConnected(fixture.player,
                    (org.schabi.newpipe.player.mediasession.PlayerServiceInterface)
                            readField(fixture.fragment, "playerService"), true);
            fixture.complete(fixture.info("A"), fixture.generation());
            fixture.assertOpened("A", false);
        });
    }

    @Test
    public void workerMetadataKeepsExplicitPlayBDespiteUnrelatedPausedA() {
        assertExplicitPlayIgnoresUnrelatedPause(false);
    }

    @Test
    public void playerMetadataKeepsExplicitPlayBDespiteUnrelatedPausedA() {
        assertExplicitPlayIgnoresUnrelatedPause(true);
    }

    private void assertExplicitPlayIgnoresUnrelatedPause(final boolean playerMetadata) {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.player.playPause();
            writeField(fixture.player, "playerType",
                    org.schabi.newpipe.player.PlayerService.PlayerType.AUDIO);
            final PlayQueue oldQueue = fixture.queue();
            fixture.select("B");
            fixture.requestExplicitPlayback();
            // A reconnect must not replace the new detail selection with the old queue.
            fixture.fragment.onQueueUpdate(oldQueue);
            fixture.fragment.onMetadataUpdate(fixture.info("A"), oldQueue);
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertEquals(fixture.info("B").getUrl(), fixture.queue().getItem().getUrl());
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            if (playerMetadata) {
                fixture.fragment.onMetadataUpdate(fixture.info("B"), fixture.queue());
            } else {
                fixture.complete(fixture.info("B"), fixture.generation());
            }
            fixture.assertOpened("B", true);
        });
    }

    @Test
    public void explicitPlayBHonorsLaterPauseAfterThePlayerAdoptsB() {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.select("B");
            fixture.requestExplicitPlayback();
            writeField(fixture.player, "playQueue", fixture.queue());
            fixture.fragment.onQueueUpdate(fixture.queue());
            fixture.player.playPause();
            fixture.complete(fixture.info("B"), fixture.generation());
            fixture.assertOpened("B", false);
        });
    }

    @Test
    public void explicitPlayBReplacesAnUnrelatedQueueReportedBeforeTheThumbnailTap() {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.player.playPause();
            final PlayQueue oldQueue = fixture.queue();
            fixture.select("B");
            fixture.fragment.onQueueUpdate(oldQueue);
            fixture.requestExplicitPlayback();
            fixture.complete(fixture.info("B"), fixture.generation());
            fixture.assertOpened("B", true);
        });
    }

    @Test
    public void explicitPlayOfPausedCurrentOccurrenceKeepsItsNewPlayIntent() {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.player.playPause();
            fixture.requestExplicitPlayback();
            fixture.complete(fixture.info("A"), fixture.generation());
            fixture.assertOpened("A", true);
        });
    }

    @Test
    public void explicitPlayOfCurrentOccurrenceHonorsProductionPauseDuringInitialBuffering() {
        assertInitialBufferingTransportPause(false);
        assertInitialBufferingTransportPause(true);
    }

    private void assertInitialBufferingTransportPause(final boolean playerMetadata) {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.registerExoPlayerEventAdapter();
            fixture.player.playPause();
            fixture.requestExplicitPlayback();
            final String before = fixture.player.getPlayWhenReadyChangeToken(
                    fixture.queue().getItem().getUuid());
            assertNotNull(before);
            // Actual notification/UI transport -> ExoPlayer -> registered production adapter.
            // No prepared-state playback notification (or direct fragment callback) is needed.
            fixture.player.playPause();
            fixture.player.playPause();
            assertEquals(com.google.android.exoplayer2.Player.STATE_BUFFERING,
                    fixture.exo.getPlaybackState());
            assertEquals(false, readField(fixture.player, "isPrepared"));
            assertEquals(false, fixture.player.getPlayWhenReady());
            org.junit.Assert.assertNotEquals(before, fixture.player.getPlayWhenReadyChangeToken(
                    fixture.queue().getItem().getUuid()));
            if (playerMetadata) {
                fixture.fragment.onMetadataUpdate(fixture.info("A"), fixture.queue());
            } else {
                fixture.complete(fixture.info("A"), fixture.generation());
            }
            fixture.assertOpened("A", false);
        });
    }

    @Test
    public void explicitReplayDoesNotInheritPauseFromAnotherOccurrenceOfTheSameUrl() {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.player.playPause();
            final PlayQueue oldQueue = fixture.queue();
            fixture.select("A");
            org.junit.Assert.assertNotEquals(oldQueue.getItem().getUuid(),
                    fixture.queue().getItem().getUuid());
            fixture.requestExplicitPlayback();
            fixture.fragment.onQueueUpdate(oldQueue);
            fixture.fragment.onMetadataUpdate(fixture.info("A"), oldQueue);
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            fixture.complete(fixture.info("A"), fixture.generation());
            fixture.assertOpened("A", true);
        });
    }

    @Test
    public void collapsedQueueSwitchFollowsNextBeforeEitherVideoHasMetadata() {
        assertCollapsedQueueSwitchFollowsNext(true);
    }

    @Test
    public void collapsedQueueSwitchDetectsNextFromMetadataWithoutAQueueUpdate() {
        assertCollapsedQueueSwitchFollowsNext(false);
    }

    private void assertCollapsedQueueSwitchFollowsNext(final boolean queueUpdate) {
        withVideoOpen(fixture -> {
            fixture.prepareColdQueue("A", "B");
            fixture.fragment.switchToVideoPlayer(false);
            final PlayQueue queue = fixture.queue();
            queue.remove(0); // Notification Next consumes A in this same queue instance.
            if (queueUpdate) fixture.fragment.onQueueUpdate(queue);
            fixture.fragment.onMetadataUpdate(fixture.info("A"), queue);
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            fixture.fragment.onMetadataUpdate(fixture.info("B"), queue);
            fixture.assertOpened("B", true);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
        });
    }

    @Test
    public void pauseCheckpointsOnlyTheCurrentOccurrenceAfterHeadRemoval() {
        for (final boolean duplicate : new boolean[]{false, true}) {
            withVideoOpen(fixture -> {
                final String next = duplicate ? "A" : "B";
                fixture.prepareColdQueue("A", next);
                fixture.connectBufferingPlayer();
                final PlayQueue queue = fixture.queue();
                fixture.setPlayerMetadata(org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                        .forStreamInfo(fixture.info("A")).withUuid(queue.getItem().getUuid()));
                queue.setRecovery(1, 42_000L);
                queue.remove(0);

                // Queue and ExoPlayer indices are both zero, but metadata is still the old head.
                fixture.player.playPause();
                assertEquals(42_000L, queue.getRecoveryPosition(0));

                // Matching metadata must still checkpoint the actual new occurrence's position.
                fixture.setPlayerMetadata(org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                        .forStreamInfo(fixture.info(next)).withUuid(queue.getItem().getUuid()));
                fixture.exo.seekTo(13_000L);
                fixture.player.saveStreamProgressState();
                assertEquals(13_000L, queue.getRecoveryPosition(0));
            });
        }
    }

    @Test
    public void recreatedBackgroundSwitchFollowsTheLiveQueuesAdvancedHead() {
        assertRecreatedSwitchFollowsNext(false);
    }

    @Test
    public void recreatedPopupSwitchRejectsOldMetadataForTheDuplicateOccurrence() {
        assertRecreatedSwitchFollowsNext(true);
    }

    private void assertRecreatedSwitchFollowsNext(final boolean duplicate) {
        withVideoOpen(fixture -> {
            final String next = duplicate ? "A" : "B";
            fixture.prepareColdQueue("A", next);
            fixture.connectBufferingPlayer();
            writeField(fixture.player, "playerType", duplicate
                    ? org.schabi.newpipe.player.PlayerService.PlayerType.POPUP
                    : org.schabi.newpipe.player.PlayerService.PlayerType.AUDIO);
            final PlayQueue liveQueue = fixture.queue();
            final var oldHead = liveQueue.getItem();
            liveQueue.setRecovery(1, 42_000L);
            fixture.setPlayerMetadata(org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                    .forStreamInfo(fixture.info("A")).withUuid(oldHead.getUuid()));
            fixture.fragment.switchToVideoPlayer(false);
            final android.os.Bundle state = fixture.saveState();
            fixture.recreate(state);
            liveQueue.remove(0); // Next while the replacement fragment has no service connection.
            fixture.player.playPause();
            assertSame(liveQueue, readField(fixture.fragment, "pendingVideoQueue"));
            if (duplicate) {
                // The restored A worker cannot consume the next occurrence's switch, even
                // before reconnect/queue callbacks and when the URLs are identical.
                fixture.complete(fixture.info("A"), fixture.generation());
                assertEquals(liveQueue.getItem().getUuid(),
                        readField(fixture.fragment, "pendingVideoOccurrenceId"));
            }
            fixture.reconnect();
            fixture.fragment.onMetadataUpdate(fixture.info("A"), liveQueue);
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            final var nextMetadata = org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                    .forStreamInfo(fixture.info(next)).withUuid(liveQueue.getItem().getUuid());
            if (duplicate) {
                fixture.fragment.onQueueUpdate(liveQueue);
                fixture.deliverPlayerMetadata(nextMetadata);
            } else {
                // Valid B metadata can be the first callback reporting the advanced head.
                fixture.setPlayerMetadata(nextMetadata);
                fixture.fragment.onMetadataUpdate(fixture.info(next), liveQueue);
                fixture.fragment.onQueueUpdate(liveQueue);
            }
            fixture.assertOpened(next, false);
            assertSame(liveQueue, fixture.queue());
            assertEquals(42_000L, fixture.queue().getRecoveryPosition(0));
        });
    }

    @Test
    public void recreatedSwitchRejectsAnotherQueueEvenWithTheSameOccurrenceIds() {
        withVideoOpen(fixture -> {
            fixture.prepareColdQueue("A", "B");
            fixture.fragment.switchToVideoPlayer(false);
            final android.os.Bundle state = fixture.saveState();
            final PlayQueue originalQueue = fixture.queue();
            final PlayQueue unrelated;
            try {
                unrelated = org.schabi.newpipe.util.SerializedUtils.clone(originalQueue, PlayQueue.class);
            } catch (final Exception error) {
                throw new AssertionError(error);
            }
            org.junit.Assert.assertNotSame(originalQueue, unrelated);
            assertEquals(originalQueue.getItem().getUuid(), unrelated.getItem().getUuid());
            fixture.recreate(state);
            fixture.fragment.onMetadataUpdate(fixture.info("A"), unrelated);
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            fixture.fragment.onQueueUpdate(unrelated);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
            fixture.complete(fixture.info("A"), fixture.generation());
            assertEquals(0, fixture.activity.intents.size());

            // An absent/dead-process Binder must not resurrect the saved pending autoplay.
            state.putBinder("pendingVideoQueueReference", new android.os.Binder());
            fixture.recreate(state);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
            assertEquals(false, readField(fixture.fragment, "autoPlayEnabled"));
            fixture.complete(fixture.info("A"), fixture.generation());
            assertEquals(0, fixture.activity.intents.size());
        });
    }

    @Test
    public void nextSupersedesAWorkerBeforeCompletingTheSwitchWithBMetadata() {
        withVideoOpen(fixture -> {
            fixture.queue().append(org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                    .forQueueItem(fixture.info("B")));
            fixture.fragment.switchToVideoPlayer(false);
            final long generation = fixture.generation();
            fixture.queue().remove(0);
            fixture.fragment.onQueueUpdate(fixture.queue());
            fixture.complete(fixture.info("A"), generation);
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            fixture.fragment.onMetadataUpdate(fixture.info("B"), fixture.queue());
            fixture.assertOpened("B", true);
        });
    }

    @Test
    public void nextToDuplicateUrlRejectsThePreviousOccurrencesWorker() {
        withVideoOpen(fixture -> {
            fixture.queue().append(org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                    .forQueueItem(fixture.info("A")));
            fixture.fragment.switchToVideoPlayer(false);
            final long generation = fixture.generation();
            fixture.queue().remove(0);
            // Even without a queue callback the old worker must not consume the new request.
            fixture.complete(fixture.info("A"), generation);
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            fixture.fragment.onMetadataUpdate(fixture.info("A"), fixture.queue());
            fixture.assertOpened("A", true);
        });
    }

    @Test
    public void nextToDuplicateUrlWaitsForMetadataFromTheCurrentOccurrence() {
        withVideoOpen(fixture -> {
            fixture.prepareColdQueue("A", "A");
            fixture.connectBufferingPlayer();
            final var oldHead = fixture.queue().getItem();
            fixture.setPlayerMetadata(org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                    .forStreamInfo(fixture.info("A")).withUuid(oldHead.getUuid()));
            fixture.fragment.switchToVideoPlayer(false);
            fixture.queue().remove(0);
            fixture.fragment.onQueueUpdate(fixture.queue());
            fixture.fragment.onMetadataUpdate(fixture.info("A"), fixture.queue());
            assertEquals(true, readField(fixture.fragment, "pendingVideoOpen"));
            assertNull(readField(fixture.fragment, "currentInfo"));
            assertEquals(0, fixture.activity.intents.size());
            // Exercise the production Exo metadata event and listener fan-out. A duplicate URL
            // must emit its new occurrence even though its stream info did not change.
            fixture.deliverPlayerMetadata(org.schabi.newpipe.player.mediaitem.PlayerMediaItem
                    .forStreamInfo(fixture.info("A")).withUuid(fixture.queue().getItem().getUuid()));
            fixture.assertOpened("A", true);
        });
    }

    @Test
    public void playerMetadataCompletesDeferredSwitchWithACanonicalUrl() {
        withVideoOpen(fixture -> {
            fixture.fragment.switchToVideoPlayer(false);
            final var canonical = new org.schabi.newpipe.extractor.stream.StreamInfo(
                    0, "https://example.com/canonical-A", fixture.info("A").getUrl(),
                    StreamType.VIDEO_STREAM, "A", "A", 0);
            canonical.setUploaderName("test uploader");
            canonical.setThumbnailUrl("");
            fixture.fragment.onMetadataUpdate(canonical, fixture.queue());
            fixture.assertOpened("A", true);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
        });
    }

    @Test
    public void serviceStopCancelsDeferredOpenWithoutLateWorkerAutoplay() {
        withVideoOpen(fixture -> {
            fixture.fragment.setAutoPlay(true);
            fixture.fragment.openVideoPlayer(true);
            fixture.fragment.onServiceStopped();
            fixture.complete(fixture.info("A"), fixture.generation());
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
            assertEquals(0, fixture.activity.intents.size());
        });
    }

    private void assertDeferredSwitchState(final boolean playerMetadata, final boolean pause) {
        withVideoOpen(fixture -> {
            fixture.connectBufferingPlayer();
            fixture.fragment.setAutoPlay(true);
            fixture.fragment.switchToVideoPlayer(false);
            if (pause) {
                // The notification's play/pause action uses this production transport method.
                fixture.player.playPause();
            }
            assertEquals(!pause, fixture.player.getPlayWhenReady());
            assertEquals(false, fixture.player.isPlaying());
            if (playerMetadata) {
                fixture.fragment.onMetadataUpdate(fixture.info("A"), fixture.queue());
            } else {
                fixture.complete(fixture.info("A"), fixture.generation());
            }
            fixture.assertOpened("A", !pause);
            assertEquals(false, readField(fixture.fragment, "pendingVideoOpen"));
        });
    }

    @Test
    public void queueLinkFromStandaloneBookmarksUsesActivityContainer() {
        assertQueueLinkNavigation(false);
    }

    @Test
    public void queueViewConnectsToColdPlayerWithoutStartingPlayback() {
        final String key = "persistent_queue_enabled";
        final boolean had = prefs.contains(key);
        final boolean old = prefs.getBoolean(key, true);
        try {
            onMain(() -> {
                org.schabi.newpipe.player.helper.PlayerHolder.getInstance().stopService();
                context.stopService(new Intent(context,
                        org.schabi.newpipe.util.DeviceUtils.getPlayerServiceClass()));
                prefs.edit().putBoolean(key, true).commit();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            withActivity(activity -> {
                final var fragment = new org.schabi.newpipe.local.playlist.PersistentQueueFragment();
                onMain(() -> {
                    final var root = new android.widget.FrameLayout(activity);
                    root.setId(R.id.fragment_holder);
                    activity.setContentView(root);
                    activity.getSupportFragmentManager().beginTransaction()
                            .replace(R.id.fragment_holder, fragment).commitNow();
                });
                final org.schabi.newpipe.player.Player[] connected = {null};
                final long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
                do {
                    onMain(() -> {
                        try {
                            final Field field = fragment.getClass().getDeclaredField("activePlayer");
                            field.setAccessible(true);
                            connected[0] = (org.schabi.newpipe.player.Player) field.get(fragment);
                        } catch (final ReflectiveOperationException error) {
                            throw new AssertionError(error);
                        }
                    });
                    if (connected[0] != null) break;
                    android.os.SystemClock.sleep(25);
                } while (android.os.SystemClock.uptimeMillis() < deadline);
                assertNotNull("A cold queue view must not wait forever for an absent service",
                        connected[0]);
                onMain(() -> {
                    assertEquals("Opening the queue must not start playback",
                            false, connected[0].isPlaying());
                    final var restored = connected[0].getPlayQueue();
                    if (restored != null && !restored.isEmpty()) {
                        assertEquals("An unfinished queue must restore paused",
                                false, connected[0].getPlayWhenReady());
                    }
                    // An empty player's initial playWhenReady flag is not active playback.
                });
            });
        } finally {
            onMain(() -> {
                org.schabi.newpipe.player.helper.PlayerHolder.getInstance().stopService();
                context.stopService(new Intent(context,
                        org.schabi.newpipe.util.DeviceUtils.getPlayerServiceClass()));
                final var editor = prefs.edit();
                if (had) editor.putBoolean(key, old); else editor.remove(key);
                editor.commit();
            });
        }
    }

    @Test
    public void lateAnnouncementDoesNotShowAWindowAfterActivityFinish() {
        final Activity launched = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, org.schabi.newpipe.MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(launched::finish);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onMain(() -> {
            assertEquals(true, launched.isFinishing());
            try {
                final Method show = org.schabi.newpipe.MainActivity.class.getDeclaredMethod(
                        "showAnnouncement", String.class, String.class);
                show.setAccessible(true);
                // Reproduce a download callback already queued when its activity was closed.
                show.invoke(launched, "Late test announcement", "late-test-id");
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
    }

    @Test
    public void queuedLocalLayoutPreferenceNotificationSurvivesFragmentDetach() {
        final String key = context.getString(R.string.list_view_mode_key);
        final boolean had = prefs.contains(key);
        final String old = prefs.getString(key, null);
        try {
            withActivity(activity -> {
                onMain(() -> {
                    final var root = new android.widget.FrameLayout(activity);
                    root.setId(R.id.fragment_holder);
                    activity.setContentView(root);
                    final var manager = activity.getSupportFragmentManager();
                    final var bookmark = new org.schabi.newpipe.local.bookmark.BookmarkFragment();
                    manager.beginTransaction().replace(R.id.fragment_holder, bookmark).commitNow();
                    // Commit off-main while main cannot dispatch: Android captures listeners
                    // now, so unregistering during removal cannot retract the posted callback.
                    final Thread writer = new Thread(() -> prefs.edit().putString(key,
                            "list".equals(old) ? "grid" : "list").commit());
                    writer.start();
                    try {
                        writer.join(5_000);
                        assertEquals(false, writer.isAlive());
                    } catch (final InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    manager.beginTransaction().remove(bookmark).commitNow();
                    assertNull(bookmark.getContext());
                    // Also make failure synchronous on unfixed builds instead of relying only
                    // on a subsequent process crash from Android's pending notification.
                    bookmark.onSharedPreferenceChanged(prefs, key);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            });
        } finally {
            final var editor = prefs.edit();
            if (had) editor.putString(key, old); else editor.remove(key);
            editor.commit();
        }
    }

    private void assertQueueLinkNavigation(final boolean nested) {
        final String key = "persistent_queue_enabled";
        final boolean had = prefs.contains(key);
        final boolean old = prefs.getBoolean(key, true);
        try {
            prefs.edit().putBoolean(key, true).commit();
            withActivity(activity -> onMain(() -> {
                final android.widget.FrameLayout root = new android.widget.FrameLayout(activity);
                root.setId(R.id.fragment_holder);
                activity.setContentView(root);
                final var manager = activity.getSupportFragmentManager();
                final var bookmark = new org.schabi.newpipe.local.bookmark.BookmarkFragment();
                bookmark.useAsFrontPage(nested);
                final Fragment original;
                if (nested) {
                    final var parent = new BookmarkTabHost();
                    manager.beginTransaction().replace(R.id.fragment_holder, parent).commitNow();
                    parent.getChildFragmentManager().beginTransaction()
                            .replace(BookmarkTabHost.CHILD_ID, bookmark).commitNow();
                    original = parent;
                } else {
                    manager.beginTransaction().replace(R.id.fragment_holder, bookmark).commitNow();
                    original = bookmark;
                }
                try {
                    final Field adapterField = org.schabi.newpipe.local.BaseLocalListFragment.class
                            .getDeclaredField("itemListAdapter");
                    adapterField.setAccessible(true);
                    final Field headerField = LocalItemListAdapter.class.getDeclaredField("header");
                    headerField.setAccessible(true);
                    final View link = (View) headerField.get(adapterField.get(bookmark));
                    assertNotNull(link);
                    assertEquals(context.getString(R.string.queue_view_title), ((TextView) link).getText());
                    link.performClick();
                    manager.executePendingTransactions();
                    assertEquals(org.schabi.newpipe.local.playlist.PersistentQueueFragment.class,
                            manager.findFragmentById(R.id.fragment_holder).getClass());
                    assertEquals(1, manager.getBackStackEntryCount());
                    assertEquals(true, manager.popBackStackImmediate());
                    assertSame(original, manager.findFragmentById(R.id.fragment_holder));
                } catch (final ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            }));
        } finally {
            final var editor = prefs.edit();
            if (had) editor.putBoolean(key, old); else editor.remove(key);
            editor.commit();
        }
    }

    public static class BookmarkTabHost extends Fragment {
        static final int CHILD_ID = 0x00fa1234;
        @Override
        public View onCreateView(final android.view.LayoutInflater inflater,
                                 final ViewGroup container, final android.os.Bundle state) {
            final var view = new android.widget.FrameLayout(requireContext());
            view.setId(CHILD_ID);
            return view;
        }
    }

    @Test
    public void newControlsDefaultOnAndExplicitOptOutAndNoneHaveNoFallback() {
        final VideoSwipeActions.Row playlist = new VideoSwipeActions.Row(video("one"), () -> { }, null);
        prefs.edit().putString(context.getString(R.string.local_playlist_swipe_action_key), "disable").commit();
        assertEquals("enqueue_next", VideoSwipeActions.playlistAction(
                context, playlist, ItemTouchHelper.LEFT));
        assertEquals("enqueue", VideoSwipeActions.playlistAction(
                context, playlist, ItemTouchHelper.RIGHT));
        assertEquals(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                VideoSwipeActions.playlistDirections(context, playlist, true));
        assertEquals(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                VideoSwipeActions.directions(context, playlist));
        prefs.edit().putBoolean("video_swipe_enabled", false).commit();
        assertEquals(0, VideoSwipeActions.directions(context, playlist));
        prefs.edit().putBoolean("video_swipe_enabled", true).commit();
        assertEquals(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                VideoSwipeActions.directions(context, playlist));
        prefs.edit().putString("video_swipe_left_action", "none")
                .putString("video_swipe_right_action_playlist", "none").commit();
        assertEquals(0, VideoSwipeActions.directions(context, playlist));
        assertEquals(ItemTouchHelper.RIGHT,
                VideoSwipeActions.directions(context, new VideoSwipeActions.Row(video("one"))));
    }

    @Test
    public void sparseModeSwitchIsSubmittedBeforeFollowingPopulatedVideo() {
        final StreamInfoItem sparse = video("sparse-X");
        sparse.setDuration(-1);
        sparse.setUploaderUrl(null);
        final StreamInfoItem populated = video("populated-Y");
        populated.setDuration(60);
        populated.setUploaderUrl("https://example.com/channel");
        // Cover both single-mode and mixed-mode gestures: no extraction callback may delay X.
        for (final String firstMode : Arrays.asList("background", "popup")) {
            for (final String secondMode : Arrays.asList("background", "popup")) {
                final java.util.List<String> submitted = new java.util.ArrayList<>();
                final java.util.function.BiConsumer<String,
                        org.schabi.newpipe.player.playqueue.SinglePlayQueue> start = (mode, queue) ->
                        submitted.add(mode + ":" + queue.getItem().getUrl());
                assertEquals(true, VideoSwipeActions.dispatchPlayerMode(firstMode, sparse, start));
                assertEquals(Arrays.asList(firstMode + ":" + sparse.getUrl()), submitted);
                assertEquals(true, VideoSwipeActions.dispatchPlayerMode(secondMode, populated, start));
                assertEquals(Arrays.asList(firstMode + ":" + sparse.getUrl(),
                        secondMode + ":" + populated.getUrl()), submitted);
            }
        }
    }

    @Test
    public void legacyPlaylistActionsApplyOnlyAfterExplicitOptOut() {
        final VideoSwipeActions.Row playlist = new VideoSwipeActions.Row(video("one"), () -> { }, null);
        prefs.edit().putBoolean("video_swipe_enabled", false).commit();
        // Explicit opt-outs retain legacy playlist behavior, always physical right.
        assertEquals("legacy_delete", VideoSwipeActions.playlistAction(context, playlist, ItemTouchHelper.RIGHT));
        for (final String legacy : Arrays.asList("delete", "enqueue", "disable")) {
            prefs.edit().putString(context.getString(R.string.local_playlist_swipe_action_key), legacy).commit();
            assertEquals("delete".equals(legacy) ? "legacy_delete"
                            : "enqueue".equals(legacy) ? "enqueue" : "none",
                    VideoSwipeActions.playlistAction(context, playlist, ItemTouchHelper.RIGHT));
            assertEquals("none", VideoSwipeActions.playlistAction(context, playlist, ItemTouchHelper.LEFT));
            assertEquals("disable".equals(legacy) ? 0 : ItemTouchHelper.RIGHT,
                    VideoSwipeActions.playlistDirections(context, playlist, false));
            assertEquals(0, VideoSwipeActions.playlistDirections(context, playlist, true));
        }
        prefs.edit().putBoolean("video_swipe_enabled", true)
                .putString("video_swipe_left_action", "none")
                .putString("video_swipe_right_action", "none").commit();
        for (final String legacy : Arrays.asList("delete", "enqueue", "disable")) {
            prefs.edit().putString(context.getString(R.string.local_playlist_swipe_action_key), legacy).commit();
            assertEquals("none", VideoSwipeActions.playlistAction(context, playlist, ItemTouchHelper.RIGHT));
            assertEquals(0, VideoSwipeActions.playlistDirections(context, playlist, false));
            assertEquals(0, VideoSwipeActions.playlistDirections(context, playlist, true));
        }
        prefs.edit().putString("video_swipe_left_action", "enqueue_next").commit();
        assertEquals(ItemTouchHelper.LEFT, VideoSwipeActions.playlistDirections(context, playlist, true));
    }

    @Test
    public void destructiveChoicesAreConfinedToTheirSourceSettings() {
        final java.util.List<String> general = Arrays.asList(
                context.getResources().getStringArray(R.array.video_swipe_action_values));
        assertEquals(false, general.contains("remove_playlist"));
        assertEquals(false, general.contains("delete_history"));
        prefs.edit().putBoolean("video_swipe_enabled", true)
                .putString("video_swipe_left_action", "none")
                .putString("video_swipe_left_action_history", "delete_history").commit();
        assertEquals(ItemTouchHelper.RIGHT,
                VideoSwipeActions.directions(context, new VideoSwipeActions.Row(video("one"))));
        assertEquals(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                VideoSwipeActions.directions(context,
                        new VideoSwipeActions.Row(video("one"), null, () -> { })));
    }

    @Test
    public void onlineHeadersAndLayoutsDoNotChangeTheTargetVideo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final InfoListAdapter adapter = new InfoListAdapter(context);
            final StreamInfoItem first = video("one");
            final StreamInfoItem second = video("two");
            adapter.setHeaderSupplier(() -> new View(context));
            adapter.addInfoItemList(Arrays.asList(first, second));
            assertNull(adapter.getSwipeItem(-1));
            assertNull(adapter.getSwipeItem(0));
            assertSame(second, adapter.getSwipeItem(2));
            assertNull(adapter.getSwipeItem(3));
            adapter.filter("two");
            assertSame(second, adapter.getSwipeItem(1));
            assertNull(adapter.getSwipeItem(2));
            adapter.filter("");
            for (final ItemViewMode mode : Arrays.asList(ItemViewMode.CARD, ItemViewMode.GRID,
                    ItemViewMode.LARGE_GRID)) {
                adapter.setItemViewMode(mode);
                assertNull(adapter.getSwipeItem(2));
            }
        });
    }

    @Test
    public void localFilterTargetsDisplayedOccurrenceRatherThanUnfilteredIndex() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final LocalItemListAdapter adapter = new LocalItemListAdapter(context);
            final PlaylistStreamEntry first = new PlaylistStreamEntry(new StreamEntity(video("one")), 0, 1, 0);
            final PlaylistStreamEntry second = new PlaylistStreamEntry(new StreamEntity(video("two")), 0, 2, 1);
            adapter.setHeader(new View(context));
            adapter.addItems(Arrays.asList(first, second));
            adapter.filter("two");
            assertNull(adapter.getSwipeItem(0));
            assertSame(second, adapter.getSwipeItem(1));
            assertNull(adapter.getSwipeItem(2));
            adapter.setItemViewMode(ItemViewMode.CARD);
            assertNull(adapter.getSwipeItem(1));
        });
    }

    @Test
    public void sourceCallbacksKeepVideosAndPhysicalDirectionsDuringDeferredCompletion() {
        withActivity(activity -> onMain(() -> {
            final SwipeFixture fixture = new SwipeFixture(activity, false);
            assertDeferredDirections(fixture);
        }));
    }

    @Test
    public void playlistCallbacksKeepOccurrencesAndPhysicalDirectionsDuringDeferredCompletion() {
        withActivity(activity -> onMain(() -> {
            final SwipeFixture fixture = new SwipeFixture(activity, true);
            assertDeferredDirections(fixture);
        }));
    }

    @Test
    public void physicalSourceRowSwipesDispatchCapturedVideosInBothLayoutDirections() {
        assertPhysicalSwipes(false);
    }

    @Test
    public void physicalPlaylistRowSwipesDispatchCapturedOccurrencesInBothLayoutDirections() {
        assertPhysicalSwipes(true);
    }

    @Test
    public void verticalPullThroughEnabledVideoSwipesReachesRefreshCallback() {
        assertVerticalPullRefresh(false);
    }

    @Test
    public void verticalPullThroughRowsWithNoSwipeActionsReachesRefreshCallback() {
        assertVerticalPullRefresh(true);
    }

    /** Real ViewPager -> SwipeRefreshLayout -> RecyclerView dispatch, with production row routing. */
    private void assertVerticalPullRefresh(final boolean noActions) {
        if (noActions) {
            prefs.edit().putString("video_swipe_left_action", "none")
                    .putString("video_swipe_right_action", "none").commit();
        }
        withActivity(activity -> {
            final SwipeFixture[] fixture = new SwipeFixture[1];
            final androidx.viewpager.widget.ViewPager[] pager = new androidx.viewpager.widget.ViewPager[1];
            final int[] refreshes = {0};
            onMain(() -> {
                fixture[0] = new SwipeFixture(activity, false);
                final SourceFragment fragment = new SourceFragment();
                activity.getSupportFragmentManager().beginTransaction().add(fragment, null).commitNow();
                final var refresh = new androidx.swiperefreshlayout.widget.SwipeRefreshLayout(activity);
                refresh.addView(fixture[0].list);
                refresh.setOnRefreshListener(() -> refreshes[0]++);
                VideoSwipeActions.protectTabGesture(fragment, fixture[0].list,
                        position -> position >= 0 && position < fixture[0].rows.size()
                                ? fixture[0].rows.get(position) : null);
                new ItemTouchHelper(fixture[0].callback).attachToRecyclerView(fixture[0].list);
                pager[0] = new androidx.viewpager.widget.ViewPager(activity);
                pager[0].setAdapter(new androidx.viewpager.widget.PagerAdapter() {
                    @Override public int getCount() { return 1; }
                    @Override public boolean isViewFromObject(final View view, final Object object) {
                        return view == object;
                    }
                    @Override public Object instantiateItem(final ViewGroup container, final int position) {
                        container.addView(refresh);
                        return refresh;
                    }
                    @Override public void destroyItem(final ViewGroup container, final int position,
                                                      final Object object) {
                        container.removeView((View) object);
                    }
                });
                activity.setContentView(pager[0]);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onMain(() -> {
                final View row = fixture[0].list.findViewHolderForAdapterPosition(0).itemView;
                final int[] rowLocation = new int[2];
                final int[] pagerLocation = new int[2];
                row.getLocationInWindow(rowLocation);
                pager[0].getLocationInWindow(pagerLocation);
                final float x = rowLocation[0] - pagerLocation[0] + row.getWidth() / 2f;
                final float startY = rowLocation[1] - pagerLocation[1] + row.getHeight() / 2f;
                final float distance = pager[0].getHeight() * 0.75f;
                final long down = android.os.SystemClock.uptimeMillis();
                for (int i = 0; i <= 24; i++) {
                    final int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN
                            : i == 24 ? android.view.MotionEvent.ACTION_UP
                            : android.view.MotionEvent.ACTION_MOVE;
                    final var event = android.view.MotionEvent.obtain(down, down + i * 16L,
                            action, x, startY + distance * i / 24f, 0);
                    pager[0].dispatchTouchEvent(event);
                    event.recycle();
                }
            });
            // SwipeRefreshLayout invokes its listener after its settle animation.
            final long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
            final int[] actual = {0};
            do {
                onMain(() -> actual[0] = refreshes[0]);
                if (actual[0] != 0) break;
                android.os.SystemClock.sleep(25);
            } while (android.os.SystemClock.uptimeMillis() < deadline);
            assertEquals("A vertical pull beginning on a video must reach refresh", 1, actual[0]);
            onMain(() -> assertEquals("Vertical pulls must not enqueue a row", 0,
                    fixture[0].context.submitted.size()));
        });
    }

    @Test
    public void disabledSourceSwipesDoNotClickOrLongClickRows() {
        assertDisabledPhysicalSwipes(false);
    }

    @Test
    public void disabledPlaylistSwipesDoNotClickOrFallBackToLegacyDelete() {
        assertDisabledPhysicalSwipes(true);
    }

    private void assertDisabledPhysicalSwipes(final boolean playlist) {
        prefs.edit().putString("video_swipe_left_action", "none")
                .putString("video_swipe_right_action", "none")
                .putString(context.getString(R.string.local_playlist_swipe_action_key), "delete")
                .commit();
        withActivity(activity -> {
            for (final int direction : Arrays.asList(View.LAYOUT_DIRECTION_LTR,
                    View.LAYOUT_DIRECTION_RTL)) {
                final SwipeFixture[] fixture = new SwipeFixture[1];
                final int[] clicks = {0};
                final int[] longClicks = {0};
                onMain(() -> {
                    fixture[0] = new SwipeFixture(activity, playlist);
                    final SourceFragment fragment = new SourceFragment();
                    activity.getSupportFragmentManager().beginTransaction()
                            .add(fragment, null).commitNow();
                    fixture[0].list.setLayoutDirection(direction);
                    activity.setContentView(fixture[0].list);
                    VideoSwipeActions.protectTabGesture(fragment, fixture[0].list,
                            position -> position < 0 || position >= 2 ? null
                                    : new VideoSwipeActions.Row(video(position == 0 ? "A" : "B"),
                                            playlist ? () -> { } : null, null));
                    new ItemTouchHelper(fixture[0].callback).attachToRecyclerView(fixture[0].list);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                onMain(() -> {
                    for (int i = 0; i < 2; i++) {
                        final View row = fixture[0].list.findViewHolderForAdapterPosition(i).itemView;
                        row.setOnClickListener(v -> clicks[0]++);
                        row.setOnLongClickListener(v -> { longClicks[0]++; return true; });
                    }
                    fixture[0].list.findViewHolderForAdapterPosition(0).itemView.performClick();
                    assertEquals(1, clicks[0]);
                    clicks[0] = 0;
                });
                swipeRow(fixture[0].list, 0, false);
                swipeRow(fixture[0].list, 1, true);
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                onMain(() -> {
                    assertEquals(0, clicks[0]);
                    assertEquals(0, longClicks[0]);
                    assertEquals(0, fixture[0].context.submitted.size());
                    assertEquals(2, fixture[0].adapter.getItemCount());
                });
            }
        });
    }

    private void assertPhysicalSwipes(final boolean playlist) {
        withActivity(activity -> {
            for (final int direction : Arrays.asList(View.LAYOUT_DIRECTION_LTR,
                    View.LAYOUT_DIRECTION_RTL)) {
                final SwipeFixture[] fixture = new SwipeFixture[1];
                onMain(() -> {
                    fixture[0] = new SwipeFixture(activity, playlist);
                    fixture[0].list.setLayoutDirection(direction);
                    activity.setContentView(fixture[0].list);
                    new ItemTouchHelper(fixture[0].callback).attachToRecyclerView(fixture[0].list);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                swipeRow(fixture[0].list, 0, true);
                awaitSubmitted(fixture[0], 1);
                swipeRow(fixture[0].list, 1, false);
                awaitSubmitted(fixture[0], 2);
                onMain(() -> {
                    assertEquals(Arrays.asList("https://example.com/A:next",
                            "https://example.com/B:end"), fixture[0].context.submitted);
                    assertEquals(2, fixture[0].adapter.getItemCount());
                });
            }
        });
    }

    private static void awaitSubmitted(final SwipeFixture fixture, final int count) {
        final long deadline = android.os.SystemClock.uptimeMillis() + 5_000;
        final int[] actual = {0};
        do {
            onMain(() -> actual[0] = fixture.context.submitted.size());
            if (actual[0] >= count) return;
            android.os.SystemClock.sleep(25);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        assertEquals("Physical swipe must reach the production service boundary", count, actual[0]);
    }

    private static void swipeRow(final RecyclerView list, final int position, final boolean left) {
        final float[] coordinates = new float[3];
        onMain(() -> {
            final View row = list.findViewHolderForAdapterPosition(position).itemView;
            final int[] location = new int[2];
            row.getLocationOnScreen(location);
            coordinates[0] = location[0] + row.getWidth() * (left ? 0.85f : 0.15f);
            coordinates[1] = location[0] + row.getWidth() * (left ? 0.05f : 0.95f);
            coordinates[2] = location[1] + row.getHeight() / 2f;
        });
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final long down = android.os.SystemClock.uptimeMillis();
        for (int i = 0; i <= 20; i++) {
            final int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN
                    : i == 20 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE;
            final var event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(),
                    action, coordinates[0] + (coordinates[1] - coordinates[0]) * i / 20f,
                    coordinates[2], 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            instrumentation.sendPointerSync(event);
            event.recycle();
            android.os.SystemClock.sleep(16);
        }
    }

    private void assertDeferredDirections(final SwipeFixture fixture) {
        for (final int layoutDirection : Arrays.asList(View.LAYOUT_DIRECTION_LTR,
                View.LAYOUT_DIRECTION_RTL)) {
            fixture.list.setLayoutDirection(layoutDirection);
            fixture.context.submitted.clear();
            fixture.restored.clear();
            fixture.selectBoth();
            // A's recovery finishes after B was selected. Each callback must retain its own row.
            fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
            fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
            assertEquals(Arrays.asList("https://example.com/A:next",
                    "https://example.com/B:end"), fixture.context.submitted);
            assertEquals(Arrays.asList(0, 1), fixture.restored);
            assertEquals(2, fixture.list.getAdapter().getItemCount());
        }
    }

    @Test
    public void sourceCallbacksRetainCapturedRowsAndDiscardCancelledOrRecycledGestures() {
        withActivity(activity -> onMain(() -> assertGestureLifecycle(
                new SwipeFixture(activity, false))));
    }

    @Test
    public void playlistCallbacksRetainCapturedOccurrencesAndDiscardCancelledOrRecycledGestures() {
        withActivity(activity -> onMain(() -> assertGestureLifecycle(
                new SwipeFixture(activity, true))));
    }

    private void assertGestureLifecycle(final SwipeFixture fixture) {
        fixture.selectBoth();
        fixture.replaceFirst(video("C"));
        fixture.adapter.bindViewHolder(fixture.first, 0);
        // Rebinding must not redirect the pending action from A to the newly displayed C.
        fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
        fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
        assertEquals(Arrays.asList("https://example.com/A:next",
                "https://example.com/B:end"), fixture.context.submitted);

        fixture.context.submitted.clear();
        fixture.selectBoth();
        fixture.callback.clearView(fixture.list, fixture.first);
        fixture.replaceFirst(video("D"));
        fixture.adapter.bindViewHolder(fixture.first, 0);
        fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
        fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
        // A stale callback after cancellation/recycle cannot resolve and act on D.
        assertEquals(Arrays.asList("https://example.com/B:end"), fixture.context.submitted);
        fixture.callback.clearView(fixture.list, fixture.second);
        fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
        assertEquals(1, fixture.context.submitted.size());

        fixture.callback.onSelectedChanged(fixture.first, ItemTouchHelper.ACTION_STATE_SWIPE);
        fixture.callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
        fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
        assertEquals(Arrays.asList("https://example.com/B:end",
                "https://example.com/D:next"), fixture.context.submitted);
        if (fixture.playlistFragment != null) {
            fixture.context.submitted.clear();
            fixture.callback.onSelectedChanged(fixture.first, ItemTouchHelper.ACTION_STATE_SWIPE);
            fixture.callback.onSelectedChanged(fixture.first, ItemTouchHelper.ACTION_STATE_DRAG);
            fixture.callback.onSwiped(fixture.first, ItemTouchHelper.RIGHT);
            assertEquals(0, fixture.context.submitted.size());
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    public void playlistConfirmationsKeepDeferredDuplicateOccurrencesAndCancellationRestoresRows() {
        prefs.edit().putString("video_swipe_left_action_playlist", "remove_playlist")
                .putString("video_swipe_right_action_playlist", "remove_playlist").commit();
        withActivity(activity -> {
            final SwipeFixture[] fixture = new SwipeFixture[1];
            final AppDatabase db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
            try {
                onMain(() -> {
                    fixture[0] = new SwipeFixture(activity, true, true);
                    final PlaylistFragment fragment = fixture[0].playlistFragment;
                    // Only the in-memory adapter is edited; no playlist writes are scheduled.
                    setPlaylistManager(fragment, new LocalPlaylistManager(db) {
                        @Override
                        public String getPlaylistThumbnail(final long playlistId) {
                            return "unused-thumbnail";
                        }
                    });
                    fixture[0].selectBoth();
                    fixture[0].callback.onSwiped(fixture[0].first, ItemTouchHelper.LEFT);
                    final View firstDialog = confirmationRoot();
                    fixture[0].callback.onSwiped(fixture[0].second, ItemTouchHelper.RIGHT);
                    final View secondDialog = confirmationRoot();
                    assertEquals(false, firstDialog == secondDialog);
                    assertEquals(2, fragment.itemCount());
                    firstDialog.findViewById(android.R.id.button1).performClick();
                    secondDialog.findViewById(android.R.id.button2).performClick();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                onMain(() -> {
                    final SwipeFixture current = fixture[0];
                    assertEquals(1, current.playlistFragment.itemCount());
                    assertSame(current.secondEntry, current.playlistFragment.firstItem());
                    assertEquals(Arrays.asList(0, 1), current.restored.subList(0, 2));
                    assertEquals(0, current.context.submitted.size());
                });
            } finally {
                db.close();
            }
        });
    }

    private static void onMain(final Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private void withVideoOpen(final Consumer<VideoOpenFixture> test) {
        withActivity(activity -> onMain(() -> {
            final VideoOpenFixture fixture = new VideoOpenFixture(activity);
            try {
                test.accept(fixture);
            } finally {
                fixture.close();
            }
        }));
    }

    private static Field accessibleField(final Object target, final String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (final NoSuchFieldException ignored) {
                // Private production state can belong to a base fragment/player class.
            }
        }
        throw new AssertionError("Missing field " + name);
    }

    private static Object readField(final Object target, final String name) {
        if (target instanceof org.schabi.newpipe.fragments.detail.VideoDetailFragment) {
            switch (name) {
                case "currentInfo": case "playQueue": case "url":
                    return readField(readField(target, "vm"), name);
                case "currentWorker": return readField(readField(target, "vm"), "worker");
                case "binding": return readField(target, "_binding");
                case "bottomSheetState": return readField(target, "sheetStateToSave");
            }
        }
        try {
            return accessibleField(target, name).get(target);
        } catch (final IllegalAccessException error) {
            throw new AssertionError(error);
        }
    }

    private static void writeField(final Object target, final String name, final Object value) {
        if (target instanceof org.schabi.newpipe.fragments.detail.VideoDetailFragment) {
            switch (name) {
                case "currentInfo": case "playQueue": case "url":
                    writeField(readField(target, "vm"), name, value);
                    return;
                case "currentWorker":
                    writeField(readField(target, "vm"), "worker", value);
                    return;
                case "binding": writeField(target, "_binding", value); return;
                case "bottomSheetState": writeField(target, "sheetStateToSave", value); return;
            }
        }
        try {
            accessibleField(target, name).set(target, value);
        } catch (final IllegalAccessException error) {
            throw new AssertionError(error);
        }
    }

    private static void invokeDetail(final VideoOpenFragment fragment, final String name) {
        try {
            final Method method = org.schabi.newpipe.fragments.detail.VideoDetailFragment.class
                    .getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(fragment);
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /**
     * Exercises real selection, metadata callbacks and openMainPlayer's service intent.
     * Only detail rendering and the Android service launch are replaced; no network/player
     * service is started. All callbacks run in one main-thread turn for deterministic ordering.
     */
    private final class VideoOpenFixture {
        private final VideoOpenActivity activity;
        private VideoOpenFragment fragment;
        private final org.schabi.newpipe.player.helper.PlayerHolder holder =
                org.schabi.newpipe.player.helper.PlayerHolder.getInstance();
        private final Object oldListener;
        private final List<String> cachedUrls = new ArrayList<>();
        private final androidx.lifecycle.ViewModelStore viewModelStore =
                new androidx.lifecycle.ViewModelStore();
        private org.schabi.newpipe.player.Player player;
        private com.google.android.exoplayer2.ExoPlayer exo;
        private com.google.android.exoplayer2.Player.Listener exoEventAdapter;

        VideoOpenFixture(final FragmentActivity host) {
            holder.stopService();
            oldListener = readField(holder, "listener");
            activity = new VideoOpenActivity(host);
            fragment = new VideoOpenFragment(host, activity, viewModelStore);
            writeField(fragment, "binding", org.schabi.newpipe.databinding.FragmentVideoDetailBinding
                    .inflate(android.view.LayoutInflater.from(host)));
            writeField(fragment, "bottomSheetState",
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED);
            writeField(holder, "listener", fragment);
            final var service = (org.schabi.newpipe.player.mediasession.PlayerServiceInterface)
                    java.lang.reflect.Proxy.newProxyInstance(
                            org.schabi.newpipe.player.mediasession.PlayerServiceInterface.class.getClassLoader(),
                            new Class<?>[]{org.schabi.newpipe.player.mediasession.PlayerServiceInterface.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("getView")) return null;
                                throw new AssertionError("Unexpected service call: " + method.getName());
                            });
            writeField(fragment, "playerService", service);
            select("A");
        }

        org.schabi.newpipe.extractor.stream.StreamInfo info(final String name) {
            final String url = "https://example.com/deferred-open-" + name;
            final var info = new org.schabi.newpipe.extractor.stream.StreamInfo(
                    0, url, url, StreamType.VIDEO_STREAM, name, name, 0);
            info.setUploaderName("test uploader");
            info.setThumbnailUrl("");
            return info;
        }

        void select(final String name) {
            final var info = info(name);
            org.schabi.newpipe.util.InfoCache.getInstance().putInfo(0, info.getUrl(), info,
                    org.schabi.newpipe.extractor.InfoItem.InfoType.STREAM);
            cachedUrls.add(info.getUrl());
            fragment.selectAndLoadVideo(0, info.getUrl(), name,
                    new org.schabi.newpipe.player.playqueue.SinglePlayQueue(info));
            // Extraction uses only the above cached metadata. Deliver its production callback
            // explicitly below, including callbacks already queued before disposal/navigation.
            ((io.reactivex.rxjava3.disposables.Disposable)
                    readField(fragment, "currentWorker")).dispose();
        }

        long generation() {
            return (Long) readField(fragment, "videoSelectionGeneration");
        }

        PlayQueue queue() {
            return (PlayQueue) readField(fragment, "playQueue");
        }

        void requestExplicitPlayback() {
            final String key = context.getString(R.string.start_main_player_fullscreen_key);
            final boolean had = prefs.contains(key);
            final boolean old = prefs.getBoolean(key, false);
            try {
                prefs.edit().putBoolean(key, false).commit();
                ((org.schabi.newpipe.fragments.detail.DetailActions.Host)
                        readField(fragment, "hosts")).playInMainPlayer();
            } finally {
                final var editor = prefs.edit();
                if (had) editor.putBoolean(key, old); else editor.remove(key);
                editor.commit();
            }
        }

        void prepareColdQueue(final String... names) {
            final Object worker = readField(fragment, "currentWorker");
            if (worker != null) ((io.reactivex.rxjava3.disposables.Disposable) worker).dispose();
            writeField(fragment, "currentWorker", null);
            writeField(fragment, "url", null);
            writeField(fragment, "currentInfo", null);
            final PlayQueue queue = new org.schabi.newpipe.player.playqueue.SinglePlayQueue(info(names[0]));
            for (int i = 1; i < names.length; i++) {
                queue.append(org.schabi.newpipe.player.mediaitem.PlayerMediaItem.forQueueItem(info(names[i])));
            }
            writeField(fragment, "playQueue", queue);
        }

        android.os.Bundle saveState() {
            final android.os.Bundle state = new android.os.Bundle();
            fragment.onSaveInstanceState(state);
            // Exercise Bundle's actual Binder parceling rather than copying test-only fields.
            final android.os.Parcel parcel = android.os.Parcel.obtain();
            try {
                parcel.writeBundle(state);
                parcel.setDataPosition(0);
                return parcel.readBundle(getClass().getClassLoader());
            } finally {
                parcel.recycle();
            }
        }

        void recreate(final android.os.Bundle state) {
            final VideoOpenFragment old = fragment;
            final Object worker = readField(old, "currentWorker");
            if (worker != null) ((io.reactivex.rxjava3.disposables.Disposable) worker).dispose();
            fragment = new VideoOpenFragment(old.getContext(), activity);
            writeField(fragment, "binding", readField(old, "binding"));
            writeField(old, "binding", null);
            writeField(fragment, "playerService", readField(old, "playerService"));
            if (player != null) player.removeFragmentListener(old);
            writeField(holder, "listener", fragment);
            fragment.onRestoreInstanceState(state);
        }

        /** Retains the actual ViewModelStore; rendering/service launch remain fixture boundaries. */
        void recreateRetainingViewModel() {
            final android.os.Bundle state = saveState();
            final VideoOpenFragment old = fragment;
            fragment = new VideoOpenFragment(old.getContext(), activity, viewModelStore);
            writeField(old, "binding", null);
            writeField(fragment, "binding", org.schabi.newpipe.databinding.FragmentVideoDetailBinding
                    .inflate(android.view.LayoutInflater.from(old.getContext())));
            writeField(fragment, "playerService", readField(old, "playerService"));
            writeField(fragment, "player", player);
            if (player != null) player.removeFragmentListener(old);
            writeField(holder, "listener", fragment);
            fragment.onRestoreInstanceState(state);
        }

        void reconnect() {
            fragment.onServiceConnected(player,
                    (org.schabi.newpipe.player.mediasession.PlayerServiceInterface)
                            readField(fragment, "playerService"), false);
        }

        void registerExoPlayerEventAdapter() {
            try {
                final var constructor = Class.forName("org.schabi.newpipe.player.ExoPlayerEventAdapter")
                        .getDeclaredConstructor(org.schabi.newpipe.player.Player.class);
                constructor.setAccessible(true);
                exoEventAdapter = (com.google.android.exoplayer2.Player.Listener) constructor.newInstance(player);
                exo.addListener(exoEventAdapter);
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        void setPlayerMetadata(final org.schabi.newpipe.player.mediaitem.PlayerMediaItem item) {
            writeField(readField(player, "metadataController"), "currentMetadata", item);
        }

        void deliverPlayerMetadata(final org.schabi.newpipe.player.mediaitem.PlayerMediaItem item) {
            writeField(readField(player, "listeners"), "fragmentListener", fragment);
            final var eventPlayer = (com.google.android.exoplayer2.Player)
                    java.lang.reflect.Proxy.newProxyInstance(
                            com.google.android.exoplayer2.Player.class.getClassLoader(),
                            new Class<?>[]{com.google.android.exoplayer2.Player.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("getCurrentMediaItem")) {
                                    return org.schabi.newpipe.player.mediaitem.ExoMediaItems.asExoMediaItem(item);
                                }
                                throw new AssertionError("Unexpected Exo call: " + method.getName());
                            });
            ((org.schabi.newpipe.player.PlayerMetadataController) readField(player, "metadataController"))
                    .onEvents(eventPlayer, new com.google.android.exoplayer2.Player.Events(
                            new com.google.android.exoplayer2.util.FlagSet.Builder().build()));
        }

        void complete(final org.schabi.newpipe.extractor.stream.StreamInfo info,
                      final long generation) {
            try {
                final Method callback = org.schabi.newpipe.fragments.detail.VideoDetailFragment.class
                        .getDeclaredMethod("onStreamInfoLoaded",
                                org.schabi.newpipe.extractor.stream.StreamInfo.class,
                                long.class, boolean.class);
                callback.setAccessible(true);
                callback.invoke(fragment, info, generation, false);
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        void connectBufferingPlayer() {
            final var serviceContext = new VideoOpenService(context);
            final var service = (org.schabi.newpipe.player.mediasession.PlayerServiceInterface)
                    java.lang.reflect.Proxy.newProxyInstance(
                            org.schabi.newpipe.player.mediasession.PlayerServiceInterface.class.getClassLoader(),
                            new Class<?>[]{org.schabi.newpipe.player.mediasession.PlayerServiceInterface.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("getInstance")) return serviceContext;
                                if (method.getName().equals("isLandscape")) return false;
                                throw new AssertionError("Unexpected service call: " + method.getName());
                            });
            player = new org.schabi.newpipe.player.Player(service);
            exo = new com.google.android.exoplayer2.ExoPlayer.Builder(context).build();
            writeField(player, "simpleExoPlayer", exo);
            writeField(player, "playQueue", queue());
            writeField(player, "audioReactor",
                    new org.schabi.newpipe.player.helper.AudioReactor(context, exo));
            writeField(fragment, "player", player);
            exo.setPlayWhenReady(true);
            exo.setMediaSource(new com.google.android.exoplayer2.source.SilenceMediaSource.Factory()
                    .setDurationUs(600_000_000L).createMediaSource());
            exo.prepare();
            assertEquals(com.google.android.exoplayer2.Player.STATE_BUFFERING, exo.getPlaybackState());
            assertEquals(false, player.isPlaying());
        }

        void assertOpened(final String name, final boolean playWhenReady) {
            assertEquals(1, activity.intents.size());
            assertEquals(info(name).getUrl(), activity.openedUrls.get(0));
            assertEquals(playWhenReady, activity.intents.get(0)
                    .getBooleanExtra(PlayerIntentConstants.PLAY_WHEN_READY, !playWhenReady));
        }

        void close() {
            if (exoEventAdapter != null) exo.removeListener(exoEventAdapter);
            final Object worker = readField(fragment, "currentWorker");
            if (worker != null) ((io.reactivex.rxjava3.disposables.Disposable) worker).dispose();
            writeField(fragment, "binding", null); // Reject delayed rendering after this test turn.
            writeField(holder, "listener", oldListener);
            for (final String url : cachedUrls) {
                org.schabi.newpipe.util.InfoCache.getInstance().removeInfo(0, url,
                        org.schabi.newpipe.extractor.InfoItem.InfoType.STREAM);
            }
            if (player != null) {
                player.removeFragmentListener(fragment);
                player.getAudioReactor().dispose();
                player.getPersistentQueueController().close();
                ((org.schabi.newpipe.player.SponsorBlockController)
                        readField(player, "sponsorBlockController")).destroy();
                ((com.google.android.exoplayer2.trackselection.DefaultTrackSelector)
                        readField(player, "trackSelector")).release();
                writeField(player, "simpleExoPlayer", null);
            }
            if (exo != null) exo.release();
            viewModelStore.clear();
        }
    }

    private static final class VideoOpenFragment
            extends org.schabi.newpipe.fragments.detail.VideoDetailFragment {
        private final Context testContext;

        VideoOpenFragment(final Context context, final VideoOpenActivity activity) {
            this(context, activity, new androidx.lifecycle.ViewModelStore());
        }

        VideoOpenFragment(final Context context, final VideoOpenActivity activity,
                          final androidx.lifecycle.ViewModelStore viewModelStore) {
            testContext = context;
            this.activity = activity;
            writeField(this, "vm", new androidx.lifecycle.ViewModelProvider(viewModelStore,
                    new androidx.lifecycle.ViewModelProvider.NewInstanceFactory())
                    .get(org.schabi.newpipe.fragments.detail.VideoDetailViewModel.class));
        }

        @Override public Context getContext() { return testContext; }
        @Override public void showLoading() { }
        @Override public void hideLoading() { }

        void restoreRetainedView() { super.doInitialLoadLogic(); }

        @Override
        public void onRestoreInstanceState(final android.os.Bundle state) {
            super.onRestoreInstanceState(state);
        }

        @Override
        public void handleResult(final org.schabi.newpipe.extractor.stream.StreamInfo info) {
            // Replace tab/thumbnail rendering, retaining the production metadata assignment.
            writeField(this, "currentInfo", info);
            ((org.schabi.newpipe.fragments.detail.VideoDetailViewModel) readField(this, "vm"))
                    .setInitialData(info.getServiceId(), info.getOriginalUrl(), info.getName(),
                            (PlayQueue) readField(this, "playQueue"));
        }
    }

    /** Captures the actual openMainPlayer intent without launching an Android service. */
    private static final class VideoOpenActivity extends androidx.appcompat.app.AppCompatActivity {
        private final List<Intent> intents = new ArrayList<>();
        private final List<String> openedUrls = new ArrayList<>();

        VideoOpenActivity(final Context context) { attachBaseContext(context); }

        @Override
        public ComponentName startForegroundService(final Intent intent) {
            return startService(intent);
        }

        @Override
        public ComponentName startService(final Intent intent) {
            final PlayQueue queue = SerializedCache.getInstance().take(
                    intent.getStringExtra(PlayerIntentConstants.PLAY_QUEUE_KEY), PlayQueue.class);
            assertNotNull(queue);
            openedUrls.add(queue.getItem().getUrl());
            intents.add(intent);
            return intent.getComponent();
        }
    }

    private static final class VideoOpenService extends android.app.Service {
        VideoOpenService(final Context context) {
            attachBaseContext(context);
            setTheme(R.style.DarkTheme);
        }
        @Override public android.os.IBinder onBind(final Intent intent) { return null; }
    }

    private void withActivity(final Consumer<FragmentActivity> test) {
        final Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, AboutActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.accept((FragmentActivity) activity);
        } finally {
            onMain(activity::finish);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }

    @androidx.annotation.RequiresApi(29)
    private View confirmationRoot() {
        final List<View> roots = WindowInspector.getGlobalWindowViews();
        for (int i = roots.size() - 1; i >= 0; i--) {
            final View root = roots.get(i);
            final TextView message = root.findViewById(android.R.id.message);
            if (message != null && message.getContext()
                    .getString(R.string.remove_from_playlist_confirmation)
                    .contentEquals(message.getText())) {
                assertNotNull(root.findViewById(android.R.id.button1));
                assertNotNull(root.findViewById(android.R.id.button2));
                return root;
            }
        }
        throw new AssertionError("Missing playlist removal confirmation");
    }

    private static void setPlaylistManager(final PlaylistFragment fragment,
                                           final LocalPlaylistManager manager) {
        try {
            final Field field = LocalPlaylistFragment.class.getDeclaredField("playlistManager");
            field.setAccessible(true);
            field.set(fragment, manager);
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /** Captures production queue intents at the service boundary without starting a player. */
    private static final class RecordingContext extends ContextWrapper {
        private final List<String> submitted = new ArrayList<>();

        RecordingContext(final Context base) {
            super(base);
        }

        @Override
        public ComponentName startService(final Intent intent) {
            final PlayQueue queue = SerializedCache.getInstance().take(
                    intent.getStringExtra(PlayerIntentConstants.PLAY_QUEUE_KEY), PlayQueue.class);
            assertNotNull(queue);
            submitted.add(queue.getItem().getUrl() + ":"
                    + (intent.getBooleanExtra(PlayerIntentConstants.ENQUEUE_NEXT, false)
                    ? "next" : "end"));
            return intent.getComponent();
        }

        @Override
        public ComponentName startForegroundService(final Intent intent) {
            return startService(intent);
        }
    }

    private static RecyclerView.ViewHolder plainHolder(final ViewGroup parent) {
        final View view = new View(parent.getContext());
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80));
        return new RecyclerView.ViewHolder(view) { };
    }

    /** Uses the real local adapter's filtering/position logic with inexpensive row views. */
    private static final class PlainLocalAdapter extends LocalItemListAdapter {
        PlainLocalAdapter(final Context context) {
            super(context);
            setItemViewMode(ItemViewMode.LIST);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent, final int type) {
            return plainHolder(parent);
        }

        @Override
        public void onBindViewHolder(final RecyclerView.ViewHolder holder, final int position) { }
    }

    private static final class PlaylistFragment extends LocalPlaylistFragment {
        private final Context context;

        PlaylistFragment(final Context context, final LocalItemListAdapter adapter) {
            this.context = context;
            itemListAdapter = adapter;
            playlistId = 1L;
        }

        @Override
        public Context getContext() {
            return context;
        }

        int itemCount() {
            return itemListAdapter.getItemsList().size();
        }

        LocalItem firstItem() {
            return itemListAdapter.getItemsList().get(0);
        }

        ItemTouchHelper.SimpleCallback callback() {
            try {
                final Method method = LocalPlaylistFragment.class
                        .getDeclaredMethod("getItemTouchCallback");
                method.setAccessible(true);
                return (ItemTouchHelper.SimpleCallback) method.invoke(this);
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }
    }

    public static class SourceFragment extends Fragment {
        private Context swipeContext;

        @Override
        public Context getContext() {
            return swipeContext == null ? super.getContext() : swipeContext;
        }
    }

    private final class SwipeFixture {
        private final RecordingContext context;
        private final RecyclerView list;
        private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
        private final ItemTouchHelper.SimpleCallback callback;
        private final RecyclerView.ViewHolder first;
        private final RecyclerView.ViewHolder second;
        private final List<Integer> restored = new ArrayList<>();
        private final List<VideoSwipeActions.Row> rows = new ArrayList<>();
        private final PlaylistFragment playlistFragment;
        private final PlaylistStreamEntry firstEntry;
        private final PlaylistStreamEntry secondEntry;

        SwipeFixture(final FragmentActivity activity, final boolean playlist) {
            this(activity, playlist, false);
        }

        SwipeFixture(final FragmentActivity activity, final boolean playlist,
                     final boolean duplicateVideo) {
            context = new RecordingContext(activity);
            list = new RecyclerView(context);
            list.setLayoutManager(new LinearLayoutManager(context));
            firstEntry = new PlaylistStreamEntry(new StreamEntity(video("A")), 0, 1, 0);
            secondEntry = new PlaylistStreamEntry(
                    new StreamEntity(video(duplicateVideo ? "A" : "B")), 0, 2, 1);
            if (playlist) {
                final PlainLocalAdapter localAdapter = new PlainLocalAdapter(context);
                localAdapter.addItems(Arrays.asList(firstEntry, secondEntry));
                adapter = localAdapter;
                playlistFragment = new PlaylistFragment(context, localAdapter);
                callback = playlistFragment.callback();
            } else {
                playlistFragment = null;
                rows.add(new VideoSwipeActions.Row(video("A")));
                rows.add(new VideoSwipeActions.Row(video("B")));
                final SourceFragment fragment = new SourceFragment();
                fragment.swipeContext = context;
                activity.getSupportFragmentManager().beginTransaction().add(fragment, null).commitNow();
                adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent,
                                                                     final int type) {
                        return plainHolder(parent);
                    }

                    @Override
                    public void onBindViewHolder(final RecyclerView.ViewHolder holder,
                                                 final int position) { }

                    @Override
                    public int getItemCount() {
                        return rows.size();
                    }
                };
                callback = VideoSwipeActions.createCallback(fragment, list,
                        position -> position >= 0 && position < rows.size() ? rows.get(position) : null);
            }
            list.setAdapter(adapter);
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onItemRangeChanged(final int position, final int count,
                                               final Object payload) {
                    restored.add(position);
                }
            });
            list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 800, 1200);
            first = list.findViewHolderForAdapterPosition(0);
            second = list.findViewHolderForAdapterPosition(1);
            assertNotNull(first);
            assertNotNull(second);
        }

        void selectBoth() {
            callback.onSelectedChanged(first, ItemTouchHelper.ACTION_STATE_SWIPE);
            callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
            callback.onSelectedChanged(second, ItemTouchHelper.ACTION_STATE_SWIPE);
            callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
        }

        void replaceFirst(final StreamInfoItem video) {
            if (playlistFragment == null) {
                rows.set(0, new VideoSwipeActions.Row(video));
            } else {
                final LocalItemListAdapter adapter = (LocalItemListAdapter) list.getAdapter();
                adapter.getItemsList().set(0,
                        new PlaylistStreamEntry(new StreamEntity(video), 0, 3, 0));
            }
        }
    }
}
