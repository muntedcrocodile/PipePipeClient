package org.schabi.newpipe.player.playqueue;

import android.app.ProgressDialog;
import android.content.Context;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import org.schabi.newpipe.database.stream.model.StreamEntity;
import org.schabi.newpipe.local.dialog.PlaylistDialog;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Existing Android dialogs around durable owner commands. */
public final class QueueViewActions {
    private QueueViewActions() { }

    public static void edit(final Context context, final Runnable command) {
        try {
            command.run();
            Toast.makeText(context, "Queue updated", Toast.LENGTH_SHORT).show();
        } catch (final RuntimeException error) {
            Toast.makeText(context, "Queue update failed: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    public static void clear(final Context context) {
        new AlertDialog.Builder(context).setTitle("Clear queue?")
                .setMessage("Remove all unfinished videos and stop playback?")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> edit(context,
                        () -> PersistentQueueManager.getInstance(context).clear())).show();
    }

    public static Disposable materialize(final Context context, final FragmentManager fragments,
                                         final PersistentPlayQueue queue, final boolean shuffle) {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ProgressDialog progress = new ProgressDialog(context);
        progress.setMessage(shuffle ? "Loading queue to shuffle upcoming videos…"
                : "Loading queue to save as playlist…");
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
                        Toast.makeText(context, "Queue updated", Toast.LENGTH_SHORT).show();
                    } else {
                        PlaylistDialog.createCorrespondingDialog(context, streams,
                                dialog -> dialog.show(fragments, "save_queue"));
                    }
                }, error -> {
                    if (!cancelled.get()) {
                        Toast.makeText(context, "Could not load queue: " + error.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }
}
