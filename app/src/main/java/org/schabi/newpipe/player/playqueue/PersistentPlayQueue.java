package org.schabi.newpipe.player.playqueue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ListInfo;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.playqueue.events.InitEvent;
import org.schabi.newpipe.player.playqueue.events.MoveEvent;
import org.schabi.newpipe.player.playqueue.events.RecoveryEvent;
import org.schabi.newpipe.player.playqueue.events.RemoveEvent;
import org.schabi.newpipe.player.playqueue.events.SelectEvent;
import org.schabi.newpipe.util.ExtractorHelper;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The shared unfinished queue. Every command saves a candidate before publishing it.
 * Pending nodes are invisible insertion boundaries, not playable entries. They survive
 * consuming/removing adjacent entries and prevent continuation pages crossing old batches.
 */
public final class PersistentPlayQueue extends PlayQueue {
    private static final long serialVersionUID = 1L;

    static final class Node implements Serializable {
        private static final long serialVersionUID = 1L;
        PlayerMediaItem item;
        boolean playlist;
        int serviceId;
        String url;
        Page page;
        boolean initial;

        static Node entry(final PlayerMediaItem item) {
            final Node node = new Node();
            node.item = item;
            return node;
        }
    }

    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final int version = 1;
        final List<Node> nodes = new ArrayList<>();
        final Map<String, Long> progress = new HashMap<>();

