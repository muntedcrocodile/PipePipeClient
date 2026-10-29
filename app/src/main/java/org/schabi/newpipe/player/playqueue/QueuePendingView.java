package org.schabi.newpipe.player.playqueue;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.schabi.newpipe.R;
import io.reactivex.rxjava3.disposables.Disposable;

/** An unfinished remote source is actionable even when there are no loaded rows to scroll. */
public final class QueuePendingView extends LinearLayout {
    private final TextView message;
    private final Button load;
    private final Disposable updates;

    public QueuePendingView(final Context context, final PersistentPlayQueue queue) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setVisibility(queue.isEmpty() && !queue.isComplete() ? View.VISIBLE : View.GONE);
        message = new TextView(context);
        message.setGravity(Gravity.CENTER);
        load = new Button(context);
        addView(message);
        addView(load);
        load.setOnClickListener(view -> {
            load.setEnabled(false);
            message.setText(R.string.queue_view_pending_loading);
            try {
                queue.retryPending();
            } catch (final RuntimeException error) {
                load.setEnabled(true);
                message.setText(context.getString(R.string.queue_view_load_failed, error.getMessage()));
            }
        });
        updates = queue.getBroadcastReceiver().subscribe(event -> {
            setVisibility(queue.isEmpty() && !queue.isComplete() ? View.VISIBLE : View.GONE);
            final Throwable failure = queue.getPendingError();
            message.setText(failure == null ? context.getString(R.string.queue_view_pending)
                    : context.getString(R.string.queue_view_load_failed, failure.getMessage()));
            load.setText(failure == null ? R.string.queue_view_load : R.string.queue_view_retry);
            load.setEnabled(true);
        });
    }

    public void dispose() {
        updates.dispose();
    }
}
