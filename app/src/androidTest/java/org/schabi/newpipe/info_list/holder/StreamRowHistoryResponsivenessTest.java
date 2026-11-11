package org.schabi.newpipe.info_list.holder;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.local.feed.service.LargeFeedRefreshFixture;
import org.schabi.newpipe.local.history.HistoryRecordManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import io.reactivex.rxjava3.core.Single;

/** Regression for the blocking history read captured during the actual MainActivity repro. */
@RunWith(AndroidJUnit4.class)
public class StreamRowHistoryResponsivenessTest {
    @Test
    public void regularRowDoesNotWaitForPendingHistory() throws Exception {
        checkRow(false, false);
    }

    @Test
    public void gridRowDoesNotWaitForPendingHistory() throws Exception {
        checkRow(true, false);
    }

    @Test
    public void miniRowDoesNotWaitForPendingHistory() throws Exception {
        checkRow(false, true);
    }

    private void checkRow(final boolean grid, final boolean mini) throws Exception {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        final Context context = new ContextThemeWrapper(instrumentation.getTargetContext(),
                R.style.DarkTheme);
        try (var ignored = new LargeFeedRefreshFixture()) {
            final HistoryRecordManager history = new HistoryRecordManager(context) {
                @Override
                public Single<StreamStateEntity[]> loadStreamState(final InfoItem item) {
                    // A contended database can complete later; binding must still return now.
                    return Single.timer(500, TimeUnit.MILLISECONDS)
                            .map(tick -> new StreamStateEntity[]{null});
                }
            };
            final AtomicLong bindMs = new AtomicLong();
            final AtomicLong updateMs = new AtomicLong();
            instrumentation.runOnMainSync(() -> {
                final var builder = new InfoItemBuilder(context);
                final var parent = new FrameLayout(context);
                final InfoItemHolder holder = mini ? new StreamMiniInfoItemHolder(builder, parent)
                        : grid ? new StreamGridInfoItemHolder(builder, parent)
                        : new StreamInfoItemHolder(builder, parent);
                final var item = new StreamInfoItem(0,
                        "https://www.youtube.com/watch?v=aqz-KE-bpKQ",
                        "Synthetic delayed-history row", StreamType.VIDEO_STREAM);
                item.setDuration(60);
                item.setUploaderName("Synthetic uploader");
                final long bindStart = SystemClock.elapsedRealtime();
                holder.updateFromItem(item, history);
                bindMs.set(SystemClock.elapsedRealtime() - bindStart);
                final long updateStart = SystemClock.elapsedRealtime();
                holder.updateState(item, history);
                updateMs.set(SystemClock.elapsedRealtime() - updateStart);
            });
            assertTrue("Binding waited for pending history: " + bindMs.get() + "ms",
                    bindMs.get() < 250);
            assertTrue("State refresh waited for pending history: " + updateMs.get() + "ms",
                    updateMs.get() < 250);
        }
    }
}
