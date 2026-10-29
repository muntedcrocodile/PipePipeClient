package org.schabi.newpipe.player.playqueue;

import android.database.Cursor;

import androidx.sqlite.db.SupportSQLiteDatabase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/** One transactional record in the backed-up app database; never a competing playlist writer. */
public final class PersistentQueueStore {
    private final SupportSQLiteDatabase database;

    public PersistentQueueStore(final SupportSQLiteDatabase database) {
        this.database = database;
    }

    public static void createTable(final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS persistent_queue_state "
                + "(id INTEGER PRIMARY KEY CHECK(id=1), state BLOB NOT NULL)");
    }

    PersistentPlayQueue.State load() {
        try (Cursor cursor = database.query(
                "SELECT state FROM persistent_queue_state WHERE id=1")) {
            if (!cursor.moveToFirst()) {
                return new PersistentPlayQueue.State();
            }
            try (ObjectInputStream input = new ObjectInputStream(
                    new ByteArrayInputStream(cursor.getBlob(0)))) {
                final PersistentPlayQueue.State state = (PersistentPlayQueue.State) input.readObject();
                if (state.version != 1) {
                    throw new IOException("Unsupported persistent queue version " + state.version);
                }
                return state;
            }
        } catch (final IOException | ClassNotFoundException e) {
            // Never silently replace a queue that could not be read.
            throw new IllegalStateException("Cannot restore persistent queue", e);
        }
    }

    void save(final PersistentPlayQueue.State state) {
        final byte[] bytes;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             ObjectOutputStream output = new ObjectOutputStream(buffer)) {
            output.writeObject(state);
            output.flush();
            bytes = buffer.toByteArray();
        } catch (final IOException e) {
            throw new IllegalStateException("Cannot serialize persistent queue", e);
        }
        database.beginTransaction();
        try {
            database.execSQL("INSERT OR REPLACE INTO persistent_queue_state(id,state) VALUES(1,?)",
                    new Object[]{bytes});
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }
}
