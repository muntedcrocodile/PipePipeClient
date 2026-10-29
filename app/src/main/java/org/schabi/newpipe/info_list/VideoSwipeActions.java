package org.schabi.newpipe.info_list;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.info_list.dialog.StreamDialogDefaultEntry;
import org.schabi.newpipe.player.playqueue.SinglePlayQueue;
import org.schabi.newpipe.util.NavigationHelper;

/** Source-row actions only. Player queue occurrences have their own removal gestures. */
public final class VideoSwipeActions {
    private VideoSwipeActions() { }

    /** Resolve the currently displayed row, accounting for filters, headers and layout. */
    public interface RowResolver {
        @Nullable Row resolve(int adapterPosition);
    }

    public static final class Row {
        public final StreamInfoItem item;
        @Nullable public final Runnable removePlaylist;
        @Nullable public final Runnable deleteHistory;

        public Row(final StreamInfoItem item) {
            this(item, null, null);
        }

        public Row(final StreamInfoItem item, @Nullable final Runnable removePlaylist,
                   @Nullable final Runnable deleteHistory) {
            this.item = item;
            this.removePlaylist = removePlaylist;
            this.deleteHistory = deleteHistory;
        }
    }

    public static boolean isEnabled(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.video_swipe_enabled_key), false);
    }

    /** Legacy playlist swipes keep their right-only, non-tab behavior until opt-in. */
    public static int playlistDirections(final Context context, final Row row, final boolean inTab) {
        if (isEnabled(context)) {
            return directions(context, row);
        }
        return inTab || "none".equals(playlistAction(context, row, ItemTouchHelper.RIGHT))
                ? 0 : ItemTouchHelper.RIGHT;
    }

    static String playlistAction(final Context context, final Row row, final int direction) {
        if (isEnabled(context)) {
            return action(context, direction, row);
        }
        if (direction != ItemTouchHelper.RIGHT) {
            return "none";
        }
        final String legacy = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.local_playlist_swipe_action_key), "delete");
        switch (legacy) {
            case "delete": return "legacy_delete";
            case "enqueue": return "enqueue";
            default: return "none";
        }
    }

    public static void executePlaylist(final Fragment fragment, final Row row,
                                       final int direction, final boolean inTab) {
        final Context context = fragment.requireContext();
        if ((playlistDirections(context, row, inTab) & direction) != 0) {
            executeAction(fragment, row, playlistAction(context, row, direction));
        }
    }

    public static int directions(final Context context, final Row row) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(context.getString(R.string.video_swipe_enabled_key), false)) {
            return 0;
        }
        int flags = 0;
        if (!"none".equals(action(context, ItemTouchHelper.LEFT, row))) {
            flags |= ItemTouchHelper.LEFT;
        }
        if (!"none".equals(action(context, ItemTouchHelper.RIGHT, row))) {
            flags |= ItemTouchHelper.RIGHT;
        }
        return flags;
    }

    private static String action(final Context context, final int direction, final Row row) {
        final boolean left = direction == ItemTouchHelper.LEFT;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String key = context.getString(left ? R.string.video_swipe_left_action_key
                : R.string.video_swipe_right_action_key);
        final String scope = row.removePlaylist != null ? "_playlist"
                : row.deleteHistory != null ? "_history" : "";
        final String override = scope.isEmpty() ? "inherit"
                : prefs.getString(key + scope, "inherit");
        return "inherit".equals(override)
                ? prefs.getString(key, left ? "enqueue_next" : "enqueue") : override;
    }

    public static void attach(final Fragment fragment, final RecyclerView list,
                              final RowResolver resolver) {
        protectTabGesture(fragment, list, resolver);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            private Row swipingRow;

            @Override
            public void onSelectedChanged(@Nullable final RecyclerView.ViewHolder holder,
                                          final int state) {
                if (holder != null && state == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    swipingRow = resolver.resolve(holder.getBindingAdapterPosition());
                }
                super.onSelectedChanged(holder, state);
            }

            @Override
            public boolean onMove(@NonNull final RecyclerView recyclerView,
                                  @NonNull final RecyclerView.ViewHolder source,
                                  @NonNull final RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public int getSwipeDirs(@NonNull final RecyclerView recyclerView,
                                    @NonNull final RecyclerView.ViewHolder holder) {
                final Row row = resolver.resolve(holder.getBindingAdapterPosition());
                return fragment.getContext() != null && row != null
                        ? directions(fragment.requireContext(), row) : 0;
            }

            @Override
            public void onSwiped(@NonNull final RecyclerView.ViewHolder holder,
                                 final int direction) {
                final int position = holder.getBindingAdapterPosition();
                final Row row = swipingRow != null ? swipingRow : resolver.resolve(position);
                swipingRow = null;
                // Restore before launching dialogs/actions: cancellation never removes a source row.
                if (position != RecyclerView.NO_POSITION && list.getAdapter() != null) {
                    list.getAdapter().notifyItemChanged(position);
                }
                if (row != null && fragment.isAdded()) {
                    execute(fragment, row, direction);
                }
            }
        }).attachToRecyclerView(list);
    }

    /** Keep horizontal row gestures available inside ViewPager tabs, preserving vertical scroll. */
    public static void protectTabGesture(final Fragment fragment, final RecyclerView list,
                                         final RowResolver resolver) {
        list.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            private float startX;
            private float startY;
            private int allowed;
            private final int slop = ViewConfiguration.get(list.getContext()).getScaledTouchSlop();

            @Override
            public boolean onInterceptTouchEvent(@NonNull final RecyclerView recyclerView,
                                                 @NonNull final MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getX();
                    startY = event.getY();
                    final View child = list.findChildViewUnder(startX, startY);
                    final Row row = child == null ? null
                            : resolver.resolve(list.getChildAdapterPosition(child));
                    allowed = row == null || fragment.getContext() == null ? 0
                            : directions(fragment.requireContext(), row);
                    if (allowed != 0) {
                        list.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && allowed != 0) {
                    final float dx = event.getX() - startX;
                    final float dy = event.getY() - startY;
                    if (Math.max(Math.abs(dx), Math.abs(dy)) > slop
                            && (Math.abs(dy) > Math.abs(dx)
                            || (allowed & (dx < 0 ? ItemTouchHelper.LEFT : ItemTouchHelper.RIGHT)) == 0)) {
                        list.getParent().requestDisallowInterceptTouchEvent(false);
                        allowed = 0;
                    }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                        || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    list.getParent().requestDisallowInterceptTouchEvent(false);
                    allowed = 0;
                }
                return false;
            }
        });
    }

    public static void execute(final Fragment fragment, final Row row, final int direction) {
        final Context context = fragment.requireContext();
        if ((directions(context, row) & direction) == 0) {
            return;
        }
        executeAction(fragment, row, action(context, direction, row));
    }

    private static void executeAction(final Fragment fragment, final Row row, final String action) {
        final Context context = fragment.requireContext();
        if ("legacy_delete".equals(action)) {
            if (row.removePlaylist == null) {
                unavailable(context);
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(R.string.play_queue_remove)
                    .setMessage(R.string.remove_from_playlist_confirmation)
                    .setPositiveButton(R.string.play_queue_remove,
                            (dialog, which) -> row.removePlaylist.run())
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        // Do not prefetch sparse metadata here: submission order must equal gesture order.
        // NavigationHelper and the service own hydration, durability and success feedback.
        if ("enqueue".equals(action)) {
            NavigationHelper.enqueueOnPlayer(context, new SinglePlayQueue(row.item));
            return;
        }
        if ("enqueue_next".equals(action)) {
            NavigationHelper.enqueueNextOnPlayer(context, new SinglePlayQueue(row.item));
            return;
        }
        if ("remove_playlist".equals(action) || "delete_history".equals(action)) {
            final boolean playlist = "remove_playlist".equals(action);
            final Runnable remove = playlist ? row.removePlaylist : row.deleteHistory;
            if (remove == null) {
                unavailable(context);
                return;
            }
            new AlertDialog.Builder(context)
                    .setTitle(playlist ? R.string.video_swipe_remove_playlist
                            : R.string.video_swipe_delete_history)
                    .setMessage(playlist ? R.string.remove_from_playlist_confirmation
                            : R.string.video_swipe_delete_history_confirm)
                    .setPositiveButton(R.string.delete, (dialog, which) -> remove.run())
                    .setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
        final StreamDialogDefaultEntry entry;
        switch (action) {
            case "none": return;
            case "background": entry = StreamDialogDefaultEntry.START_HERE_ON_BACKGROUND; break;
            case "popup": entry = StreamDialogDefaultEntry.START_HERE_ON_POPUP; break;
            case "playlist": entry = StreamDialogDefaultEntry.APPEND_PLAYLIST; break;
            case "download": entry = StreamDialogDefaultEntry.DOWNLOAD; break;
            case "share": entry = StreamDialogDefaultEntry.SHARE; break;
            case "browser": entry = StreamDialogDefaultEntry.OPEN_IN_BROWSER; break;
            case "watched":
                new org.schabi.newpipe.local.history.HistoryRecordManager(context)
                        .markAsWatched(row.item)
                        .observeOn(io.reactivex.rxjava3.android.schedulers.AndroidSchedulers.mainThread())
                        .subscribe(streamId -> Toast.makeText(context, R.string.mark_as_watched,
                                        Toast.LENGTH_SHORT).show(),
                                error -> org.schabi.newpipe.error.ErrorUtil.createNotification(context,
                                        new org.schabi.newpipe.error.ErrorInfo(error,
                                                org.schabi.newpipe.error.UserAction.REQUESTED_STREAM,
                                                "Marking video watched", row.item.getServiceId())),
                                () -> unavailable(context));
                return;
            case "channel":
                org.schabi.newpipe.util.SparseItemUtil.fetchUploaderUrlIfSparse(context,
                        row.item.getServiceId(), row.item.getUrl(), row.item.getUploaderUrl(), url -> {
                            if (url == null || url.isEmpty()) {
                                unavailable(context);
                            } else if (fragment.isAdded()) {
                                NavigationHelper.openChannelFragment(fragment, row.item, url);
                            }
                        });
                return;
            case "filter_channel":
                if (row.item.getUploaderName() == null || row.item.getUploaderName().isEmpty()) {
                    unavailable(context);
                    return;
                }
                entry = StreamDialogDefaultEntry.ADD_TO_FILTER_LIST;
                break;
            default: unavailable(context); return;
        }
        entry.action.onClick(fragment, row.item);
    }

    private static void unavailable(final Context context) {
        Toast.makeText(context, R.string.video_swipe_unavailable, Toast.LENGTH_LONG).show();
    }
}
