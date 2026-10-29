package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;

/** Real SQLite file reopen/rollback tests; no network or player timing dependency. */
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

}
