package org.schabi.newpipe.info_list.holder;

import static org.junit.Assert.*;

import android.content.Context;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.feed.service.LargeFeedRefreshFixture;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.SingleSubject;

@RunWith(AndroidJUnit4.class)
public class StreamProgressBinderTest {
    @Test
    public void lateHistoryCannotOverwriteReboundRow() throws Exception {
        withBinder((binder, progress, context) -> {
            final var first = SingleSubject.<StreamStateEntity[]>create();
            final var second = SingleSubject.<StreamStateEntity[]>create();
            final var history = new HistoryRecordManager(context) {
                @Override
                public Single<StreamStateEntity[]> loadStreamState(final InfoItem item) {
                    return item.getUrl().endsWith("A") ? first : second;
                }
            };
            main(() -> binder.load(item("A"), history, false));
            awaitSubscribed(first);
            main(() -> binder.load(item("B"), history, false));
            awaitSubscribed(second);
            assertFalse("Rebinding must cancel the previous query", first.hasObservers());
            second.onSuccess(new StreamStateEntity[]{new StreamStateEntity(2, 20_000)});
            first.onSuccess(new StreamStateEntity[]{new StreamStateEntity(1, 50_000)});
            main(() -> {
                assertEquals(View.VISIBLE, progress.getVisibility());
                assertEquals(20, progress.getProgress());
            });
        });
    }

    @Test
    public void recyclingCancelsHistoryAndKeepsProgressHidden() throws Exception {
        withBinder((binder, progress, context) -> {
            final var pending = SingleSubject.<StreamStateEntity[]>create();
            final var history = pendingHistory(context, pending);
            main(() -> binder.load(item("A"), history, false));
            awaitSubscribed(pending);
            main(binder::recycle);
            assertFalse(pending.hasObservers());
            pending.onSuccess(new StreamStateEntity[]{new StreamStateEntity(1, 20_000)});
            main(() -> assertEquals(View.GONE, progress.getVisibility()));
        });
    }

    @Test
    public void failedHistoryLeavesRowUsableAndProgressHidden() throws Exception {
        withBinder((binder, progress, context) -> {
            final var pending = SingleSubject.<StreamStateEntity[]>create();
            main(() -> binder.load(item("A"), pendingHistory(context, pending), false));
            awaitSubscribed(pending);
            pending.onError(new IOException("Synthetic history failure"));
            main(() -> assertEquals(View.GONE, progress.getVisibility()));
        });
    }

    private HistoryRecordManager pendingHistory(final Context context,
                                                final SingleSubject<StreamStateEntity[]> pending) {
        return new HistoryRecordManager(context) {
            @Override
            public Single<StreamStateEntity[]> loadStreamState(final InfoItem item) {
                return pending;
            }
        };
    }

    private void withBinder(final Check check) throws Exception {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = new ContextThemeWrapper(instrumentation.getTargetContext(),
                R.style.DarkTheme);
        try (var ignored = new LargeFeedRefreshFixture()) {
            final var progress = new AtomicReference<AnimatedProgressBar>();
            final var binder = new AtomicReference<StreamProgressBinder>();
            main(() -> {
                progress.set(new AnimatedProgressBar(context));
                // Match the determinate progress widget declared in the row layouts.
                progress.get().setIndeterminate(false);
                binder.set(new StreamProgressBinder(progress.get()));
            });
            try { check.run(binder.get(), progress.get(), context); }
            finally { main(binder.get()::recycle); }
        }
    }

    private static StreamInfoItem item(final String suffix) {
        final var item = new StreamInfoItem(0, "https://example.invalid/" + suffix,
                "Synthetic row " + suffix, StreamType.VIDEO_STREAM);
        item.setDuration(60);
        return item;
    }

    private static void awaitSubscribed(final SingleSubject<?> subject) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + 3000;
        while (!subject.hasObservers() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(5);
        assertTrue("History query did not subscribe", subject.hasObservers());
    }

    private static void main(final Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private interface Check {
        void run(StreamProgressBinder binder, AnimatedProgressBar progress, Context context)
                throws Exception;
    }
}
