package org.schabi.newpipe.local.playlist;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.schabi.newpipe.BaseFragment;
import org.schabi.newpipe.R;
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.playqueue.*;
import org.schabi.newpipe.util.NavigationHelper;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/** Editable playlist projection of the single owner, including when the player is closed. */
public final class PersistentQueueFragment extends BaseFragment {
    private org.schabi.newpipe.player.Player activePlayer;
    private boolean bound;
    private boolean connecting;
    private final java.util.ArrayDeque<PlayerMediaItem> pendingSelections =
            new java.util.ArrayDeque<>();
    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(final android.content.ComponentName name,
                                       final android.os.IBinder binder) {
            if (binder instanceof org.schabi.newpipe.player.PlayerBinderInterface) {
                activePlayer = ((org.schabi.newpipe.player.PlayerBinderInterface) binder).getPlayer();
            }
            connecting = false;
            drainSelections();
        }
        @Override
        public void onNullBinding(final android.content.ComponentName name) {
            activePlayer = null;
            connecting = false;
            drainSelections();
        }
        @Override
        public void onServiceDisconnected(final android.content.ComponentName name) {
            activePlayer = null;
            connecting = false;
            drainSelections();
        }
    };
    private PersistentPlayQueue queue;
    private QueuePendingView pendingView;
    private PlayQueueAdapter adapter;
    private ItemTouchHelper touch;
    private final CompositeDisposable operations = new CompositeDisposable();

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container, final Bundle state) {
        setHasOptionsMenu(true);
        setTitle(getString(R.string.queue_view_title));
        queue = PersistentQueueManager.getInstance(requireContext()).getQueue();
        if (queue.getBroadcastReceiver() == null) {
            queue.init();
        }
        final android.content.Intent service = new android.content.Intent(requireContext(),
                org.schabi.newpipe.util.DeviceUtils.getPlayerServiceClass());
        service.setAction(org.schabi.newpipe.player.PlayerIntentConstants.BIND_PLAYER_HOLDER_ACTION);
        connecting = true;
        bound = requireContext().bindService(service, connection, 0);
        connecting = bound;
        final RecyclerView list = new RecyclerView(requireContext());
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new PlayQueueAdapter(requireContext(), queue);
        list.setAdapter(adapter);
        touch = new ItemTouchHelper(new PlayQueueItemTouchCallback() {
            @Override
            public void onMove(final int from, final int to) {
                QueueViewActions.edit(requireContext(), () -> queue.move(from, to));
            }
            @Override
            public void onSwiped(final int index) {
                if (index >= 0 && index < queue.size()) {
                    QueueViewActions.remove(requireContext(), queue, index);
                    adapter.notifyDataSetChanged();
                }
            }
        });
        touch.attachToRecyclerView(list);
        adapter.setSelectedListener(new PlayQueueItemBuilder.OnSelectedListener() {
            @Override
            public void selected(final PlayerMediaItem item, final View view) {
                pendingSelections.add(item);
                drainSelections();
            }
            @Override
            public void held(final PlayerMediaItem item, final View view) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setItems(new String[]{getString(R.string.queue_view_remove)}, (dialog, which) ->
                                QueueViewActions.remove(requireContext(), queue, queue.indexOf(item))).show();
            }
            @Override
            public void onStartDrag(final PlayQueueItemHolder holder) {
                if (adapter.canDrag(holder.getBindingAdapterPosition())) {
                    touch.startDrag(holder);
                }
            }
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView recycler, final int dx, final int dy) {
                if (dy > 0 && !recycler.canScrollVertically(1) && !queue.isComplete()) {
                    queue.fetch();
                }
            }
        });
        final android.widget.FrameLayout root = new android.widget.FrameLayout(requireContext());
        root.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        final View empty = inflater.inflate(R.layout.fragment_empty, root, false);
        root.addView(empty);
        pendingView = new QueuePendingView(requireContext(), queue);
        root.addView(pendingView, new android.widget.FrameLayout.LayoutParams(-1, -1));
        final Runnable updateEmpty = () -> empty.setVisibility(queue.isEmpty() && queue.isComplete()
                ? View.VISIBLE : View.GONE);
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                updateEmpty.run();
            }
            @Override
            public void onItemRangeRemoved(final int start, final int count) {
                updateEmpty.run();
            }
            @Override
            public void onItemRangeInserted(final int start, final int count) {
                updateEmpty.run();
            }
        });
        updateEmpty.run();
        return root;
    }

    private void drainSelections() {
        if (connecting || !isAdded()) {
            return;
        }
        while (!pendingSelections.isEmpty()) {
            final PlayerMediaItem item = pendingSelections.removeFirst();
            if (queue.indexOf(item) < 0) {
                continue;
            }
            QueueViewActions.edit(requireContext(), () -> {
                if (activePlayer != null && activePlayer.getPlayQueue() == queue) {
                    activePlayer.selectQueueItem(item);
                } else {
                    queue.setIndex(queue.indexOf(item));
                    NavigationHelper.playOnMainPlayer(activity, queue);
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!PersistentQueueManager.isEnabled(requireContext())) {
            getParentFragmentManager().popBackStack();
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        inflater.inflate(R.menu.persistent_queue, menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.queue_view_clear) {
            QueueViewActions.clear(requireContext(), activePlayer);
            return true;
        }
        if (item.getItemId() == R.id.queue_view_save || item.getItemId() == R.id.queue_view_shuffle) {
            operations.add(QueueViewActions.materialize(requireContext(), getParentFragmentManager(),
                    queue, item.getItemId() == R.id.queue_view_shuffle));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroyView() {
        operations.clear();
        pendingView.dispose();
        pendingView = null;
        if (bound) {
            requireContext().unbindService(connection);
            bound = false;
        }
        activePlayer = null;
        connecting = false;
        pendingSelections.clear();
        adapter.dispose();
        touch.attachToRecyclerView(null);
        super.onDestroyView();
    }
}
