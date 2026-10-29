package org.schabi.newpipe.player.playqueue;

import android.app.ProgressDialog;
import org.schabi.newpipe.R;
import android.content.Context;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.local.dialog.PlaylistCreationDialog;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Existing Android dialogs around durable owner commands. */
public final class QueueViewActions {
    private QueueViewActions() { }

    public static PlayQueueItemBuilder.OnSelectedListener historyListener(
            final org.schabi.newpipe.player.Player player) {
        return new PlayQueueItemBuilder.OnSelectedListener() {
            @Override
            public void selected(final org.schabi.newpipe.player.mediaitem.PlayerMediaItem item,
                                 final android.view.View view) {
                player.getPersistentQueueController().replayHistory(item);
            }
            @Override
            public void held(final org.schabi.newpipe.player.mediaitem.PlayerMediaItem item,
                             final android.view.View view) { }
            @Override
            public void onStartDrag(final PlayQueueItemHolder holder) { }
        };
    }

    public static void edit(final Context context, final Runnable command) {
        edit(context, command, R.string.queue_view_updated);
    }

    public static void remove(final Context context, final PlayQueue queue, final int index) {
        if (index < 0 || index >= queue.size()) {
            return;
        }
        final var occurrence = queue.getItem(index);
        edit(context, () -> queue.remove(queue.indexOf(occurrence)), R.string.queue_view_removed);
    }

    private static void edit(final Context context, final Runnable command, final int success) {
        try {
            command.run();
            Toast.makeText(context, success, Toast.LENGTH_SHORT).show();
        } catch (final RuntimeException error) {
            Toast.makeText(context, context.getString(R.string.queue_view_update_failed,
                    error.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    public static void clear(final Context context,
                             final org.schabi.newpipe.player.Player player) {
        new AlertDialog.Builder(context).setTitle(R.string.queue_view_clear_title)
                .setMessage(R.string.queue_view_clear_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> edit(context, () -> {
                    PersistentQueueManager.getInstance(context).clear();
                    if (player != null && !player.exoPlayerIsNull()) {
                        player.smoothStopPlayer();
                    }
                    org.schabi.newpipe.player.helper.PlayerHolder.getInstance().stopService();
                })).show();
    }

    public static Disposable materialize(final Context context, final FragmentManager fragments,
                                         final PersistentPlayQueue queue, final boolean shuffle) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ProgressDialog progress = new ProgressDialog(context);
        progress.setMessage(context.getString(shuffle ? R.string.queue_view_loading_shuffle
                : R.string.queue_view_loading_save));
        progress.setIndeterminate(true);
        progress.setCancelable(true);
        progress.setOnCancelListener(dialog -> cancelled.set(true));
        progress.show();
        return Single.fromCallable(() -> {
            if (shuffle) {
                queue.shuffleUpcoming(cancelled::get);
                return java.util.Collections.<StreamEntity>emptyList();
            }
            return queue.materialize(cancelled::get).stream()
                    .map(StreamEntity::new).collect(Collectors.toList());
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .doFinally(() -> {
                    cancelled.set(true);
                    progress.dismiss();
                }).subscribe(streams -> {
                    if (cancelled.get()) {
                        return;
                    }
                    if (shuffle) {
                        Toast.makeText(context, R.string.queue_view_updated, Toast.LENGTH_SHORT).show();
                    } else {
                        if (!streams.isEmpty()) {
                            PlaylistCreationDialog.newInstance(streams).show(fragments, "save_queue");
                        }
                    }
                }, error -> {
                    if (!cancelled.get()) {
                        Toast.makeText(context, context.getString(R.string.queue_view_load_failed,
                                error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }
}
