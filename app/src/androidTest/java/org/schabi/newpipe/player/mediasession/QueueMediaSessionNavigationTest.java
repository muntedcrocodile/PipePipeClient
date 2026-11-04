package org.schabi.newpipe.player.mediasession;

import static android.support.v4.media.session.PlaybackStateCompat.*;
import static org.junit.Assert.*;

import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Queue mode advertises real skip actions even when the ordinary-mode preference requests seek. */
@RunWith(AndroidJUnit4.class)
public class QueueMediaSessionNavigationTest {
    @Test
    public void skipActionsRemainAvailableForPersistentQueueIndependentOfSeekPreference() {
        final var instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            final MediaSessionCompat session = new MediaSessionCompat(
                    instrumentation.getTargetContext(), "queue-navigation-test");
            try {
                final Callback callback = new Callback();
                final PlayQueueNavigator navigator = new PlayQueueNavigator(session, callback);
                navigator.setSeekInsteadOfSkip(() -> true);
                assertEquals(ACTION_SKIP_TO_QUEUE_ITEM,
                        navigator.getSupportedQueueNavigatorActions(null));
                callback.persistent = true;
                final long actions = navigator.getSupportedQueueNavigatorActions(null);
                assertTrue((actions & ACTION_SKIP_TO_PREVIOUS) != 0);
                assertTrue((actions & ACTION_SKIP_TO_NEXT) != 0);
                assertTrue((actions & ACTION_SKIP_TO_QUEUE_ITEM) != 0);
                // Transport callbacks do not substitute seek for these actions.
                navigator.onSkipToPrevious(null);
                navigator.onSkipToNext(null);
                assertEquals(1, callback.previous);
                assertEquals(1, callback.next);
                assertEquals(0, callback.seeks);
            } finally {
                session.release();
            }
        });
    }

    private static final class Callback implements MediaSessionCallback {
        boolean persistent;
        int previous;
        int next;
        int seeks;
        @Override public boolean isPersistentQueueMode() { return persistent; }
        @Override public void playPrevious() { previous++; }
        @Override public void playNext() { next++; }
        @Override public void fastRewind() { seeks++; }
        @Override public void fastForward() { seeks++; }
        @Override public void playItemAtIndex(final int index) { }
        @Override public int getCurrentPlayingIndex() { return 0; }
        @Override public int getQueueSize() { return 1; }
        @Override public MediaDescriptionCompat getQueueMetadata(final int index) { return null; }
        @Override public void play() { }
        @Override public void pause() { }
        @Override public void changePlayMode() { }
        @Override public void close() { }
    }
}
