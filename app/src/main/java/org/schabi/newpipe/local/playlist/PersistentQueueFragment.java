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
import org.schabi.newpipe.player.mediaitem.PlayerMediaItem;
import org.schabi.newpipe.player.playqueue.*;
import org.schabi.newpipe.util.NavigationHelper;
import io.reactivex.rxjava3.disposables.CompositeDisposable;

/** Editable playlist projection of the single owner, including when the player is closed. */
public final class PersistentQueueFragment extends BaseFragment {
    private PersistentPlayQueue queue;
    private PlayQueueAdapter adapter;
    private ItemTouchHelper touch;
    private final CompositeDisposable operations = new CompositeDisposable();

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             final ViewGroup container, final Bundle state) {
        setHasOptionsMenu(true);
        setTitle("Queue");
        queue = PersistentQueueManager.getInstance(requireContext()).getQueue();
        if (queue.getBroadcastReceiver() == null) {
            queue.init();
        }
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
                    final PlayerMediaItem item = queue.getItem(index);
                    QueueViewActions.edit(requireContext(), () -> queue.remove(queue.indexOf(item)));
                    adapter.notifyDataSetChanged();
                }
            }
        });
        touch.attachToRecyclerView(list);
        adapter.setSelectedListener(new PlayQueueItemBuilder.OnSelectedListener() {
            @Override
            public void selected(final PlayerMediaItem item, final View view) {
                QueueViewActions.edit(requireContext(), () -> {
                    queue.setIndex(queue.indexOf(item));
                    NavigationHelper.playOnMainPlayer(activity, queue);
                });
            }
            @Override
            public void held(final PlayerMediaItem item, final View view) {
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setItems(new String[]{"Remove from queue"}, (dialog, which) ->
                                QueueViewActions.edit(requireContext(),
                                        () -> queue.remove(queue.indexOf(item)))).show();
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
        return list;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu, @NonNull final MenuInflater inflater) {
        menu.add(0, 9001, 0, "Save queue as playlist");
        menu.add(0, 9002, 1, "Shuffle upcoming");
        menu.add(0, 9003, 2, "Clear queue");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (item.getItemId() == 9003) {
            QueueViewActions.clear(requireContext());
            return true;
        }
        if (item.getItemId() == 9001 || item.getItemId() == 9002) {
            operations.add(QueueViewActions.materialize(requireContext(), getParentFragmentManager(),
                    queue, item.getItemId() == 9002));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroyView() {
        operations.clear();
        adapter.dispose();
        touch.attachToRecyclerView(null);
        super.onDestroyView();
    }
}
