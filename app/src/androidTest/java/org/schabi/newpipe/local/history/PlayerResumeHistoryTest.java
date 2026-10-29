package org.schabi.newpipe.local.history;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.util.InfoCache;

/** Real history DAO and cached extraction at the API used by PlayerHistoryController. */
@RunWith(AndroidJUnit4.class)
public class PlayerResumeHistoryTest {
    private static final String URL = "https://www.youtube.com/watch?v=resume-test";
    private AppDatabase database;
    private HistoryRecordManager history;
    private StreamInfo resolved;
    private PlayerMediaItem sparse;

    @Before
    public void setUp() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
        history = new HistoryRecordManager(context, database);
        resolved = new StreamInfo(0, "resume-test", URL, "Resolved video");
        resolved.setStreamType(StreamType.VIDEO_STREAM);
        resolved.setDuration(600);
        resolved.setUploaderName("Uploader");
        sparse = new PlayerMediaItem.Builder().serviceId(0).url(URL)
                .duration(-1).streamType(StreamType.VIDEO_STREAM).build();
        InfoCache.getInstance().putInfo(0, URL, resolved, InfoItem.InfoType.STREAM);
    }

    @After
    public void tearDown() {
        InfoCache.getInstance().removeInfo(0, URL, InfoItem.InfoType.STREAM);
        database.close();
    }

    private void save(final long progressMillis) {
        final long id = database.streamDAO().upsert(new StreamEntity(resolved));
        database.streamStateDAO().upsert(new StreamStateEntity(id, progressMillis));
    }

    private void assertNoResume() {
        history.loadPlaybackResumePosition(sparse).test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete().assertNoErrors().assertNoValues();
    }

    @Test
    public void unfinishedSparseRowUsesResolvedDurationForResume() {
        save(60_000);
        history.loadPlaybackResumePosition(sparse).test().awaitDone(5, TimeUnit.SECONDS)
                .assertResult(60_000L);
        // Resolving history must not replace the occurrence or mutate its sparse source row.
        assertEquals(-1, sparse.getDuration());
        assertFalse(new StreamStateEntity(1, 60_000).isFinished(resolved.getDuration()));
    }

    @Test
    public void finishedSparseRowDoesNotResume() {
        save(550_000);
        assertNoResume();
    }

    @Test
    public void sparseRowValidityUsesResolvedDuration() {
        save(0);
        history.loadStreamState(sparse).test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete().assertNoErrors().assertNoValues();
        assertNoResume();
    }

    @Test
    public void resolvedShortStreamRetainsValidQuarterProgress() {
        resolved.setDuration(8);
        save(3_000);
        history.loadPlaybackResumePosition(sparse).test().awaitDone(5, TimeUnit.SECONDS)
                .assertResult(3_000L);
    }

    @Test
    public void resolvedRoundPlayStreamDoesNotResume() {
        resolved.setRoundPlayStream(true);
        save(60_000);
        history.loadStreamState(sparse).test().awaitDone(5, TimeUnit.SECONDS)
                .assertComplete().assertNoErrors().assertNoValues();
        assertNoResume();
    }

    @Test
    public void unknownResolvedDurationDoesNotResume() {
        resolved.setDuration(-1);
        save(60_000);
        assertNoResume();
    }

    @Test
    public void missingHistoryReturnsNoPositionWithoutRecordingAView() {
        assertNoResume();
        database.streamHistoryDAO().getAll().test().awaitCount(1)
                .assertValue(entries -> entries.isEmpty()).cancel();
    }
}
