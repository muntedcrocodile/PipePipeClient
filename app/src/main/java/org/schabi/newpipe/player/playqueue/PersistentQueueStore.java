package org.schabi.newpipe.player.playqueue;

import android.database.Cursor;
import android.util.Base64;

import androidx.sqlite.db.SupportSQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * One transactional record in the backed-up app database. The versioned JSON wire format
 * deliberately excludes Java class descriptors, Kotlin implementation details and resolved
 * extractor objects: upgrades/obfuscation cannot change the persisted occurrence schema.
 */
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
            try {
                return decode(new JSONObject(new String(cursor.getBlob(0), StandardCharsets.UTF_8)));
            } catch (final JSONException | IllegalArgumentException e) {
                // Never silently replace a queue that could not be read.
                throw new IllegalStateException("Cannot restore persistent queue", e);
            }
        }
    }

    void save(final PersistentPlayQueue.State state) {
        final byte[] bytes;
        try {
            bytes = encode(state).toString().getBytes(StandardCharsets.UTF_8);
        } catch (final JSONException e) {
            throw new IllegalStateException("Cannot encode persistent queue", e);
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

    private static JSONObject encode(final PersistentPlayQueue.State state) throws JSONException {
        final JSONArray nodes = new JSONArray();
        for (final PersistentPlayQueue.Node node : state.nodes) {
            final JSONObject value = new JSONObject();
            if (node.item != null) {
                final PlayerMediaItem item = node.item;
                value.put("uuid", item.getUuid()).put("service", item.getServiceId())
                        .put("url", item.getUrl()).put("title", item.getTitle())
                        .put("uploader", item.getUploader()).put("uploaderUrl", item.getUploaderUrl())
                        .put("duration", item.getDuration()).put("thumbnail", item.getThumbnailUrl())
                        .put("type", item.getStreamType().name())
                        .put("round", item.isRoundPlayStream()).put("start", item.getStartAt());
            } else {
                value.put("playlist", node.playlist).put("service", node.serviceId)
                        .put("url", node.url).put("initial", node.initial);
                if (node.page != null) {
                    final JSONObject page = new JSONObject().put("url", node.page.getUrl())
                            .put("id", node.page.getId());
                    if (node.page.getIds() != null) {
                        page.put("ids", new JSONArray(node.page.getIds()));
                    }
                    if (node.page.getCookies() != null) {
                        page.put("cookies", new JSONObject(node.page.getCookies()));
                    }
                    if (node.page.getBody() != null) {
                        page.put("body", Base64.encodeToString(node.page.getBody(), Base64.NO_WRAP));
                    }
                    value.put("page", page);
                }
            }
            nodes.put(value);
        }
        return new JSONObject().put("version", state.version).put("nodes", nodes)
                .put("progress", new JSONObject(state.progress));
    }

    private static PersistentPlayQueue.State decode(final JSONObject data) throws JSONException {
        if (data.getInt("version") != 1) {
            throw new JSONException("Unsupported persistent queue version");
        }
        final PersistentPlayQueue.State state = new PersistentPlayQueue.State();
        final JSONArray nodes = data.getJSONArray("nodes");
        final java.util.Set<String> identities = new java.util.HashSet<>();
        for (int i = 0; i < nodes.length(); i++) {
            final JSONObject value = nodes.getJSONObject(i);
            final PersistentPlayQueue.Node node = new PersistentPlayQueue.Node();
            if (value.has("uuid")) {
                final String uuid = value.getString("uuid");
                if (!identities.add(uuid)) {
                    throw new JSONException("Duplicate occurrence identity");
                }
                node.item = new PlayerMediaItem.Builder().uuid(uuid)
                        .serviceId(value.getInt("service")).url(value.getString("url"))
                        .title(value.getString("title")).uploader(value.getString("uploader"))
                        .uploaderUrl(nullableString(value, "uploaderUrl"))
                        .duration(value.getLong("duration"))
                        .thumbnailUrl(nullableString(value, "thumbnail"))
                        .streamType(StreamType.valueOf(value.getString("type")))
                        .isRoundPlayStream(value.getBoolean("round"))
                        .startAt(value.getLong("start")).build();
            } else {
                node.playlist = value.getBoolean("playlist");
                node.serviceId = value.getInt("service");
                node.url = value.getString("url");
                node.initial = value.getBoolean("initial");
                if (value.has("page")) {
                    final JSONObject page = value.getJSONObject("page");
                    final List<String> ids = page.has("ids") ? new ArrayList<>() : null;
                    if (ids != null) {
                        final JSONArray array = page.getJSONArray("ids");
                        for (int j = 0; j < array.length(); j++) {
                            ids.add(array.getString(j));
                        }
                    }
                    final Map<String, String> cookies = page.has("cookies") ? new HashMap<>() : null;
                    if (cookies != null) {
                        final JSONObject values = page.getJSONObject("cookies");
                        final Iterator<String> keys = values.keys();
                        while (keys.hasNext()) {
                            final String key = keys.next();
                            cookies.put(key, values.getString(key));
                        }
                    }
                    node.page = new Page(nullableString(page, "url"), nullableString(page, "id"),
                            ids, cookies, page.has("body")
                            ? Base64.decode(page.getString("body"), Base64.DEFAULT) : null);
                }
                if (!node.initial && !Page.isValid(node.page)) {
                    throw new JSONException("Missing pending source continuation");
                }
            }
            state.nodes.add(node);
        }
        final JSONObject progress = data.getJSONObject("progress");
        final Iterator<String> keys = progress.keys();
        while (keys.hasNext()) {
            final String uuid = keys.next();
            if (identities.contains(uuid)) {
                state.progress.put(uuid, progress.getLong(uuid));
            }
        }
        return state;
    }

    private static String nullableString(final JSONObject object, final String key)
            throws JSONException {
        return object.has(key) && !object.isNull(key) ? object.getString(key) : null;
    }
}
