package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.source.BaseMediaSource;
import com.google.android.exoplayer2.source.MediaPeriod;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.upstream.Allocator;
import com.google.android.exoplayer2.upstream.TransferListener;
import io.reactivex.rxjava3.core.Single;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.mediaitem.ExoMediaItems;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.mediaitem.StreamInfoResolver;
import org.schabi.newpipe.player.playback.MediaSourceManager;
import org.schabi.newpipe.player.playback.PlaybackListener;

/** Exercises the actual source-manager event/reactor path with SQLite and controlled extraction. */
@RunWith(AndroidJUnit4.class)
public class PersistentSourcePlaybackTest {
    private SupportSQLiteOpenHelper helper;
    private PersistentPlayQueue queue;
    private MediaSourceManager manager;
    private final RecordingPlayback playback = new RecordingPlayback();

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        helper = new FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                        .callback(new SupportSQLiteOpenHelper.Callback(1) {
                            @Override public void onCreate(@NonNull final SupportSQLiteDatabase db) {
                                PersistentQueueStore.createTable(db);
                            }
                            @Override public void onUpgrade(@NonNull final SupportSQLiteDatabase db,
                                                            final int from, final int to) { }
                        }).build());
        queue = new PersistentPlayQueue(new PersistentQueueStore(helper.getWritableDatabase()),
                (state, boundary) -> Single.error(new IllegalStateException("page offline")));
    }

    @After
    public void tearDown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (manager != null) manager.dispose();
            queue.freeze();
        });
        helper.close();
    }

    @Test
    public void failedLaterPageAllowsSelectedPreservedAndNewHeadsButBlocksAtBoundary()
            throws Exception {
        queue.append(PlayerMediaItem.forStreamInfoItem(stream("preserved")));
        queue.insert(new PlaylistPlayQueue(0, "source", new Page("next"),
                Collections.singletonList(stream("loaded")), 0), true, false);
        queue.fetch();
        queue.getBroadcastReceiver().filter(event -> queue.getPendingError() != null)
                .timeout(5, TimeUnit.SECONDS).blockingFirst();
        queue.consume(); // The failed boundary is now ahead of the preserved occurrence.
        start(item -> Single.just(info(item)));
        assertNotNull(playback.failures.poll(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        playback.failures.clear(); // INIT and the replayed owner event may both report the block.
        assertTrue(playback.started.isEmpty());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> queue.setIndex(0));
        assertEquals("preserved", playback.started.poll(5, TimeUnit.SECONDS));
        assertNotNull(queue.getPendingError()); // Error stays available for its later boundary.
        assertFalse(queue.hasBlockedBoundary());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                queue.insert(new SinglePlayQueue(stream("new")), true, false));
        assertEquals("new", playback.started.poll(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(queue::consume);
        assertEquals("preserved", playback.started.poll(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(queue::consume);
        assertNotNull(playback.failures.poll(5, TimeUnit.SECONDS));
        assertTrue(queue.hasBlockedBoundary());
        assertTrue(playback.started.isEmpty());
    }

    @Test
    public void failedExtractionRetainsOccurrenceAndRebuiltManagerRetriesSameHead() throws Exception {
        queue.append(PlayerMediaItem.forStreamInfoItem(stream("retry")));
        final String occurrence = queue.getItem().getUuid();
        final AtomicInteger attempts = new AtomicInteger();
        final StreamInfoResolver resolver = item -> attempts.incrementAndGet() == 1
                ? Single.error(new IllegalStateException("extraction offline"))
                : Single.just(info(item));
        start(resolver);
        assertNotNull(playback.failures.poll(5, TimeUnit.SECONDS));
        assertEquals(occurrence, queue.getItem().getUuid());
        assertEquals(1, queue.size());
        assertTrue(playback.started.isEmpty()); // No silence source that could falsely complete.
        // Explicit retry rebuilds exactly this manager, as the bridge's retry policy requests.
        start(resolver);
        assertEquals("retry", playback.started.poll(5, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
        assertEquals(occurrence, queue.getItem().getUuid());
    }

    private void start(final StreamInfoResolver resolver) throws Exception {
        // Reuse the existing dependency-injected constructor without widening production API.
        final var constructor = MediaSourceManager.class.getDeclaredConstructor(Context.class,
                PlaybackListener.class, PlayQueue.class, StreamInfoResolver.class,
                long.class, long.class, long.class);
        constructor.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (manager != null) manager.dispose();
            try {
                manager = constructor.newInstance(
                        InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        playback, queue, resolver, 400L, 30_000L, 2_000L);
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
    }

    private static StreamInfoItem stream(final String url) {
        return new StreamInfoItem(0, url, url, StreamType.VIDEO_STREAM);
    }

    private static StreamInfo info(final PlayerMediaItem item) {
        return new StreamInfo(0, item.getUrl(), item.getUrl(), item.getTitle());
    }

    private static final class RecordingPlayback implements PlaybackListener {
        final LinkedBlockingQueue<String> started = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        @Override public boolean isApproachingPlaybackEdge(final long gap) { return false; }
        @Override public void onPlaybackBlock() { }
        @Override public void onPlaybackFailure(final Throwable error) { failures.add(error); }
        @Override public void onPlaybackUnblock(final MediaSource source) {
            started.add(ExoMediaItems.fromMediaItem(source.getMediaItem()).get().getUrl());
        }
        @Override public void onPlaybackSynchronize(final PlayerMediaItem item,
                                                    final boolean wasBlocked) { }
        @Override public MediaSource sourceOf(final PlayerMediaItem item, final StreamInfo info) {
            return new TaggedSource(item);
        }
        @Override public void onPlaybackShutdown() { }
        @Override public void onPlayQueueEdited() { }
    }

    /** A tagged source need not prepare real media to test manager attachment/readiness. */
    private static final class TaggedSource extends BaseMediaSource {
        private final MediaItem item;
        TaggedSource(final PlayerMediaItem item) { this.item = ExoMediaItems.asExoMediaItem(item); }
        @Override public MediaItem getMediaItem() { return item; }
        @Override protected void prepareSourceInternal(final TransferListener listener) { }
        @Override public void maybeThrowSourceInfoRefreshError() { }
        @Override protected void releaseSourceInternal() { }
        @Override public MediaPeriod createPeriod(final MediaSource.MediaPeriodId id,
                                                  final Allocator allocator, final long position) {
            throw new UnsupportedOperationException("Test does not prepare media");
        }
        @Override public void releasePeriod(final MediaPeriod period) { }
    }
}
