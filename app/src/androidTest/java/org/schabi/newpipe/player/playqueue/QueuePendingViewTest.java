package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import io.reactivex.rxjava3.core.Single;

@RunWith(AndroidJUnit4.class)
public class QueuePendingViewTest {
    @Test
    public void restoredBoundaryOnlyQueueOffersLoadAndExplicitRetryWithoutStartingPlayback() {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = instrumentation.getTargetContext();
        final SupportSQLiteOpenHelper helper = new FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                        .callback(new SupportSQLiteOpenHelper.Callback(1) {
                            @Override
                            public void onCreate(@NonNull final SupportSQLiteDatabase db) {
                                PersistentQueueStore.createTable(db);
                            }
                            @Override
                            public void onUpgrade(@NonNull final SupportSQLiteDatabase db,
                                                  final int oldVersion, final int newVersion) { }
                        }).build());
        final AtomicReference<QueuePendingView> view = new AtomicReference<>();
        try {
            final PersistentQueueStore store = new PersistentQueueStore(helper.getWritableDatabase());
            final PersistentPlayQueue original = new PersistentPlayQueue(store);
            final StreamInfoItem loaded = new StreamInfoItem(0, "loaded", "loaded", StreamType.VIDEO_STREAM);
            original.insert(new PlaylistPlayQueue(0, "source", new Page("next"),
                    Collections.singletonList(loaded), 0), true, false);
            original.consume();
            final AtomicBoolean offline = new AtomicBoolean(true);
            final PersistentPlayQueue restored = new PersistentPlayQueue(store, (state, boundary) ->
                    offline.get() ? Single.error(new java.io.IOException("offline"))
                            : Single.just(PersistentPlayQueue.splice(state, boundary,
                                    Collections.singletonList(new StreamInfoItem(0, "continued",
                                            "continued", StreamType.VIDEO_STREAM)), null)));
            assertTrue(restored.isEmpty());
            assertFalse(restored.isComplete());
            instrumentation.runOnMainSync(() -> view.set(new QueuePendingView(context, restored)));
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                assertEquals(View.VISIBLE, view.get().getVisibility());
                final Button load = (Button) view.get().getChildAt(1);
                assertEquals(context.getString(R.string.queue_view_load), load.getText().toString());
                load.performClick();
            });
            restored.getBroadcastReceiver().filter(event -> restored.getPendingError() != null)
                    .timeout(5, TimeUnit.SECONDS).blockingFirst();
            instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(() -> {
                final Button retry = (Button) view.get().getChildAt(1);
                assertEquals(context.getString(R.string.queue_view_retry), retry.getText().toString());
                assertTrue(((TextView) view.get().getChildAt(0)).getText().toString().contains("offline"));
                assertTrue(restored.isEmpty());
                offline.set(false);
                retry.performClick();
            });
            restored.getBroadcastReceiver().filter(event -> restored.isComplete())
                    .timeout(5, TimeUnit.SECONDS).blockingFirst();
            instrumentation.waitForIdleSync();
            assertEquals("continued", restored.getItem().getUrl());
            instrumentation.runOnMainSync(() -> assertEquals(View.GONE, view.get().getVisibility()));
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (view.get() != null) {
                    view.get().dispose();
                }
            });
            helper.close();
        }
    }
}
