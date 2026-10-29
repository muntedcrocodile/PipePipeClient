package org.schabi.newpipe.info_list;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.R;
import org.schabi.newpipe.about.AboutActivity;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.LocalItem;
import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.local.LocalItemListAdapter;
import org.schabi.newpipe.local.playlist.LocalPlaylistFragment;
import org.schabi.newpipe.local.playlist.LocalPlaylistManager;
import org.schabi.newpipe.player.PlayerIntentConstants;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.util.SerializedCache;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Tests swipe settings and production callback sequencing without starting a player service. */
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
    public void newControlsDefaultOnAndExplicitOptOutAndNoneHaveNoFallback() {
        final VideoSwipeActions.Row playlist = new VideoSwipeActions.Row(video("one"), () -> { }, null);
        prefs.edit().putString("local_playlist_swipe_action", "disable").commit();
        assertEquals("enqueue_next", VideoSwipeActions.playlistAction(
                context, playlist, ItemTouchHelper.LEFT));
        assertEquals("enqueue", VideoSwipeActions.playlistAction(
                context, playlist, ItemTouchHelper.RIGHT));
        assertEquals(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                VideoSwipeActions.playlistDirections(context, playlist, true));
        assertEquals(ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                VideoSwipeActions.directions(context, playlist));
        prefs.edit().putBoolean("video_swipe_enabled", false).commit();
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
    public void legacyPlaylistActionsApplyOnlyAfterExplicitOptOut() {
        final VideoSwipeActions.Row playlist = new VideoSwipeActions.Row(video("one"), () -> { }, null);
        prefs.edit().putBoolean("video_swipe_enabled", false).commit();
        // Explicit opt-outs retain legacy playlist behavior, always physical right.
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

    @Test
    public void sourceCallbacksKeepVideosAndPhysicalDirectionsDuringDeferredCompletion() {
        withActivity(activity -> onMain(() -> {
            final SwipeFixture fixture = new SwipeFixture(activity, false);
            assertDeferredDirections(fixture);
        }));
    }

    @Test
    public void playlistCallbacksKeepOccurrencesAndPhysicalDirectionsDuringDeferredCompletion() {
        withActivity(activity -> onMain(() -> {
            final SwipeFixture fixture = new SwipeFixture(activity, true);
            assertDeferredDirections(fixture);
        }));
    }

    private void assertDeferredDirections(final SwipeFixture fixture) {
        for (final int layoutDirection : Arrays.asList(View.LAYOUT_DIRECTION_LTR,
                View.LAYOUT_DIRECTION_RTL)) {
            fixture.list.setLayoutDirection(layoutDirection);
            fixture.context.submitted.clear();
            fixture.restored.clear();
            fixture.selectBoth();
            // A's recovery finishes after B was selected. Each callback must retain its own row.
            fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
            fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
            assertEquals(Arrays.asList("https://example.com/A:next",
                    "https://example.com/B:end"), fixture.context.submitted);
            assertEquals(Arrays.asList(0, 1), fixture.restored);
            assertEquals(2, fixture.list.getAdapter().getItemCount());
        }
    }

    @Test
    public void sourceCallbacksRetainCapturedRowsAndDiscardCancelledOrRecycledGestures() {
        withActivity(activity -> onMain(() -> assertGestureLifecycle(
                new SwipeFixture(activity, false))));
    }

    @Test
    public void playlistCallbacksRetainCapturedOccurrencesAndDiscardCancelledOrRecycledGestures() {
        withActivity(activity -> onMain(() -> assertGestureLifecycle(
                new SwipeFixture(activity, true))));
    }

    private void assertGestureLifecycle(final SwipeFixture fixture) {
        fixture.selectBoth();
        fixture.replaceFirst(video("C"));
        fixture.adapter.bindViewHolder(fixture.first, 0);
        // Rebinding must not redirect the pending action from A to the newly displayed C.
        fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
        fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
        assertEquals(Arrays.asList("https://example.com/A:next",
                "https://example.com/B:end"), fixture.context.submitted);

        fixture.context.submitted.clear();
        fixture.selectBoth();
        fixture.callback.clearView(fixture.list, fixture.first);
        fixture.replaceFirst(video("D"));
        fixture.adapter.bindViewHolder(fixture.first, 0);
        fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
        fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
        // A stale callback after cancellation/recycle cannot resolve and act on D.
        assertEquals(Arrays.asList("https://example.com/B:end"), fixture.context.submitted);
        fixture.callback.clearView(fixture.list, fixture.second);
        fixture.callback.onSwiped(fixture.second, ItemTouchHelper.RIGHT);
        assertEquals(1, fixture.context.submitted.size());

        fixture.callback.onSelectedChanged(fixture.first, ItemTouchHelper.ACTION_STATE_SWIPE);
        fixture.callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
        fixture.callback.onSwiped(fixture.first, ItemTouchHelper.LEFT);
        assertEquals(Arrays.asList("https://example.com/B:end",
                "https://example.com/D:next"), fixture.context.submitted);
        if (fixture.playlistFragment != null) {
            fixture.context.submitted.clear();
            fixture.callback.onSelectedChanged(fixture.first, ItemTouchHelper.ACTION_STATE_SWIPE);
            fixture.callback.onSelectedChanged(fixture.first, ItemTouchHelper.ACTION_STATE_DRAG);
            fixture.callback.onSwiped(fixture.first, ItemTouchHelper.RIGHT);
            assertEquals(0, fixture.context.submitted.size());
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    public void playlistConfirmationsKeepDeferredDuplicateOccurrencesAndCancellationRestoresRows() {
        prefs.edit().putString("video_swipe_left_action_playlist", "remove_playlist")
                .putString("video_swipe_right_action_playlist", "remove_playlist").commit();
        withActivity(activity -> {
            final SwipeFixture[] fixture = new SwipeFixture[1];
            final AppDatabase db = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).build();
            try {
                onMain(() -> {
                    fixture[0] = new SwipeFixture(activity, true, true);
                    final PlaylistFragment fragment = fixture[0].playlistFragment;
                    // Only the in-memory adapter is edited; no playlist writes are scheduled.
                    setPlaylistManager(fragment, new LocalPlaylistManager(db) {
                        @Override
                        public String getPlaylistThumbnail(final long playlistId) {
                            return "unused-thumbnail";
                        }
                    });
                    fixture[0].selectBoth();
                    fixture[0].callback.onSwiped(fixture[0].first, ItemTouchHelper.LEFT);
                    final View firstDialog = confirmationRoot();
                    fixture[0].callback.onSwiped(fixture[0].second, ItemTouchHelper.RIGHT);
                    final View secondDialog = confirmationRoot();
                    assertEquals(false, firstDialog == secondDialog);
                    assertEquals(2, fragment.itemCount());
                    firstDialog.findViewById(android.R.id.button1).performClick();
                    secondDialog.findViewById(android.R.id.button2).performClick();
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                onMain(() -> {
                    final SwipeFixture current = fixture[0];
                    assertEquals(1, current.playlistFragment.itemCount());
                    assertSame(current.secondEntry, current.playlistFragment.firstItem());
                    assertEquals(Arrays.asList(0, 1), current.restored.subList(0, 2));
                    assertEquals(0, current.context.submitted.size());
                });
            } finally {
                db.close();
            }
        });
    }

    private static void onMain(final Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private void withActivity(final Consumer<FragmentActivity> test) {
        final Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, AboutActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.accept((FragmentActivity) activity);
        } finally {
            onMain(activity::finish);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
    }

    @androidx.annotation.RequiresApi(29)
    private View confirmationRoot() {
        final List<View> roots = WindowInspector.getGlobalWindowViews();
        for (int i = roots.size() - 1; i >= 0; i--) {
            final View root = roots.get(i);
            final TextView message = root.findViewById(android.R.id.message);
            if (message != null && message.getContext()
                    .getString(R.string.remove_from_playlist_confirmation)
                    .contentEquals(message.getText())) {
                assertNotNull(root.findViewById(android.R.id.button1));
                assertNotNull(root.findViewById(android.R.id.button2));
                return root;
            }
        }
        throw new AssertionError("Missing playlist removal confirmation");
    }

    private static void setPlaylistManager(final PlaylistFragment fragment,
                                           final LocalPlaylistManager manager) {
        try {
            final Field field = LocalPlaylistFragment.class.getDeclaredField("playlistManager");
            field.setAccessible(true);
            field.set(fragment, manager);
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    /** Captures production queue intents at the service boundary without starting a player. */
    private static final class RecordingContext extends ContextWrapper {
        private final List<String> submitted = new ArrayList<>();

        RecordingContext(final Context base) {
            super(base);
        }

        @Override
        public ComponentName startService(final Intent intent) {
            final PlayQueue queue = SerializedCache.getInstance().take(
                    intent.getStringExtra(PlayerIntentConstants.PLAY_QUEUE_KEY), PlayQueue.class);
            assertNotNull(queue);
            submitted.add(queue.getItem().getUrl() + ":"
                    + (intent.getBooleanExtra(PlayerIntentConstants.ENQUEUE_NEXT, false)
                    ? "next" : "end"));
            return intent.getComponent();
        }

        @Override
        public ComponentName startForegroundService(final Intent intent) {
            return startService(intent);
        }
    }

    private static RecyclerView.ViewHolder plainHolder(final ViewGroup parent) {
        final View view = new View(parent.getContext());
        view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80));
        return new RecyclerView.ViewHolder(view) { };
    }

    /** Uses the real local adapter's filtering/position logic with inexpensive row views. */
    private static final class PlainLocalAdapter extends LocalItemListAdapter {
        PlainLocalAdapter(final Context context) {
            super(context);
            setItemViewMode(ItemViewMode.LIST);
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent, final int type) {
            return plainHolder(parent);
        }

        @Override
        public void onBindViewHolder(final RecyclerView.ViewHolder holder, final int position) { }
    }

    private static final class PlaylistFragment extends LocalPlaylistFragment {
        private final Context context;

        PlaylistFragment(final Context context, final LocalItemListAdapter adapter) {
            this.context = context;
            itemListAdapter = adapter;
            playlistId = 1L;
        }

        @Override
        public Context getContext() {
            return context;
        }

        int itemCount() {
            return itemListAdapter.getItemsList().size();
        }

        LocalItem firstItem() {
            return itemListAdapter.getItemsList().get(0);
        }

        ItemTouchHelper.SimpleCallback callback() {
            try {
                final Method method = LocalPlaylistFragment.class
                        .getDeclaredMethod("getItemTouchCallback");
                method.setAccessible(true);
                return (ItemTouchHelper.SimpleCallback) method.invoke(this);
            } catch (final ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }
    }

    public static class SourceFragment extends Fragment {
        private Context swipeContext;

        @Override
        public Context getContext() {
            return swipeContext == null ? super.getContext() : swipeContext;
        }
    }

    private final class SwipeFixture {
        private final RecordingContext context;
        private final RecyclerView list;
        private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
        private final ItemTouchHelper.SimpleCallback callback;
        private final RecyclerView.ViewHolder first;
        private final RecyclerView.ViewHolder second;
        private final List<Integer> restored = new ArrayList<>();
        private final List<VideoSwipeActions.Row> rows = new ArrayList<>();
        private final PlaylistFragment playlistFragment;
        private final PlaylistStreamEntry firstEntry;
        private final PlaylistStreamEntry secondEntry;

        SwipeFixture(final FragmentActivity activity, final boolean playlist) {
            this(activity, playlist, false);
        }

        SwipeFixture(final FragmentActivity activity, final boolean playlist,
                     final boolean duplicateVideo) {
            context = new RecordingContext(activity);
            list = new RecyclerView(context);
            list.setLayoutManager(new LinearLayoutManager(context));
            firstEntry = new PlaylistStreamEntry(new StreamEntity(video("A")), 0, 1, 0);
            secondEntry = new PlaylistStreamEntry(
                    new StreamEntity(video(duplicateVideo ? "A" : "B")), 0, 2, 1);
            if (playlist) {
                final PlainLocalAdapter localAdapter = new PlainLocalAdapter(context);
                localAdapter.addItems(Arrays.asList(firstEntry, secondEntry));
                adapter = localAdapter;
                playlistFragment = new PlaylistFragment(context, localAdapter);
                callback = playlistFragment.callback();
            } else {
                playlistFragment = null;
                rows.add(new VideoSwipeActions.Row(video("A")));
                rows.add(new VideoSwipeActions.Row(video("B")));
                final SourceFragment fragment = new SourceFragment();
                fragment.swipeContext = context;
                activity.getSupportFragmentManager().beginTransaction().add(fragment, null).commitNow();
                adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(final ViewGroup parent,
                                                                     final int type) {
                        return plainHolder(parent);
                    }

                    @Override
                    public void onBindViewHolder(final RecyclerView.ViewHolder holder,
                                                 final int position) { }

                    @Override
                    public int getItemCount() {
                        return rows.size();
                    }
                };
                callback = VideoSwipeActions.createCallback(fragment, list,
                        position -> position >= 0 && position < rows.size() ? rows.get(position) : null);
            }
            list.setAdapter(adapter);
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override
                public void onItemRangeChanged(final int position, final int count,
                                               final Object payload) {
                    restored.add(position);
                }
            });
            list.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY));
            list.layout(0, 0, 800, 1200);
            first = list.findViewHolderForAdapterPosition(0);
            second = list.findViewHolderForAdapterPosition(1);
            assertNotNull(first);
            assertNotNull(second);
        }

        void selectBoth() {
            callback.onSelectedChanged(first, ItemTouchHelper.ACTION_STATE_SWIPE);
            callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
            callback.onSelectedChanged(second, ItemTouchHelper.ACTION_STATE_SWIPE);
            callback.onSelectedChanged(null, ItemTouchHelper.ACTION_STATE_IDLE);
        }

        void replaceFirst(final StreamInfoItem video) {
            if (playlistFragment == null) {
                rows.set(0, new VideoSwipeActions.Row(video));
            } else {
                final LocalItemListAdapter adapter = (LocalItemListAdapter) list.getAdapter();
                adapter.getItemsList().set(0,
                        new PlaylistStreamEntry(new StreamEntity(video), 0, 3, 0));
            }
        }
    }
}
