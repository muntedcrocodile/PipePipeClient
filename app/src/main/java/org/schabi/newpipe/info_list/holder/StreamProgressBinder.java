package org.schabi.newpipe.info_list.holder;

import android.view.View;

import org.schabi.newpipe.database.stream.model.StreamStateEntity;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.ktx.ViewUtils;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.views.AnimatedProgressBar;

import java.util.concurrent.TimeUnit;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** A history query must never hold RecyclerView layout waiting for a database writer. */
final class StreamProgressBinder {
    private final AnimatedProgressBar progress;
    private final SerialDisposable request = new SerialDisposable();
    private long generation;
    private StreamInfoItem boundItem;
    private HistoryRecordManager boundHistory;

    StreamProgressBinder(final AnimatedProgressBar progress) {
        this.progress = progress;
        progress.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(final View view) {
                // RecyclerView can reattach a cached holder without binding it again.
                if (boundItem != null && boundHistory != null && request.get() == null) {
                    load(boundItem, boundHistory, false);
                }
            }

            @Override
            public void onViewDetachedFromWindow(final View view) {
                cancel();
            }
        });
    }

    void load(final StreamInfoItem item, final HistoryRecordManager history,
              final boolean animate) {
        final long expectedGeneration = ++generation;
        request.set(null);
        if (!animate || boundItem != item) {
            progress.clearAnimation();
            progress.setVisibility(View.GONE);
        }
        boundItem = item;
        boundHistory = history;
        if (item.getDuration() <= 0 || item.getStreamType() == StreamType.LIVE_STREAM
                || item.getStreamType() == StreamType.AUDIO_LIVE_STREAM) {
            progress.setVisibility(View.GONE);
            return;
        }
        request.set(history.loadStreamState(item)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(states -> {
                    if (generation == expectedGeneration) {
                        render(item, states.length == 0 ? null : states[0], animate);
                    }
                }, error -> {
                    if (generation == expectedGeneration) {
                        progress.setVisibility(View.GONE);
                    }
                }));
    }

    void recycle() {
        cancel();
        boundItem = null;
        boundHistory = null;
    }

    private void cancel() {
        generation++;
        request.set(null);
        progress.clearAnimation();
        progress.setVisibility(View.GONE);
    }

    private void render(final StreamInfoItem item, final StreamStateEntity state,
                        final boolean animate) {
        if (state == null) {
            if (animate && progress.getVisibility() == View.VISIBLE) {
                ViewUtils.animate(progress, false, 500);
            } else {
                progress.setVisibility(View.GONE);
            }
            return;
        }
        progress.setMax((int) item.getDuration());
        final int seconds = (int) TimeUnit.MILLISECONDS.toSeconds(state.getProgressMillis());
        if (animate && progress.getVisibility() == View.VISIBLE) {
            progress.setProgressAnimated(seconds);
        } else {
            progress.setProgress(seconds);
            if (animate) ViewUtils.animate(progress, true, 500);
            else progress.setVisibility(View.VISIBLE);
        }
    }
}
