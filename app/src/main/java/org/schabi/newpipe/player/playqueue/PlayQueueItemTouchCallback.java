package org.schabi.newpipe.player.playqueue;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

public abstract class PlayQueueItemTouchCallback extends ItemTouchHelper.SimpleCallback {
    private static final int MINIMUM_INITIAL_DRAG_VELOCITY = 10;
    private static final int MAXIMUM_INITIAL_DRAG_VELOCITY = 25;

    public PlayQueueItemTouchCallback() {
        super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
    }

    public abstract void onMove(int sourceIndex, int targetIndex);

    public abstract void onSwiped(int index);

    @Override
    public int getMovementFlags(final RecyclerView recyclerView,
                                final RecyclerView.ViewHolder holder) {
        final RecyclerView.Adapter<?> adapter = holder.getBindingAdapter();
        final int position = holder.getBindingAdapterPosition();
        if (!(adapter instanceof PlayQueueAdapter)) {
            return 0;
        }
        final PlayQueueAdapter queueAdapter = (PlayQueueAdapter) adapter;
        return makeMovementFlags(queueAdapter.canDrag(position)
                ? ItemTouchHelper.UP | ItemTouchHelper.DOWN : 0,
                queueAdapter.isQueuePosition(position)
                ? ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT : 0);
    }

    @Override
    public int interpolateOutOfBoundsScroll(final RecyclerView recyclerView, final int viewSize,
                                            final int viewSizeOutOfBounds, final int totalSize,
                                            final long msSinceStartScroll) {
        final int standardSpeed = super.interpolateOutOfBoundsScroll(recyclerView, viewSize,
                viewSizeOutOfBounds, totalSize, msSinceStartScroll);
        final int clampedAbsVelocity = Math.max(MINIMUM_INITIAL_DRAG_VELOCITY,
                Math.min(Math.abs(standardSpeed), MAXIMUM_INITIAL_DRAG_VELOCITY));
        return clampedAbsVelocity * (int) Math.signum(viewSizeOutOfBounds);
    }

    @Override
    public boolean onMove(final RecyclerView recyclerView, final RecyclerView.ViewHolder source,
                          final RecyclerView.ViewHolder target) {
        if (source.getItemViewType() != target.getItemViewType()) {
            return false;
        }

        final int sourceIndex = source.getBindingAdapterPosition();
        final int targetIndex = target.getBindingAdapterPosition();
        if (!(source.getBindingAdapter() instanceof PlayQueueAdapter)
                || !((PlayQueueAdapter) source.getBindingAdapter()).canDrag(sourceIndex)
                || !((PlayQueueAdapter) source.getBindingAdapter()).canDrag(targetIndex)) {
            return false;
        }
        onMove(sourceIndex, targetIndex);
        return true;
    }

    @Override
    public boolean isLongPressDragEnabled() {
        return false;
    }

    @Override
    public boolean isItemViewSwipeEnabled() {
        return true;
    }

    @Override
    public void onSwiped(final RecyclerView.ViewHolder viewHolder, final int swipeDir) {
        onSwiped(viewHolder.getBindingAdapterPosition());
    }
}