        State copy() {
            final State copy = new State();
            copy.nodes.addAll(nodes); // Nodes are immutable after insertion.
            copy.progress.putAll(progress);
            return copy;
        }
    }

    @FunctionalInterface
    interface PageResolver {
        Single<State> resolve(State source, Node boundary);
    }

    private transient PageResolver pageResolver;
    private State state;
    private transient PersistentQueueStore store;
    private transient boolean frozen;
    private transient boolean fetching;
    private transient Throwable pendingError;
    private long structuralRevision;

    PersistentPlayQueue(final PersistentQueueStore store) {
        this(store, PersistentPlayQueue::resolve);
    }

    PersistentPlayQueue(final PersistentQueueStore store, final PageResolver pageResolver) {
        super(0, Collections.emptyList());
        this.pageResolver = pageResolver;
        this.store = store;
        state = store.load();
        init();
    }

    @Override
    public synchronized void init() {
        if (getBroadcastReceiver() == null) {
            super.init();
        }
    }

    @Override
    public void dispose() {
        // The manager owns this bus for the entire process, including closed-player views.
    }

    private void commit(final State candidate) {
        commit(candidate, true);
    }

    private void commit(final State candidate, final boolean structural) {
        if (frozen || store == null) {
            throw new IllegalStateException("This queue owner is no longer writable");
        }
        store.save(candidate);
        state = candidate;
        if (structural) {
            structuralRevision++;
        }
    }

    synchronized void freeze() {
        frozen = true;
    }

    @Override
    public int getIndex() {
        return 0;
    }

    @Override
    public synchronized List<PlayerMediaItem> getStreams() {
        return Collections.unmodifiableList(items(state));
    }

    private static List<PlayerMediaItem> items(final State source) {
        final List<PlayerMediaItem> result = new ArrayList<>();
        for (final Node node : source.nodes) {
            if (node.item != null) {
                result.add(node.item);
            }
        }
        return result;
    }

    @Override
    public synchronized int size() {
        return items(state).size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return size() == 0;
    }

    @Override
    @Nullable
    public synchronized PlayerMediaItem getItem(final int index) {
        final List<PlayerMediaItem> entries = items(state);
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    @Override
    public synchronized int indexOf(@NonNull final PlayerMediaItem item) {
        return items(state).indexOf(item);
    }

    private int nodeIndex(final int index) {
        int visible = 0;
        for (int i = 0; i < state.nodes.size(); i++) {
            if (state.nodes.get(i).item != null && visible++ == index) {
                return i;
            }
        }
        return -1;
    }

    /** True when playback must wait for a pending page, even if old entries remain visible. */
    public synchronized boolean hasBlockedBoundary() {
        return !state.nodes.isEmpty() && state.nodes.get(0).item == null;
    }

    @Override
    public synchronized boolean isComplete() {
        return state.nodes.stream().noneMatch(node -> node.item == null);
    }

    @Nullable
    public synchronized Throwable getPendingError() {
        return pendingError;
    }

    @Override
    public synchronized void setIndex(final int index) {
        final int nodeIndex = nodeIndex(index);
        if (nodeIndex < 0 || index == 0) {
            return;
        }
        final State candidate = state.copy();
        candidate.nodes.add(0, candidate.nodes.remove(nodeIndex));
        commit(candidate);
        broadcast(new MoveEvent(index, 0));
        broadcast(new SelectEvent(index, 0));
    }

    public synchronized void consume() {
        if (hasBlockedBoundary()) {
            fetch();
            return;
        }
        remove(0);
    }

    @Override
    public synchronized void offsetIndex(final int offset) {
        if (offset > 0) {
            consume();
        }
    }

    @Override
    public synchronized void remove(final int index) {
        final int position = nodeIndex(index);
        if (position < 0) {
            return;
        }
        final State candidate = state.copy();
        candidate.progress.remove(candidate.nodes.remove(position).item.getUuid());
        commit(candidate);
        broadcast(new RemoveEvent(index, 0));
    }

    @Override
    public synchronized void move(final int source, final int target) {
        if (source <= 0 || target <= 0 || source == target) {
            return;
        }
        final int from = nodeIndex(source);
        final int to = nodeIndex(target);
        if (from < 0 || to < 0) {
            return;
        }
        final State candidate = state.copy();
        candidate.nodes.add(to, candidate.nodes.remove(from));
        commit(candidate);
        broadcast(new MoveEvent(source, target));
    }

    @Override
    public synchronized void append(@NonNull final List<PlayerMediaItem> entries) {
        final State candidate = state.copy();
        for (final PlayerMediaItem item : entries) {
            candidate.nodes.add(Node.entry(portable(item, true)));
        }
        commit(candidate);
        broadcast(new InitEvent());
    }

    @Override
    public void appendAutoQueued(@NonNull final List<PlayerMediaItem> entries) {
        // Queue mode never adds recommendations or speculative items.
    }

    @Override
    public void error() {
        // Extraction/playback failures never consume an unfinished occurrence.
    }

    @Override
    public boolean previous() {
        return false; // The player uses global history, not an occurrence trail.
    }

    @Override
    public boolean isShuffled() {
        return false;
    }

    @Override
    public void unshuffle() {
        // No original-order backup exists in consuming mode.
    }

    @Override
    public void shuffle() {
        shuffleUpcoming(() -> false);
    }

    @Override
    public synchronized void setRecovery(final int index, final long position) {
        if (frozen) {
            return; // Player shutdown may checkpoint after backup has frozen the owner.
        }
        final PlayerMediaItem item = getItem(index);
        if (item == null) {
            return;
        }
        final State candidate = state.copy();
        if (position == RECOVERY_UNSET) {
            candidate.progress.remove(item.getUuid());
        } else {
            candidate.progress.put(item.getUuid(), position);
        }
        commit(candidate, false);
        broadcast(new RecoveryEvent(index, position));
    }

    @Override
    public synchronized long getRecoveryPosition(@NonNull final PlayerMediaItem item) {
        return state.progress.getOrDefault(item.getUuid(), RECOVERY_UNSET);
    }

    @Override
    public synchronized long getRecoveryPosition(final int index) {
        final PlayerMediaItem item = getItem(index);
        return item == null ? RECOVERY_UNSET : getRecoveryPosition(item);
    }

    @Override
    public synchronized void unsetRecovery(final int index) {
        setRecovery(index, RECOVERY_UNSET);
    }

    public synchronized void clearRecovery(final int serviceId, final String url) {
        final State candidate = state.copy();
        for (final PlayerMediaItem item : items(candidate)) {
            if (item.getServiceId() == serviceId && item.getUrl().equals(url)) {
                candidate.progress.remove(item.getUuid());
            }
        }
        commit(candidate, false);
        broadcast(new RecoveryEvent(0, RECOVERY_UNSET));
    }

    public synchronized void clearRecovery() {
        final State candidate = state.copy();
        candidate.progress.clear();
        commit(candidate, false);
        broadcast(new RecoveryEvent(0, RECOVERY_UNSET));
    }

    synchronized void clear() {
        commit(new State());
        pendingError = null;
        broadcast(new InitEvent());
    }

    synchronized void insert(final PlayQueue incoming, final boolean playNow, final boolean next) {
        if (incoming == this) {
            return;
        }
        final State candidate = state.copy();
        final List<Node> batch = new ArrayList<>();
        for (int i = Math.max(0, incoming.getIndex()); i < incoming.size(); i++) {
            final PlayerMediaItem original = incoming.getItem(i);
            final PlayerMediaItem item = portable(original, true);
            batch.add(Node.entry(item));
            final long recovery = incoming.getRecoveryPosition(i);
            if (recovery != RECOVERY_UNSET) {
                candidate.progress.put(item.getUuid(), recovery);
            }
        }
        if (!incoming.isComplete()) {
            if (!(incoming instanceof AbstractInfoPlayQueue)) {
                throw new IllegalArgumentException("Unsupported remote queue source");
            }
            final AbstractInfoPlayQueue<?> remote = (AbstractInfoPlayQueue<?>) incoming;
            final Node boundary = new Node();
            boundary.playlist = incoming instanceof PlaylistPlayQueue;
            boundary.serviceId = remote.serviceId;
            boundary.url = remote.baseUrl;
            boundary.page = remote.nextPage;
            boundary.initial = remote.isInitial;
            batch.add(boundary);
        }
        // Insert next before any pending boundary immediately after current.
        final int position = playNow || candidate.nodes.isEmpty() ? 0
                : next ? Math.max(0, nodeIndex(0) + 1) : candidate.nodes.size();
        candidate.nodes.addAll(position, batch);
        commit(candidate);
        broadcast(new InitEvent());
        if (playNow) {
            broadcast(new SelectEvent(0, 0));
        }
    }

    /** Strip transient resolved streams/errors; keep just portable queue metadata and identity. */
    private static PlayerMediaItem portable(final PlayerMediaItem item, final boolean fresh) {
        return new PlayerMediaItem.Builder()
                .uuid(fresh ? PlayerMediaItem.newUuid() : item.getUuid())
                .serviceId(item.getServiceId()).url(item.getUrl()).title(item.getTitle())
                .uploader(item.getUploader()).uploaderUrl(item.getUploaderUrl())
                .duration(item.getDuration()).thumbnailUrl(item.getThumbnailUrl())
                .streamType(item.getStreamType()).isRoundPlayStream(item.isRoundPlayStream())
                .startAt(item.getStartAt()).build();
    }

    private static Single<State> resolve(final State source, final Node boundary) {
        if (boundary.initial) {
            final Single<? extends ListInfo<StreamInfoItem>> head = boundary.playlist
                    ? ExtractorHelper.getPlaylistInfo(boundary.serviceId, boundary.url, false)
                    : ExtractorHelper.getChannelInfo(boundary.serviceId, boundary.url, false);
            return head.map(info -> splice(source, boundary, info.getRelatedItems(),
                    info.getNextPage()));
        }
        final Single<ListExtractor.InfoItemsPage<StreamInfoItem>> page = boundary.playlist
                ? ExtractorHelper.getMorePlaylistItems(boundary.serviceId, boundary.url, boundary.page)
                : ExtractorHelper.getMoreChannelItems(boundary.serviceId, boundary.url, boundary.page);
        return page.map(info -> splice(source, boundary, info.getItems(), info.getNextPage()));
    }

    static State splice(final State source, final Node boundary,
                                final List<StreamInfoItem> entries, final Page next) {
        final State candidate = source.copy();
        int position = candidate.nodes.indexOf(boundary);
        candidate.nodes.remove(position);
        for (final StreamInfoItem item : entries) {
            candidate.nodes.add(position++, Node.entry(PlayerMediaItem.forStreamInfoItem(item)));
        }
        if (Page.isValid(next)) {
            final Node continuation = new Node();
            continuation.playlist = boundary.playlist;
            continuation.serviceId = boundary.serviceId;
            continuation.url = boundary.url;
            continuation.page = next;
            candidate.nodes.add(position, continuation);
        }
        return candidate;
    }

    private static Node firstPending(final State source) {
        return source.nodes.stream().filter(node -> node.item == null).findFirst().orElse(null);
    }

    /** Explicit user retry; implicit loader fetches never bypass a retained failure. */
    public synchronized void retryPending() {
        pendingError = null;
        fetch();
    }

    @Override
    public synchronized void fetch() {
        final Node boundary = firstPending(state);
        if (frozen || fetching || pendingError != null || boundary == null) {
            return;
        }
        fetching = true;
        pendingError = null;
        final State snapshot = state.copy();
        Single.defer(() -> pageResolver.resolve(snapshot, boundary)).subscribeOn(Schedulers.io()).subscribe(result -> {
            synchronized (PersistentPlayQueue.this) {
                fetching = false;
                if (frozen || !state.nodes.contains(boundary)) {
                    return;
                }
                // Merge only this boundary into latest state; never overwrite concurrent edits.
                final State merged = state.copy();
                final int at = merged.nodes.indexOf(boundary);
                merged.nodes.remove(at);
                final int start = snapshot.nodes.indexOf(boundary);
                final int count = result.nodes.size() - snapshot.nodes.size() + 1;
                merged.nodes.addAll(at, result.nodes.subList(start, start + count));
                try {
                    commit(merged);
                    pendingError = null;
                } catch (final RuntimeException failure) {
                    pendingError = failure;
                }
                broadcast(new InitEvent());
            }
        }, failure -> {
            synchronized (PersistentPlayQueue.this) {
                fetching = false;
                if (!frozen) {
                    pendingError = failure;
                    broadcast(new InitEvent());
                }
            }
        });
    }

    private State materialized(final State snapshot, final BooleanSupplier cancelled) {
        State result = snapshot;
        Node boundary;
        while ((boundary = firstPending(result)) != null) {
            checkCancelled(cancelled);
            final java.util.concurrent.Future<State> request = pageResolver.resolve(result, boundary)
                    .subscribeOn(Schedulers.io()).toFuture();
            try {
                while (true) {
                    checkCancelled(cancelled);
                    try {
                        result = request.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                        break;
                    } catch (final java.util.concurrent.TimeoutException waiting) {
                        // Check cancellation while extraction is still running.
                    }
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Queue materialization interrupted");
            } catch (final java.util.concurrent.ExecutionException failure) {
                throw new IllegalStateException("Cannot load complete queue", failure.getCause());
            } finally {
                request.cancel(true);
            }
        }
        checkCancelled(cancelled);
        return result;
    }

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Queue materialization cancelled");
        }
    }

    /** Blocking; call on an IO worker. The returned copy never mutates the live queue. */
    public List<PlayerMediaItem> materialize(final BooleanSupplier cancelled) {
        final State snapshot;
        synchronized (this) {
            snapshot = state.copy();
        }
        return Collections.unmodifiableList(items(materialized(snapshot, cancelled)));
    }

    /** Blocking; cancellation/error or intervening edits leave live order untouched. */
    public void shuffleUpcoming(final BooleanSupplier cancelled) {
        final State snapshot;
        final long expectedRevision;
        synchronized (this) {
            snapshot = state.copy();
            expectedRevision = structuralRevision;
        }
        final State candidate = materialized(snapshot, cancelled);
        if (candidate.nodes.size() > 2) {
            Collections.shuffle(candidate.nodes.subList(1, candidate.nodes.size()));
        }
        synchronized (this) {
            checkCancelled(cancelled);
            if (structuralRevision != expectedRevision) {
                throw new ConcurrentModificationException("Queue changed while loading pages");
            }
            // Progress checkpoints/deletions are independent of the membership snapshot.
            candidate.progress.clear();
            candidate.progress.putAll(state.progress);
            commit(candidate);
            broadcast(new InitEvent());
        }
    }
}
