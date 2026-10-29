package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerPlaybackState;
import org.schabi.newpipe.player.mediasession.PlayerServiceInterface;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real SQLite reopen/rollback and player checkpoint tests with controlled media; no network. */
@RunWith(AndroidJUnit4.class)
public class PersistentPlayQueueTest {
    private static final String NAME = "persistent-queue-test.db";
    private Context context;
    private SupportSQLiteOpenHelper helper;
    private PersistentPlayQueue queue;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(NAME);
        open();
    }

    private void open() {
        helper = new FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(NAME)
                        .callback(new SupportSQLiteOpenHelper.Callback(1) {
                            @Override
                            public void onCreate(@NonNull final SupportSQLiteDatabase db) {
                                db.execSQL("CREATE TABLE existing_user_data(value TEXT)");
                                db.execSQL("INSERT INTO existing_user_data VALUES('kept')");
                            }

                            @Override
                            public void onUpgrade(@NonNull final SupportSQLiteDatabase db,
                                                  final int oldVersion, final int newVersion) {
                            }

                            @Override
                            public void onOpen(@NonNull final SupportSQLiteDatabase db) {
                                db.execSQL("PRAGMA synchronous=FULL");
                                PersistentQueueStore.createTable(db);
                            }
                        }).build());
        queue = new PersistentPlayQueue(new PersistentQueueStore(helper.getWritableDatabase()));
    }

    @After
    public void tearDown() {
        helper.close();
        context.deleteDatabase(NAME);
    }

    private static PlayerMediaItem item(final String url) {
        return new PlayerMediaItem.Builder().url(url).title(url).build();
    }

    @Test
    public void acknowledgedDuplicateProgressAndSelectionSurviveFileReopen() {
        queue.append(Arrays.asList(item("A"), item("A"), item("C")));
        final String first = queue.getItem(0).getUuid();
        final String duplicate = queue.getItem(1).getUuid();
        assertNotEquals(first, duplicate);
        queue.setRecovery(0, 1234);
        queue.setRecovery(1, 9876);
        queue.setIndex(1);
        helper.close();
        open();
        assertEquals(0, queue.getIndex());
        assertEquals(duplicate, queue.getItem(0).getUuid());
        assertEquals(first, queue.getItem(1).getUuid());
        assertEquals(9876, queue.getRecoveryPosition(0));
        assertEquals(1234, queue.getRecoveryPosition(1));
        queue.unsetRecovery(0);
        assertEquals(PlayQueue.RECOVERY_UNSET, queue.getRecoveryPosition(0));
    }

    @Test
    public void failedTransactionPublishesNeitherMutationNorSuccessEvent() {
        queue.append(item("A"));
        final io.reactivex.rxjava3.subscribers.TestSubscriber<?> events =
                queue.getBroadcastReceiver().test();
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        final int initialEvents = events.values().size();
        helper.getWritableDatabase().execSQL("CREATE TRIGGER reject_queue BEFORE INSERT ON "
                + "persistent_queue_state BEGIN SELECT RAISE(ABORT,'disk failure'); END");
        try {
            queue.append(item("B"));
            fail("Save must report failure");
        } catch (final RuntimeException expected) {
            assertEquals(1, queue.size());
        }
        // Delivery is scheduled on main; a failed save must add no event.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        events.assertValueCount(initialEvents);
        helper.close();
        open();
        assertEquals(1, queue.size());
        assertEquals("A", queue.getItem().getUrl());
    }

    @Test
    public void failedPlayerSelectionDoesNotReachSuccessFeedbackOrCommit() {
        queue.append(Arrays.asList(item("A"), item("B"), item("B")));
        final PlayerMediaItem selected = queue.getItem(2);
        final String original = queue.getItem().getUuid();
        queue.setRecovery(0, 12_000);
        queue.setRecovery(2, 34_000);
        helper.getWritableDatabase().execSQL("CREATE TRIGGER reject_queue BEFORE INSERT ON "
                + "persistent_queue_state BEGIN SELECT RAISE(ABORT,'selection disk failure'); END");
        withPlayer(true, player -> {
            // The controller reports its error using the player's real context. The view wrapper
            // must emit neither a success toast nor a second error toast for that false result.
            final Context noFurtherFeedback = new ContextWrapper(context) {
                @Override public Resources getResources() {
                    throw new AssertionError("An already reported failure must not show feedback");
                }
            };
            assertFalse(QueueViewActions.editIfSuccessful(noFurtherFeedback,
                    () -> player.selectQueueItem(selected)));
            assertEquals(PlayerPlaybackState.PAUSED, player.getCurrentState());
            assertEquals(original, queue.getItem().getUuid());
            assertEquals(12_000, queue.getRecoveryPosition(0));
            assertEquals(34_000, queue.getRecoveryPosition(2));
        });
        helper.close();
        open();
        assertEquals(original, queue.getItem().getUuid());
        assertEquals(selected.getUuid(), queue.getItem(2).getUuid());
    }

    @Test
    public void successfulPlayerSelectionReportsExactCommittedDuplicate() {
        queue.append(Arrays.asList(item("A"), item("B"), item("B")));
        final PlayerMediaItem selected = queue.getItem(2);
        final String original = queue.getItem().getUuid();
        queue.setRecovery(0, 12_000);
        queue.setRecovery(2, 34_000);
        withPlayer(true, player -> {
            assertTrue(player.getPersistentQueueController().checkpoint()); // Safe unprepared no-op.
            assertTrue(QueueViewActions.editIfSuccessful(context,
                    () -> player.selectQueueItem(selected)));
            assertEquals(selected.getUuid(), queue.getItem().getUuid());
            assertEquals(original, queue.getItem(1).getUuid());
            assertEquals(34_000, queue.getRecoveryPosition(0));
            assertEquals(12_000, queue.getRecoveryPosition(1));
        });
        helper.close();
        open();
        assertEquals(selected.getUuid(), queue.getItem().getUuid());
    }

    @Test
    public void ordinaryPlayerSelectionKeepsIndexBasedQueueAndRecovery() {
        withPlayer(false, player -> {
            final PlayQueue ordinary = new SinglePlayQueue(Collections.emptyList(), 0);
            ordinary.append(Arrays.asList(item("A"), item("B")));
            ordinary.setRecovery(0, 12_000);
            setPlayerQueue(player, ordinary);
            // No media is prepared: only exercise the ordinary selection/index boundary.
            final var exo = new com.google.android.exoplayer2.ExoPlayer.Builder(context).build();
            try {
                final var field = Player.class.getDeclaredField("simpleExoPlayer");
                field.setAccessible(true);
                field.set(player, exo);
                try {
                    assertTrue(player.selectQueueItem(ordinary.getItem(1)));
                    assertEquals(1, ordinary.getIndex());
                    assertEquals("A", ordinary.getItem(0).getUrl());
                    assertEquals(12_000, ordinary.getRecoveryPosition(0));
                    assertFalse(player.selectQueueItem(item("missing")));
                } finally {
                    field.set(player, null);
                }
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            } finally {
                exo.release();
            }
        });
    }

    @Test
    public void preparedSelectionAbortsWhenOnlyCheckpointWritesFail() {
        rejectCheckpointWritesOnly();
        final String head = queue.getItem().getUuid();
        withPreparedPlayer(player -> {
            assertFalse(QueueViewActions.editIfSuccessful(noFurtherFeedback(),
                    () -> player.selectQueueItem(queue.getItem(1))));
            assertFailedCheckpointPreservedQueue(player, head);
        });
    }

    @Test
    public void failedLiveCheckpointPausesThroughProductionListenerWithoutRecoveryWriteCrash() {
        rejectCheckpointWritesOnly(true);
        final String head = queue.getItem().getUuid();
        withPreparedPlayer(player -> {
            installProductionListener(player);
            assertEquals(PlayerPlaybackState.PLAYING, player.getCurrentState());
            assertFalse(QueueViewActions.editIfSuccessful(noFurtherFeedback(),
                    () -> player.selectQueueItem(queue.getItem(1))));
        }, player -> {
            assertEquals(PlayerPlaybackState.PAUSED, player.getCurrentState());
            assertLiveSourceReleased(player);
            assertFailedCheckpointPreservedQueue(player, head);
        });
        helper.close();
        open();
        assertEquals(head, queue.getItem().getUuid());
        assertEquals(2, queue.size());
        assertEquals("B", queue.getItem(1).getUrl());
        assertEquals(12_000, queue.getRecoveryPosition(0));
        assertEquals(34_000, queue.getRecoveryPosition(1));
    }

    @Test
    public void livePauseKeepsDurableProgressAndSynchronizationUsesLiveEdge() {
        queue.append(Arrays.asList(liveItem("A"), item("B")));
        queue.setRecovery(0, 12_000);
        queue.setRecovery(1, 34_000);
        final String head = queue.getItem().getUuid();
        withPreparedPlayer(player -> installProductionListener(player).pause(), player -> {
            assertEquals(PlayerPlaybackState.PAUSED, player.getCurrentState());
            assertLiveSourceReleased(player);
            final long checkpoint = queue.getRecoveryPosition(0);
            assertTrue(checkpoint >= 60_000);
            try {
                // Re-synchronizing a restored live occurrence must ignore its retained offset.
                final var current = Player.class.getDeclaredMethod("setCurrentItem", PlayerMediaItem.class);
                current.setAccessible(true);
                current.invoke(player, new Object[]{null});
                final var synchronize = Player.class.getDeclaredMethod("onPlaybackSynchronize",
                        PlayerMediaItem.class, boolean.class);
                synchronize.setAccessible(true);
                synchronize.invoke(player, queue.getItem(), true);
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            assertEquals(0, player.getCurrentPosition());
            final var restored = new PersistentPlayQueue(new PersistentQueueStore(helper.getWritableDatabase()));
            assertEquals(head, restored.getItem().getUuid());
            assertEquals(checkpoint, restored.getRecoveryPosition(0));
            assertEquals(34_000, restored.getRecoveryPosition(1));
        });
    }

    @Test
    public void ordinaryLivePauseStillClearsTransientRecoveryThroughProductionListener() {
        queue.append(liveItem("fixture"));
        queue.setRecovery(0, 12_000);
        final PlayQueue ordinary = new SinglePlayQueue(Collections.emptyList(), 0);
        ordinary.append(liveItem("ordinary"));
        ordinary.setRecovery(0, 12_000);
        withPreparedPlayer(player -> {
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putBoolean("persistent_queue_enabled", false).commit();
            setPlayerQueue(player, ordinary);
            try {
                final var current = Player.class.getDeclaredMethod("setCurrentItem", PlayerMediaItem.class);
                current.setAccessible(true);
                current.invoke(player, ordinary.getItem());
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            installProductionListener(player).pause();
        }, player -> {
            assertEquals(PlayerPlaybackState.PAUSED, player.getCurrentState());
            assertLiveSourceReleased(player);
            assertEquals(PlayQueue.RECOVERY_UNSET, ordinary.getRecoveryPosition(0));
            assertEquals(12_000, queue.getRecoveryPosition(0));
        });
    }

    @Test
    public void attachedOwnerSelectionAbortsWithoutDuplicateCheckpointFeedback() {
        rejectCheckpointWritesOnly();
        final String head = queue.getItem().getUuid();
        withPreparedPlayer(player -> {
            assertFalse(QueueViewActions.editIfSuccessful(noFurtherFeedback(), () -> {
                queue.setIndex(1); // Direct owner path exercises the before-head-change callback.
                return true;
            }));
            assertFailedCheckpointPreservedQueue(player, head);
        });
    }

    @Test
    public void attachedOwnerPlayNowAbortsWhenCheckpointFails() {
        rejectCheckpointWritesOnly();
        final String head = queue.getItem().getUuid();
        final PlayQueue incoming = new SinglePlayQueue(Collections.emptyList(), 0);
        incoming.append(item("incoming"));
        withPreparedPlayer(player -> {
            assertFalse(QueueViewActions.editIfSuccessful(noFurtherFeedback(), () -> {
                queue.insert(incoming, true, false);
                return true;
            }));
            assertFailedCheckpointPreservedQueue(player, head);
        });
    }

    @Test
    public void preparedPlaybackIntentAbortsBeforeChangingQueueOrPlayerMode() {
        rejectCheckpointWritesOnly();
        final String head = queue.getItem().getUuid();
        final PlayQueue incoming = new SinglePlayQueue(Collections.emptyList(), 0);
        incoming.append(item("incoming"));
        withPreparedPlayer(player -> {
            player.handleIntent(org.schabi.newpipe.util.NavigationHelper.getPlayerIntent(context,
                    org.schabi.newpipe.player.PlayerService.class, incoming, true));
            assertSame(queue, player.getPlayQueue());
            assertFailedCheckpointPreservedQueue(player, head);
        });
    }

    @Test
    public void preparedSourceRebuildAbortsBeforeReplacingManagerOnCheckpointFailure() {
        rejectCheckpointWritesOnly();
        final String head = queue.getItem().getUuid();
        withPreparedPlayer(player -> {
            try {
                final var reload = Player.class.getDeclaredMethod("reloadPlayQueueManager");
                reload.setAccessible(true);
                final var manager = Player.class.getDeclaredField("playQueueManager");
                manager.setAccessible(true);
                assertNull(manager.get(player));
                reload.invoke(player);
                assertNull(manager.get(player));
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            assertFailedCheckpointPreservedQueue(player, head);
        });
    }

    @Test
    public void preparedHistoryAndNextKeepCurrentOccurrenceAfterCheckpointFailure() {
        rejectCheckpointWritesOnly();
        final String head = queue.getItem().getUuid();
        withPreparedPlayer(player -> {
            final var controller = player.getPersistentQueueController();
            controller.replayHistory(item("history"));
            controller.previous();
            controller.consume(false);
            controller.consume(true);
            assertFailedCheckpointPreservedQueue(player, head);
        });
    }

    @Test
    public void failedModeSwitchRetainsLivePolicyAndPausedQueue() {
        rejectCheckpointWritesOnly();
        final String head = queue.getItem().getUuid();
        withPreparedPlayer(player -> {
            final var prefs = PreferenceManager.getDefaultSharedPreferences(context);
            try {
                // Register the actual listener without starting history/paused-restore services.
                final var listener = player.getPersistentQueueController().getClass()
                        .getDeclaredField("modeListener");
                listener.setAccessible(true);
                prefs.registerOnSharedPreferenceChangeListener(
                        (SharedPreferences.OnSharedPreferenceChangeListener)
                                listener.get(player.getPersistentQueueController()));
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
            prefs.edit().putBoolean("persistent_queue_enabled", false).commit();
        }, player -> {
            assertTrue(player.isPersistentQueueMode());
            assertSame(queue, player.getPlayQueue());
            assertFailedCheckpointPreservedQueue(player, head);
        });
    }

    @Test
    public void preparedSelectionCheckpointsInterruptedOccurrenceBeforeReordering() {
        queue.append(Arrays.asList(item("A"), item("B")));
        queue.setRecovery(0, 12_000);
        queue.setRecovery(1, 34_000);
        final String head = queue.getItem().getUuid();
        final PlayerMediaItem selected = queue.getItem(1);
        withPreparedPlayer(player -> {
            assertTrue(player.selectQueueItem(selected));
            assertEquals(selected.getUuid(), queue.getItem().getUuid());
            assertEquals(head, queue.getItem(1).getUuid());
            assertEquals(34_000, queue.getRecoveryPosition(0));
            assertTrue(queue.getRecoveryPosition(1) >= 60_000);
            final var restored = new PersistentPlayQueue(new PersistentQueueStore(helper.getWritableDatabase()));
            assertEquals(selected.getUuid(), restored.getItem().getUuid());
            assertEquals(queue.getRecoveryPosition(1), restored.getRecoveryPosition(1));
        });
    }

    @Test
    public void idleReplacementPlayerDoesNotCheckpointZeroOverSavedProgress() {
        queue.append(item("A"));
        queue.setRecovery(0, 12_000);
        withPlayer(true, player -> {
            final var exo = new com.google.android.exoplayer2.ExoPlayer.Builder(context).build();
            setPreparedPlayer(player, exo); // App's prepared flag may lag a replaced ExoPlayer.
            try {
                assertTrue(player.getPersistentQueueController().checkpoint());
                assertEquals(12_000, queue.getRecoveryPosition(0));
            } finally {
                setPreparedPlayer(player, null);
                exo.release();
            }
        });
    }

    private Context noFurtherFeedback() {
        return new ContextWrapper(context) {
            @Override public Resources getResources() {
                throw new AssertionError("Checkpoint failure was already reported by the player");
            }
        };
    }

    private static PlayerMediaItem liveItem(final String url) {
        return new PlayerMediaItem.Builder().url(url).title(url)
                .streamType(org.schabi.newpipe.extractor.stream.StreamType.LIVE_STREAM).build();
    }

    private com.google.android.exoplayer2.ExoPlayer installProductionListener(final Player player) {
        try {
            final var exoField = Player.class.getDeclaredField("simpleExoPlayer");
            exoField.setAccessible(true);
            final var exo = (com.google.android.exoplayer2.ExoPlayer) exoField.get(player);
            exo.pause(); // Install after controlled extraction/READY, then deliver real Play/Pause.
            final var binding = Player.class.getDeclaredField("binding");
            binding.setAccessible(true);
            binding.set(player, org.schabi.newpipe.databinding.PlayerBinding.inflate(
                    android.view.LayoutInflater.from(new androidx.appcompat.view.ContextThemeWrapper(
                            context, org.schabi.newpipe.R.style.DarkTheme))));
            final var type = Player.class.getDeclaredMethod("setPlayerType",
                    org.schabi.newpipe.player.PlayerService.PlayerType.class);
            type.setAccessible(true);
            type.invoke(player, org.schabi.newpipe.player.PlayerService.PlayerType.AUDIO);
            final var constructor = Class.forName("org.schabi.newpipe.player.ExoPlayerEventAdapter")
                    .getDeclaredConstructor(Player.class);
            constructor.setAccessible(true);
            final var adapter = (com.google.android.exoplayer2.Player.Listener) constructor.newInstance(player);
            final var field = Player.class.getDeclaredField("exoPlayerEventAdapter");
            field.setAccessible(true);
            field.set(player, adapter);
            exo.addListener(adapter);
            exo.play();
            return exo;
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void assertLiveSourceReleased(final Player player) {
        try {
            final var liveIdle = Player.class.getDeclaredMethod("isLiveIdle");
            liveIdle.setAccessible(true);
            assertEquals(Boolean.TRUE, liveIdle.invoke(player));
            final var exoField = Player.class.getDeclaredField("simpleExoPlayer");
            exoField.setAccessible(true);
            assertEquals(com.google.android.exoplayer2.Player.STATE_IDLE,
                    ((com.google.android.exoplayer2.ExoPlayer) exoField.get(player)).getPlaybackState());
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
        assertFalse(player.getPlayWhenReady());
    }

    private void rejectCheckpointWritesOnly() {
        rejectCheckpointWritesOnly(false);
    }

    private void rejectCheckpointWritesOnly(final boolean live) {
        queue.append(Arrays.asList(live ? liveItem("A") : item("A"), item("B")));
        queue.setRecovery(0, 12_000);
        queue.setRecovery(1, 34_000);
        final var db = helper.getWritableDatabase();
        // Compare the encoded membership/order prefix, leaving the trailing progress map out.
        // No JSON SQLite extension is needed. Reorders/inserts remain writable.
        db.execSQL("CREATE TRIGGER reject_checkpoint BEFORE INSERT ON persistent_queue_state "
                + "WHEN substr(CAST(NEW.state AS TEXT),1,instr(CAST(NEW.state AS TEXT),'\"progress\":')) "
                + "= (SELECT substr(CAST(state AS TEXT),1,instr(CAST(state AS TEXT),'\"progress\":')) "
                + "FROM persistent_queue_state WHERE id=1) "
                + "BEGIN SELECT RAISE(ABORT,'checkpoint disk failure'); END");
        // Prove that membership writes WOULD succeed, then roll the probe back on disk.
        db.beginTransaction();
        try {
            final var probe = new PersistentPlayQueue(new PersistentQueueStore(db));
            probe.setIndex(1);
            assertEquals("B", probe.getItem().getUrl());
            final PlayQueue incoming = new SinglePlayQueue(Collections.emptyList(), 0);
            incoming.append(item("probe"));
            probe.insert(incoming, true, false);
            assertEquals("probe", probe.getItem().getUrl());
        } finally {
            db.endTransaction();
        }
    }

    private void assertFailedCheckpointPreservedQueue(final Player player, final String head) {
        assertFalse(player.getPlayWhenReady());
        assertSame(queue, player.getPlayQueue());
        assertEquals(2, queue.size());
        assertEquals(head, queue.getItem().getUuid());
        assertEquals("B", queue.getItem(1).getUrl());
        assertEquals(12_000, queue.getRecoveryPosition(0));
        assertEquals(34_000, queue.getRecoveryPosition(1));
        final var restored = new PersistentPlayQueue(new PersistentQueueStore(helper.getWritableDatabase()));
        assertEquals(head, restored.getItem().getUuid());
        assertEquals(queue.getItem(1).getUuid(), restored.getItem(1).getUuid());
        assertEquals(12_000, restored.getRecoveryPosition(0));
        assertEquals(34_000, restored.getRecoveryPosition(1));
    }

    private static void setPlayerQueue(final Player player, final PlayQueue playQueue) {
        try {
            final var setter = Player.class.getDeclaredMethod("setPlayQueue", PlayQueue.class);
            setter.setAccessible(true);
            setter.invoke(player, playQueue);
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private void withPlayer(final boolean persistent, final java.util.function.Consumer<Player> test) {
        withPlayer(persistent, false, test);
    }

    private void withPreparedPlayer(final java.util.function.Consumer<Player> test) {
        withPlayer(true, true, test);
    }

    private void withPreparedPlayer(final java.util.function.Consumer<Player> test,
                                    final java.util.function.Consumer<Player> afterCallbacks) {
        withPlayer(true, true, test, afterCallbacks);
    }

    private void withPlayer(final boolean persistent, final boolean prepared,
                            final java.util.function.Consumer<Player> test) {
        withPlayer(persistent, prepared, test, null);
    }

    private void withPlayer(final boolean persistent, final boolean prepared,
                            final java.util.function.Consumer<Player> test,
                            final java.util.function.Consumer<Player> afterCallbacks) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final boolean hadPreference = prefs.contains("persistent_queue_enabled");
        final boolean oldPreference = prefs.getBoolean("persistent_queue_enabled", true);
        final TestService service = new TestService(context);
        final Player[] player = {null};
        final com.google.android.exoplayer2.ExoPlayer[] exo = {null};
        final CountDownLatch ready = new CountDownLatch(1);
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        try {
            instrumentation.runOnMainSync(() -> {
                prefs.edit().putBoolean("persistent_queue_enabled", persistent).commit();
                final PlayerServiceInterface bridge = (PlayerServiceInterface)
                        java.lang.reflect.Proxy.newProxyInstance(PlayerServiceInterface.class.getClassLoader(),
                                new Class<?>[]{PlayerServiceInterface.class}, (proxy, method, args) -> {
                                    if (method.getName().equals("getInstance")) return service;
                                    if (method.getName().equals("isLandscape")) return false;
                                    throw new AssertionError("Unexpected service call: " + method.getName());
                                });
                player[0] = new Player(bridge);
                setPlayerQueue(player[0], queue);
                if (prepared) {
                    exo[0] = new com.google.android.exoplayer2.ExoPlayer.Builder(context).build();
                    setPreparedPlayer(player[0], exo[0]);
                    exo[0].addListener(new com.google.android.exoplayer2.Player.Listener() {
                        @Override public void onPlaybackStateChanged(final int state) {
                            if (state == com.google.android.exoplayer2.Player.STATE_READY) ready.countDown();
                        }
                    });
                    exo[0].setMediaSource(new com.google.android.exoplayer2.source.SilenceMediaSource.Factory()
                            .setDurationUs(600_000_000L).createMediaSource());
                    exo[0].seekTo(60_000);
                    exo[0].prepare();
                    player[0].getPersistentQueueController().attachQueue();
                }
            });
            if (prepared) assertTrue("In-memory media must prepare", ready.await(10, TimeUnit.SECONDS));
            instrumentation.runOnMainSync(() -> {
                if (prepared) {
                    assertEquals(com.google.android.exoplayer2.Player.STATE_READY, exo[0].getPlaybackState());
                    assertEquals(60_000, exo[0].getContentPosition());
                    exo[0].setPlayWhenReady(true);
                }
                test.accept(player[0]);
            });
            if (afterCallbacks != null) {
                instrumentation.waitForIdleSync();
                instrumentation.runOnMainSync(() -> afterCallbacks.accept(player[0]));
            }
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        } finally {
            instrumentation.runOnMainSync(() -> {
                try {
                    if (exo[0] != null) {
                        final var field = Player.class.getDeclaredField("exoPlayerEventAdapter");
                        field.setAccessible(true);
                        final var adapter = (com.google.android.exoplayer2.Player.Listener) field.get(player[0]);
                        if (adapter != null) exo[0].removeListener(adapter);
                        field.set(player[0], null);
                        setPreparedPlayer(player[0], null);
                        exo[0].release();
                    }
                    if (player[0] != null) closeUnstartedPlayer(player[0]);
                } catch (final ReflectiveOperationException error) {
                    throw new AssertionError(error);
                } finally {
                    final SharedPreferences.Editor restore = prefs.edit();
                    if (hadPreference) restore.putBoolean("persistent_queue_enabled", oldPreference);
                    else restore.remove("persistent_queue_enabled");
                    restore.commit();
                }
            });
        }
    }

    private void setPreparedPlayer(final Player player, final com.google.android.exoplayer2.ExoPlayer exo) {
        try {
            final var field = Player.class.getDeclaredField("simpleExoPlayer");
            field.setAccessible(true);
            field.set(player, exo);
            final var prepared = Player.class.getDeclaredMethod("setPrepared", boolean.class);
            prepared.setAccessible(true);
            prepared.invoke(player, exo != null);
            final var current = Player.class.getDeclaredMethod("setCurrentItem", PlayerMediaItem.class);
            current.setAccessible(true);
            current.invoke(player, exo == null ? null : queue.getItem());
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void closeUnstartedPlayer(final Player player) {
        // Full service startup was not called. Listener fixtures have a view binding, but no
        // NewPipe source manager or registered receiver; stop loops/animations and owned resources.
        player.getPersistentQueueController().close();
        try {
            final var progress = Player.class.getDeclaredMethod("getProgressController");
            progress.setAccessible(true);
            ((org.schabi.newpipe.player.PlayerProgressController) progress.invoke(player)).stopProgressLoop();
            if (player.getBinding() != null) cancelViewAnimations(player.getBinding().getRoot());
            final var sponsor = Player.class.getDeclaredMethod("getSponsorBlockController");
            sponsor.setAccessible(true);
            ((org.schabi.newpipe.player.SponsorBlockController) sponsor.invoke(player)).destroy();
            final var selector = Player.class.getDeclaredMethod("getTrackSelector");
            selector.setAccessible(true);
            ((com.google.android.exoplayer2.trackselection.DefaultTrackSelector)
                    selector.invoke(player)).release();
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void cancelViewAnimations(final android.view.View view) {
        view.animate().setListener(null).cancel();
        view.clearAnimation();
        if (view instanceof android.view.ViewGroup) {
            final var group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) cancelViewAnimations(group.getChildAt(i));
        }
    }

    private static final class TestService extends android.app.Service {
        TestService(final Context context) {
            attachBaseContext(context);
            setTheme(org.schabi.newpipe.R.style.DarkTheme);
        }
        @Override public android.os.IBinder onBind(final android.content.Intent intent) { return null; }
    }

    @Test
    public void consumesOnlyHeadAndPinsCurrentForDrag() {
        queue.append(Arrays.asList(item("A"), item("B"), item("C")));
        queue.move(0, 2);
        assertEquals("A", queue.getItem().getUrl());
        queue.move(2, 1);
        queue.consume();
        assertEquals("C", queue.getItem().getUrl());
        queue.error();
        assertEquals(2, queue.size());
        queue.consume();
        queue.consume();
        assertTrue(queue.isEmpty());
        queue.offsetIndex(1);
        assertTrue(queue.isEmpty());
    }

    @Test
    public void frozenOwnerAllowsShutdownCheckpointButRejectsMembership() {
        queue.append(item("A"));
        queue.setRecovery(0, 42);
        queue.freeze();
        queue.setRecovery(0, 99);
        assertEquals(42, queue.getRecoveryPosition(0));
        try {
            queue.remove(0);
            fail("Frozen membership must not be acknowledged");
        } catch (final IllegalStateException expected) {
            assertEquals(1, queue.size());
        }
    }

    @Test
    public void cancelledShuffleAndIndependentCopyLeaveLiveStateIntact() {
        queue.append(Arrays.asList(item("A"), item("B"), item("C")));
        final java.util.List<PlayerMediaItem> before = queue.getStreams();
        try {
            queue.shuffleUpcoming(() -> true);
            fail("Cancellation expected");
        } catch (final CancellationException expected) {
            assertEquals(before, queue.getStreams());
        }
        final java.util.List<PlayerMediaItem> copy = queue.materialize(() -> false);
        queue.clear();
        assertEquals(3, copy.size());
    }

    @Test
    public void additiveTableCreationPreservesExistingUserData() {
        helper.close();
        open();
        try (android.database.Cursor cursor = helper.getWritableDatabase().query(
                "SELECT value FROM existing_user_data")) {
            assertTrue(cursor.moveToFirst());
            assertEquals("kept", cursor.getString(0));
        }
        try (android.database.Cursor cursor = helper.getWritableDatabase().query(
                "PRAGMA synchronous")) {
            assertTrue(cursor.moveToFirst());
            assertEquals(2, cursor.getInt(0));
        }
    }
    private static final class Request {
        final PersistentPlayQueue.State source;
        final PersistentPlayQueue.Node boundary;
        final io.reactivex.rxjava3.core.SingleEmitter<PersistentPlayQueue.State> emitter;

        Request(final PersistentPlayQueue.State source, final PersistentPlayQueue.Node boundary,
                final io.reactivex.rxjava3.core.SingleEmitter<PersistentPlayQueue.State> emitter) {
            this.source = source;
            this.boundary = boundary;
            this.emitter = emitter;
        }

        void succeed(final String url, final org.schabi.newpipe.extractor.Page next) {
            emitter.onSuccess(PersistentPlayQueue.splice(source, boundary,
                    Collections.singletonList(stream(url)), next));
        }
    }

    private static final class Pages implements PersistentPlayQueue.PageResolver {
        final java.util.concurrent.BlockingQueue<Request> requests =
                new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public io.reactivex.rxjava3.core.Single<PersistentPlayQueue.State> resolve(
                final PersistentPlayQueue.State source, final PersistentPlayQueue.Node boundary) {
            calls.incrementAndGet();
            return io.reactivex.rxjava3.core.Single.create(emitter ->
                    requests.add(new Request(source, boundary, emitter)));
        }

        Request take() throws InterruptedException {
            final Request request = requests.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull("Missing page request", request);
            return request;
        }
    }

    private static org.schabi.newpipe.extractor.stream.StreamInfoItem stream(final String url) {
        final org.schabi.newpipe.extractor.stream.StreamInfoItem item =
                new org.schabi.newpipe.extractor.stream.StreamInfoItem(0, url, url,
                        org.schabi.newpipe.extractor.stream.StreamType.VIDEO_STREAM);
        item.setUploaderName("Uploader");
        return item;
    }

    private PlaylistPlayQueue remote(final String url, final String first) {
        return new PlaylistPlayQueue(0, url, new org.schabi.newpipe.extractor.Page("next"),
                Collections.singletonList(stream(first)), 0);
    }

    private void usePages(final Pages pages) {
        queue = new PersistentPlayQueue(new PersistentQueueStore(helper.getWritableDatabase()), pages);
    }

    private void awaitState(final java.util.function.BooleanSupplier predicate) {
        queue.getBroadcastReceiver().filter(event -> predicate.getAsBoolean())
                .timeout(5, java.util.concurrent.TimeUnit.SECONDS).blockingFirst();
    }

    @Test
    public void remoteSuffixAndRichContinuationSurviveRestartAndFailureRequiresExplicitRetry()
            throws Exception {
        queue.append(item("old"));
        final org.schabi.newpipe.extractor.Page page = new org.schabi.newpipe.extractor.Page(
                "next", "id", Arrays.asList("one", "two"),
                Collections.singletonMap("session", "cookie"), new byte[]{1, 2, 3});
        queue.insert(new PlaylistPlayQueue(0, "source", page,
                Arrays.asList(stream("excluded"), stream("selected")), 1), true, false);
        assertEquals(Arrays.asList("selected", "old"), urls(queue.getStreams()));
        queue.consume();
        assertTrue(queue.hasBlockedBoundary());
        final String oldUuid = queue.getItem(0).getUuid();
        helper.close();
        open();
        final Pages pages = new Pages();
        usePages(pages);
        assertTrue(queue.hasBlockedBoundary());
        assertEquals(oldUuid, queue.getItem(0).getUuid());
        queue.fetch();
        final Request failed = pages.take();
        assertEquals("source", failed.boundary.url);
        assertEquals("id", failed.boundary.page.getId());
        assertEquals(Arrays.asList("one", "two"), failed.boundary.page.getIds());
        assertEquals("cookie", failed.boundary.page.getCookies().get("session"));
        assertArrayEquals(new byte[]{1, 2, 3}, failed.boundary.page.getBody());
        failed.emitter.onError(new java.io.IOException("offline"));
        awaitState(() -> queue.getPendingError() != null);
        queue.fetch();
        assertEquals(1, pages.calls.get()); // Loader cannot repeatedly retry a retained failure.
        assertTrue(queue.hasBlockedBoundary());
        assertEquals(1, queue.size());
        queue.retryPending();
        pages.take().succeed("continued", null);
        awaitState(() -> queue.isComplete());
        assertNull(queue.getPendingError());
        assertEquals(Arrays.asList("continued", "old"), urls(queue.getStreams()));
    }

    @Test
    public void remoteBatchesMergeAtTheirOwnBoundaryDespiteConcurrentInsertions() throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source1", "A"), true, false);
        queue.insert(remote("source2", "B"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.fetch();
        final Request pending = pages.take();
        assertEquals("source2", pending.boundary.url);
        queue.insert(new SinglePlayQueue(Collections.singletonList(stream("next")), 0), false, true);
        pending.succeed("B2", null);
        awaitState(() -> queue.size() == 5);
        queue.fetch();
        final Request second = pages.take();
        assertEquals("source1", second.boundary.url);
        second.succeed("A2", null);
        awaitState(() -> queue.isComplete());
        assertEquals(Arrays.asList("B", "next", "B2", "A", "A2", "old"),
                urls(queue.getStreams()));
        helper.close();
        open();
        assertEquals(Arrays.asList("B", "next", "B2", "A", "A2", "old"),
                urls(queue.getStreams()));
    }

    @Test
    public void materializeResolvesEveryPageIntoIndependentCopyWithoutLiveMutation() throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        final java.util.List<PlayerMediaItem> before = queue.getStreams();
        final java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<java.util.List<PlayerMediaItem>> copy =
                    worker.submit(() -> queue.materialize(() -> false));
            pages.take().succeed("B", new org.schabi.newpipe.extractor.Page("second"));
            final Request second = pages.take();
            assertEquals("second", second.boundary.page.getUrl());
            second.succeed("C", null);
            assertEquals(Arrays.asList("A", "B", "C", "old"),
                    urls(copy.get(5, java.util.concurrent.TimeUnit.SECONDS)));
            assertEquals(before, queue.getStreams());
            assertFalse(queue.isComplete());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void shuffleAcceptsConcurrentCheckpointAndKeepsLatestProgress() throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        final String head = queue.getItem().getUuid();
        final java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<?> shuffle = worker.submit(() ->
                    queue.shuffleUpcoming(() -> false));
            final Request pending = pages.take();
            queue.setRecovery(0, 5000);
            pending.succeed("B", null);
            shuffle.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(head, queue.getItem().getUuid());
            assertEquals(5000, queue.getRecoveryPosition(0));
            assertTrue(queue.isComplete());
            assertEquals(3, queue.size());
            helper.close();
            open();
            assertEquals(5000, queue.getRecoveryPosition(0));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void inFlightCancelAndNetworkFailureLeaveLiveQueueAndDiskUnchanged() throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        final java.util.List<PlayerMediaItem> before = queue.getStreams();
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<?> shuffle = worker.submit(() ->
                    queue.shuffleUpcoming(cancel::get));
            final Request pending = pages.take();
            cancel.set(true);
            try {
                shuffle.get(5, java.util.concurrent.TimeUnit.SECONDS);
                fail("Cancellation expected");
            } catch (final java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof CancellationException);
            }
            assertTrue(pending.emitter.isDisposed());
            assertEquals(before, queue.getStreams());
            final java.util.concurrent.Future<?> copy = worker.submit(() -> queue.materialize(() -> false));
            pages.take().emitter.onError(new java.io.IOException("failed extraction"));
            try {
                copy.get(5, java.util.concurrent.TimeUnit.SECONDS);
                fail("Network failure expected");
            } catch (final java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
            helper.close();
            open();
            assertEquals(before, queue.getStreams());
            assertFalse(queue.isComplete());
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void frozenOwnerRejectsInFlightFetchResultAndObserversSurvivePlayerDispose() throws Exception {
        queue.insert(remote("source", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        final io.reactivex.rxjava3.core.Flowable<?> bus = queue.getBroadcastReceiver();
        queue.dispose();
        queue.init();
        assertSame(bus, queue.getBroadcastReceiver());
        queue.fetch();
        final Request pending = pages.take();
        queue.freeze();
        pending.succeed("B", null);
        helper.close();
        open();
        assertEquals(Collections.singletonList("A"), urls(queue.getStreams()));
        assertFalse(queue.isComplete());
    }

    @Test
    public void ordinaryAndPersistentQueueEqualityUsesVisibleContentAndIndex() {
        queue.append(item("A"));
        final SinglePlayQueue ordinary = new SinglePlayQueue(Collections.singletonList(stream("A")), 0);
        assertEquals(ordinary, queue);
        assertEquals(queue, ordinary);
        assertEquals(ordinary.hashCode(), queue.hashCode());
    }

    private static java.util.List<String> urls(final java.util.List<PlayerMediaItem> entries) {
        final java.util.List<String> urls = new java.util.ArrayList<>();
        for (final PlayerMediaItem item : entries) {
            urls.add(item.getUrl());
        }
        return urls;
    }

    @Test
    public void roomSchemaAcceptsExtraQueueTableOnExistingDatabaseAndReopen() {
        final String name = NAME + ".room";
        context.deleteDatabase(name);
        org.schabi.newpipe.database.AppDatabase room = androidx.room.Room.databaseBuilder(
                context, org.schabi.newpipe.database.AppDatabase.class, name).build();
        try {
            // Simulate a pre-feature database with Room's actual current schema.
            room.getOpenHelper().getWritableDatabase();
            room.close();
            final androidx.room.RoomDatabase.Callback callback = new androidx.room.RoomDatabase.Callback() {
                @Override
                public void onOpen(@NonNull final SupportSQLiteDatabase db) {
                    db.execSQL("PRAGMA synchronous=FULL");
                    PersistentQueueStore.createTable(db);
                }
            };
            room = androidx.room.Room.databaseBuilder(context,
                    org.schabi.newpipe.database.AppDatabase.class, name).addCallback(callback).build();
            final PersistentPlayQueue saved = new PersistentPlayQueue(new PersistentQueueStore(
                    room.getOpenHelper().getWritableDatabase()));
            saved.append(item("room-entry"));
            saved.setRecovery(0, 321);
            final String uuid = saved.getItem().getUuid();
            room.close();
            room = androidx.room.Room.databaseBuilder(context,
                    org.schabi.newpipe.database.AppDatabase.class, name).addCallback(callback).build();
            final PersistentPlayQueue restored = new PersistentPlayQueue(new PersistentQueueStore(
                    room.getOpenHelper().getWritableDatabase()));
            assertEquals(uuid, restored.getItem().getUuid());
            assertEquals(321, restored.getRecoveryPosition(0));
        } finally {
            room.close();
            context.deleteDatabase(name);
        }
    }

    @Test
    public void explicitSelectionOfVisibleFirstEntryMovesItAheadOfBlockedBoundary() {
        queue.append(item("old"));
        queue.insert(remote("source", "A"), true, false);
        queue.consume();
        assertTrue(queue.hasBlockedBoundary());
        final String uuid = queue.getItem(0).getUuid();
        queue.setIndex(0);
        assertFalse(queue.hasBlockedBoundary());
        assertEquals(uuid, queue.getItem().getUuid());
        assertEquals(1, queue.size());
        assertFalse(queue.isComplete());
    }

    @Test
    public void clearCancelsOldRequestAndDoesNotBlockFetchingNewSource() throws Exception {
        queue.insert(remote("old-source", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.fetch();
        final Request old = pages.take();
        queue.clear();
        assertTrue(old.emitter.isDisposed());
        queue.insert(remote("new-source", "B"), true, false);
        queue.fetch();
        final Request current = pages.take();
        assertEquals("new-source", current.boundary.url);
        current.succeed("B2", null);
        awaitState(() -> queue.isComplete());
        assertEquals(Arrays.asList("B", "B2"), urls(queue.getStreams()));
    }

    @Test
    public void serializedIntentCacheReturnsIdenticalLiveOwnerIncludingPendingSources() {
        queue.insert(remote("source", "A"), true, false);
        final String key = org.schabi.newpipe.util.SerializedCache.getInstance()
                .put(queue, PlayQueue.class);
        assertNotNull(key);
        final PlayQueue restored = org.schabi.newpipe.util.SerializedCache.getInstance()
                .take(key, PlayQueue.class);
        assertSame(queue, restored);
        assertFalse(restored.isComplete());
        restored.setRecovery(0, 321);
        assertEquals(321, queue.getRecoveryPosition(0));
    }

    @Test
    public void directQueueSelectionCheckpointsInterruptedHeadBeforeReordering() {
        queue.append(Arrays.asList(item("A"), item("B")));
        final String interrupted = queue.getItem().getUuid();
        queue.setBeforeHeadChange(() -> queue.setRecovery(0, 8765));
        queue.setIndex(1);
        assertEquals(interrupted, queue.getItem(1).getUuid());
        assertEquals(8765, queue.getRecoveryPosition(1));
        queue.insert(new SinglePlayQueue(Collections.singletonList(stream("C")), 0), true, false);
        assertEquals("B", queue.getItem(1).getUrl());
        assertEquals(8765, queue.getRecoveryPosition(1));
        queue.setBeforeHeadChange(null);
        helper.close();
        open();
        assertEquals(8765, queue.getRecoveryPosition(1));
        assertEquals(8765, queue.getRecoveryPosition(2));
    }

    @Test
    public void prependedRemoteLoadsAllOwnPagesBeforeRetainedOlderBoundaryFailure() throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source-A", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.consume();
        queue.fetch();
        final java.io.IOException errorA = new java.io.IOException("A offline");
        pages.take().emitter.onError(errorA);
        awaitState(() -> queue.getPendingError() == errorA);

        queue.insert(remote("source-B", "B"), true, false);
        assertNull(queue.getPendingError());
        queue.consume();
        assertTrue(queue.hasBlockedBoundary());
        queue.fetch();
        final Request firstB = pages.take();
        assertEquals("source-B", firstB.boundary.url);
        firstB.succeed("B2", new org.schabi.newpipe.extractor.Page("B-next"));
        awaitState(() -> !queue.hasBlockedBoundary());
        assertNull(queue.getPendingError());
        queue.consume();
        queue.fetch();
        final Request secondB = pages.take();
        assertEquals("B-next", secondB.boundary.page.getUrl());
        secondB.succeed("B3", null);
        awaitState(() -> !queue.hasBlockedBoundary());
        assertEquals("B3", queue.getItem().getUrl());
        assertSame(errorA, queue.getPendingError());
        assertFalse(queue.hasBlockedBoundary()); // Loaded B3 remains playable ahead of A's failure.
        queue.consume();
        assertTrue(queue.hasBlockedBoundary());
        assertSame(errorA, queue.getPendingError());
        final int before = pages.calls.get();
        queue.fetch();
        assertEquals(before, pages.calls.get());
        queue.retryPending();
        final Request retryA = pages.take();
        assertEquals("source-A", retryA.boundary.url);
        retryA.succeed("A2", null);
        awaitState(() -> queue.isComplete());
        assertNull(queue.getPendingError());
        assertEquals(Arrays.asList("A2", "old"), urls(queue.getStreams()));
        helper.close();
        open();
        assertEquals(Arrays.asList("A2", "old"), urls(queue.getStreams()));
    }

    @Test
    public void failedBRetryDoesNotClearFailedAWithIdenticalSourceUrl() throws Exception {
        queue.insert(remote("same-source", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.consume();
        queue.fetch();
        final java.io.IOException errorA = new java.io.IOException("A failure");
        pages.take().emitter.onError(errorA);
        awaitState(() -> queue.getPendingError() == errorA);
        queue.insert(remote("same-source", "B"), true, false);
        queue.consume();
        queue.fetch();
        final java.io.IOException errorB = new java.io.IOException("B failure");
        pages.take().emitter.onError(errorB);
        awaitState(() -> queue.getPendingError() == errorB);
        queue.retryPending();
        pages.take().succeed("B2", null);
        awaitState(() -> !queue.hasBlockedBoundary());
        assertSame(errorA, queue.getPendingError());
        queue.consume();
        assertTrue(queue.hasBlockedBoundary());
        assertSame(errorA, queue.getPendingError());
        final int before = pages.calls.get();
        queue.fetch();
        assertEquals(before, pages.calls.get());
        queue.retryPending();
        pages.take().succeed("A2", null);
        awaitState(() -> queue.isComplete());
        assertEquals(Collections.singletonList("A2"), urls(queue.getStreams()));
    }

    @Test
    public void olderInFlightFailureAfterPrependCannotPoisonNewSourceOrOverwriteEdits() throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source-A", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.fetch();
        final Request pendingA = pages.take();
        queue.insert(remote("source-B", "B"), true, false);
        queue.setRecovery(0, 456);
        queue.append(item("concurrent"));
        final java.io.IOException errorA = new java.io.IOException("late A failure");
        pendingA.emitter.onError(errorA);
        assertNull(queue.getPendingError());
        queue.consume();
        queue.fetch();
        final Request pendingB = pages.take();
        assertEquals("source-B", pendingB.boundary.url);
        pendingB.succeed("B2", null);
        awaitState(() -> queue.size() == 4);
        assertEquals(Arrays.asList("B2", "A", "old", "concurrent"), urls(queue.getStreams()));
        queue.consume();
        assertEquals("A", queue.getItem().getUrl());
        assertFalse(queue.hasBlockedBoundary());
        assertSame(errorA, queue.getPendingError());
        queue.consume();
        assertTrue(queue.hasBlockedBoundary());
        assertSame(errorA, queue.getPendingError());
        // Transient errors may be retried after paused restart, but both boundaries/order persist.
        helper.close();
        open();
        usePages(pages);
        assertTrue(queue.hasBlockedBoundary());
        assertNull(queue.getPendingError());
        queue.fetch();
        final Request restoredA = pages.take();
        assertEquals("source-A", restoredA.boundary.url);
        restoredA.succeed("A2", null);
        awaitState(() -> queue.isComplete());
        assertEquals(Arrays.asList("A2", "old", "concurrent"), urls(queue.getStreams()));
    }

    @Test
    public void reverseCopyAndLiveResponsesKeepFailureOnLiveBoundaryOnly() throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source-A", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.fetch();
        final Request liveA = pages.take(); // Oldest request will complete after newer B's copy.
        queue.insert(remote("source-B", "B"), true, false);
        final java.util.List<PlayerMediaItem> before = queue.getStreams();
        final java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<java.util.List<PlayerMediaItem>> copy =
                    worker.submit(() -> queue.materialize(() -> false));
            final Request copyB = pages.take();
            assertEquals("source-B", copyB.boundary.url);
            copyB.succeed("B-copy", null);
            final Request copyA = pages.take();
            assertEquals("source-A", copyA.boundary.url);
            final java.io.IOException errorA = new java.io.IOException("late live failure");
            liveA.emitter.onError(errorA);
            copyA.succeed("A-copy", null);
            assertEquals(Arrays.asList("B", "B-copy", "A", "A-copy", "old"),
                    urls(copy.get(5, java.util.concurrent.TimeUnit.SECONDS)));
            assertEquals(before, queue.getStreams());
            assertNull(queue.getPendingError()); // B's pending boundary, not failed A, is first.
            queue.consume();
            queue.fetch();
            pages.take().succeed("B-live", null);
            awaitState(() -> !queue.hasBlockedBoundary());
            assertSame(errorA, queue.getPendingError()); // Copy success never acknowledges live A.
            assertEquals(Arrays.asList("B-live", "A", "old"), urls(queue.getStreams()));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void cancelledShufflePreservesMultipleErrorsAndClearReleasesAllOfThem() throws Exception {
        queue.insert(remote("source-A", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.consume();
        queue.fetch();
        final java.io.IOException errorA = new java.io.IOException("A failed");
        pages.take().emitter.onError(errorA);
        queue.insert(remote("source-B", "B"), true, false);
        queue.consume();
        queue.fetch();
        final java.io.IOException errorB = new java.io.IOException("B failed");
        pages.take().emitter.onError(errorB);
        assertSame(errorB, queue.getPendingError());
        final java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean();
        final java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<?> shuffle = worker.submit(() ->
                    queue.shuffleUpcoming(cancel::get));
            final Request retryB = pages.take();
            retryB.succeed("B-copy", null);
            final Request retryA = pages.take();
            cancel.set(true);
            try {
                shuffle.get(5, java.util.concurrent.TimeUnit.SECONDS);
                fail("Cancellation expected");
            } catch (final java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof CancellationException);
            }
            assertTrue(retryA.emitter.isDisposed());
            assertSame(errorB, queue.getPendingError());
            assertTrue(queue.hasBlockedBoundary());
            queue.retryPending();
            pages.take().succeed("B-live", null);
            awaitState(() -> !queue.hasBlockedBoundary());
            assertSame(errorA, queue.getPendingError());
            queue.clear();
            assertNull(queue.getPendingError());
            assertTrue(queue.isComplete());
            assertTrue(queue.isEmpty());
            queue.insert(remote("source-A", "fresh-A"), true, false);
            queue.fetch();
            pages.take().succeed("fresh-A2", null);
            awaitState(() -> queue.isComplete());
            assertEquals(Arrays.asList("fresh-A", "fresh-A2"), urls(queue.getStreams()));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void selectingAndCheckpointingPastFailedBoundaryRetainsItForLaterRetry() throws Exception {
        queue.append(item("selected-old"));
        queue.insert(remote("source-A", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.consume();
        queue.fetch();
        final java.io.IOException errorA = new java.io.IOException("A failed");
        pages.take().emitter.onError(errorA);
        awaitState(() -> queue.getPendingError() == errorA);
        assertTrue(queue.hasBlockedBoundary());
        final String selectedUuid = queue.getItem(0).getUuid();
        queue.setIndex(0);
        queue.setRecovery(0, 1234);
        assertEquals(selectedUuid, queue.getItem().getUuid());
        assertFalse(queue.hasBlockedBoundary());
        assertSame(errorA, queue.getPendingError());
        queue.consume();
        assertTrue(queue.hasBlockedBoundary());
        assertTrue(queue.isEmpty());
        assertFalse(queue.isComplete());
        assertSame(errorA, queue.getPendingError());
        final int before = pages.calls.get();
        queue.fetch();
        assertEquals(before, pages.calls.get());
        queue.retryPending();
        pages.take().succeed("A2", null);
        awaitState(() -> queue.isComplete());
        assertEquals(Collections.singletonList("A2"), urls(queue.getStreams()));
    }

    @Test
    public void successfulShuffleCommitsMaterializedBoundariesAndReleasesOnlyResolvedErrors()
            throws Exception {
        queue.append(item("old"));
        queue.insert(remote("source-A", "A"), true, false);
        final Pages pages = new Pages();
        usePages(pages);
        queue.fetch();
        final java.io.IOException errorA = new java.io.IOException("A failed");
        pages.take().emitter.onError(errorA);
        queue.insert(remote("source-B", "B"), true, false);
        queue.fetch();
        final java.io.IOException errorB = new java.io.IOException("B failed");
        pages.take().emitter.onError(errorB);
        final String head = queue.getItem().getUuid();
        assertSame(errorB, queue.getPendingError());
        final java.util.concurrent.ExecutorService worker =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final java.util.concurrent.Future<?> shuffled = worker.submit(() ->
                    queue.shuffleUpcoming(() -> false));
            final Request retryB = pages.take();
            assertEquals("source-B", retryB.boundary.url);
            retryB.succeed("B2", null);
            final Request retryA = pages.take();
            assertEquals("source-A", retryA.boundary.url);
            retryA.succeed("A2", null);
            shuffled.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(queue.isComplete());
            assertNull(queue.getPendingError());
            assertEquals(head, queue.getItem().getUuid());
            assertEquals(5, queue.size());
            helper.close();
            open();
            assertTrue(queue.isComplete());
            assertNull(queue.getPendingError());
            assertEquals(head, queue.getItem().getUuid());
            assertEquals(5, queue.size());
        } finally {
            worker.shutdownNow();
        }
    }

}
