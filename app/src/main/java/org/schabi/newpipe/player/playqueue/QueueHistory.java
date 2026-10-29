package org.schabi.newpipe.player.playqueue;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import org.schabi.newpipe.R;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.subjects.BehaviorSubject;

/** Shared recent global history; navigation snapshots remain the player's responsibility. */
public final class QueueHistory {
    private static QueueHistory instance;
    private final SharedPreferences preferences;
    private final String enabledKey;
    private io.reactivex.rxjava3.disposables.Disposable historyChanges;
    private List<PlayerMediaItem> recent = Collections.emptyList();
    private final BehaviorSubject<List<PlayerMediaItem>> items =
            BehaviorSubject.createDefault(Collections.emptyList());
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (prefs, key) -> publish();

    public static synchronized QueueHistory getInstance(final Context context) {
        if (instance == null) {
            instance = new QueueHistory(context.getApplicationContext());
        }
        return instance;
    }

    private QueueHistory(final Context context) {
        this(context, new HistoryRecordManager(context).getStreamHistory());
    }

    QueueHistory(final Context context,
                 final io.reactivex.rxjava3.core.Flowable<java.util.List<
                         org.schabi.newpipe.database.history.model.StreamHistoryEntry>> history) {
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        enabledKey = context.getString(R.string.enable_watch_history_key);
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
        historyChanges = history.observeOn(AndroidSchedulers.mainThread())
                .subscribe(entries -> {
                    final List<PlayerMediaItem> result = new ArrayList<>();
                    final java.util.Set<String> seen = new java.util.HashSet<>();
                    for (final var entry : entries) {
                        final var stream = entry.getStreamEntity();
                        if (seen.add(stream.getServiceId() + ":" + stream.getUrl())) {
                            result.add(PlayerMediaItem.forStreamInfoItem(entry.toStreamInfoItem()));
                        }
                    }
                    recent = result;
                    publish();
                }, error -> {
                    recent = Collections.emptyList();
                    publish();
                });
    }

    void close() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        historyChanges.dispose();
    }

    private synchronized void publish() {
        int depth = 20;
        try {
            depth = Math.max(0, Integer.parseInt(preferences.getString(
                    "persistent_queue_history_depth", "20")));
        } catch (final IllegalArgumentException | ClassCastException ignored) {
            // A malformed imported preference uses the default.
        }
        final List<PlayerMediaItem> result = preferences.getBoolean(enabledKey, true)
                ? new ArrayList<>(recent.subList(0, Math.min(depth, recent.size())))
                : Collections.emptyList();
        items.onNext(Collections.unmodifiableList(result));
    }

    public Observable<List<PlayerMediaItem>> observe() {
        return items.hide().observeOn(AndroidSchedulers.mainThread());
    }

    public List<PlayerMediaItem> getItems() {
        return items.getValue();
    }

    public List<PlayerMediaItem> excluding(final PlayerMediaItem current) {
        final List<PlayerMediaItem> result = new ArrayList<>(getItems());
        if (current != null) {
            result.removeIf(item -> item.getServiceId() == current.getServiceId()
                    && item.getUrl().equals(current.getUrl()));
        }
        return result;
    }
}
