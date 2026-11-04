package org.schabi.newpipe.player.playqueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import io.reactivex.rxjava3.processors.BehaviorProcessor;

@RunWith(AndroidJUnit4.class)
public class QueueHistoryTest {
    private StreamHistoryEntry entry(final String url, final long id) {
        return new StreamHistoryEntry(new StreamEntity(new StreamInfoItem(0, url, url,
                StreamType.VIDEO_STREAM)), id, OffsetDateTime.now(), 1);
    }

    @Test
    public void mergedRecentHistoryExcludesCurrentAndReflectsDeletionAndPreferences() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String enabled = context.getString(R.string.enable_watch_history_key);
        final String depth = "persistent_queue_history_depth";
        final boolean oldEnabled = prefs.getBoolean(enabled, true);
        final String oldDepth = prefs.getString(depth, "20");
        final BehaviorProcessor<java.util.List<StreamHistoryEntry>> source =
                BehaviorProcessor.createDefault(Collections.emptyList());
        final QueueHistory history = new QueueHistory(context, source);
        try {
            prefs.edit().putBoolean(enabled, true).putString(depth, "20").commit();
            source.onNext(Arrays.asList(entry("C", 3), entry("B", 2), entry("B", 2), entry("A", 1)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(3, history.getItems().size());
            final PlayerMediaItem current = new PlayerMediaItem.Builder().serviceId(0).url("C").build();
            assertEquals("B", history.excluding(current).get(0).getUrl());
            assertEquals(2, history.excluding(current).size());

            source.onNext(Arrays.asList(entry("C", 3), entry("A", 1)));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("A", history.excluding(current).get(0).getUrl());
            prefs.edit().putString(depth, "0").commit();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(history.getItems().isEmpty());
            prefs.edit().putString(depth, "20").putBoolean(enabled, false).commit();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(history.getItems().isEmpty());
            prefs.edit().putBoolean(enabled, true).commit();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(2, history.getItems().size());
        } finally {
            history.close();
            prefs.edit().putBoolean(enabled, oldEnabled).putString(depth, oldDepth).commit();
        }
    }
}
