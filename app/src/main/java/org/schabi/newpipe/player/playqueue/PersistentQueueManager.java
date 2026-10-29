package org.schabi.newpipe.player.playqueue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.NewPipeDatabase;

/** Process-wide owner. Playback readiness/player mode belong to the player, never to storage. */
public final class PersistentQueueManager {
    public static final String ENABLED_KEY = "persistent_queue_enabled";
    private static volatile PersistentQueueManager instance;
    private final Context context;
    private PersistentPlayQueue queue;
    private boolean importing;

    private PersistentQueueManager(final Context context) {
        this.context = context.getApplicationContext();
    }

    public static PersistentQueueManager getInstance(final Context context) {
        PersistentQueueManager result = instance;
        if (result == null) {
            synchronized (PersistentQueueManager.class) {
                result = instance;
                if (result == null) {
                    result = new PersistentQueueManager(context);
                    instance = result;
                }
            }
        }
        return result;
    }

    public static boolean isEnabled(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(ENABLED_KEY, false);
    }

    public synchronized PersistentPlayQueue getQueue() {
        checkWritable();
        if (queue == null) {
            queue = new PersistentPlayQueue(new PersistentQueueStore(
                    NewPipeDatabase.getInstance(context).getOpenHelper().getWritableDatabase()));
        }
        return queue;
    }

    public synchronized PersistentPlayQueue playNow(final PlayQueue incoming) {
        final PersistentPlayQueue live = getQueue();
        live.insert(incoming, true, false);
        return live;
    }

    public synchronized PersistentPlayQueue enqueue(final PlayQueue incoming, final boolean next) {
        final PersistentPlayQueue live = getQueue();
        live.insert(incoming, false, next);
        return live;
    }

    public synchronized void clear() {
        getQueue().clear();
    }

    /** Caller stops the current player before either transition; restoration never starts audio. */
    public synchronized void setEnabled(final boolean enabled) {
        checkWritable();
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.edit().putBoolean(ENABLED_KEY, enabled).commit()) {
            throw new IllegalStateException("Could not save persistent queue setting");
        }
    }

    /**
     * After overwrite confirmation, call BEFORE stopping playback/closing the database.
     * Waits for any active durable commit and rejects subsequent old-owner writes and fetches.
     * Deliberately irreversible for this process: import restarts the app.
     */
    public synchronized void prepareForImport() {
        importing = true;
        if (queue != null) {
            queue.freeze();
        }
    }

    /** Stop playback first. Intended for an explicit database reopen outside import. */
    public synchronized void reloadAfterImport() {
        checkWritable();
        if (queue != null) {
            queue.freeze();
            queue.dispose();
            queue = null;
        }
    }

    private void checkWritable() {
        if (importing) {
            throw new IllegalStateException("Database replacement in progress; restart required");
        }
    }
}
