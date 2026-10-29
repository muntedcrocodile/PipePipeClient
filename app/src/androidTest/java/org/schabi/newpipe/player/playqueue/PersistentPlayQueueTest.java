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
        helper.getWritableDatabase().execSQL("CREATE TRIGGER reject_queue BEFORE INSERT ON "
                + "persistent_queue_state BEGIN SELECT RAISE(ABORT,'disk failure'); END");
        try {
            queue.append(item("B"));
            fail("Save must report failure");
        } catch (final RuntimeException expected) {
            assertEquals(1, queue.size());
        }
        // Delivery is scheduled on main; drain before counting only INIT from subscription.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        events.assertValueCount(1);
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
}
