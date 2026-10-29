package org.schabi.newpipe.info_list;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.LocalItemListAdapter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Integration tests for opt-in safety and displayed-row identity without network/player startup. */
@RunWith(AndroidJUnit4.class)
public class VideoSwipeActionsTest {
    private Context context;
    private SharedPreferences prefs;
    private final Map<String, Object> original = new HashMap<>();
    private static final String[] KEYS = {"video_swipe_enabled", "video_swipe_left_action",
        "video_swipe_right_action", "video_swipe_left_action_playlist",
        "video_swipe_right_action_playlist", "video_swipe_left_action_history",
        "video_swipe_right_action_history", "local_playlist_swipe_action"};

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
    public void newControlsStayOffUntilOptInAndNoneHasNoFallback() {
        final VideoSwipeActions.Row playlist = new VideoSwipeActions.Row(video("one"), () -> { }, null);
        prefs.edit().putString("local_playlist_swipe_action", "delete").commit();
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
    public void legacyPlaylistActionsApplyOnlyBeforeOptIn() {
        final VideoSwipeActions.Row playlist = new VideoSwipeActions.Row(video("one"), () -> { }, null);
        // Existing users retain the old default and their explicit action, always physical right.
        assertEquals("legacy_delete", VideoSwipeActions.playlistAction(context, playlist, ItemTouchHelper.RIGHT));
        for (final String legacy : Arrays.asList("delete", "enqueue", "disable")) {
            prefs.edit().putString("local_playlist_swipe_action", legacy).commit();
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
            prefs.edit().putString("local_playlist_swipe_action", legacy).commit();
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
}
