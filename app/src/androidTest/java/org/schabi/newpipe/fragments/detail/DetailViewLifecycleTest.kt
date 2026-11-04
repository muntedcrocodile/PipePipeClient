package org.schabi.newpipe.fragments.detail

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.databinding.FragmentVideoDetailBinding

/** Real fragment teardown/recreation with callbacks already captured by a view-tree dispatch. */
@RunWith(AndroidJUnit4::class)
class DetailViewLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun detachedDetailRejectsLateSheetLayoutWithoutHostOrOldViewWrites() = withDetail { activity, fragment ->
        val sheet = field(fragment, "sheet") as MiniPlayerSheetController
        val binding = field(fragment, "_binding") as FragmentVideoDetailBinding
        val listener = field(sheet, "pendingLayoutListener") as ViewTreeObserver.OnGlobalLayoutListener
        val observer = activity.window.decorView.viewTreeObserver
        activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
        assertFalse(fragment.isAdded)
        assertNull(fragment.view)
        assertNull(field(sheet, "pendingLayoutListener"))

        val holder = activity.findViewById<ViewGroup>(R.id.fragment_holder)
        val behavior = BottomSheetBehavior.from(activity.findViewById<FrameLayout>(R.id.fragment_player_holder))
        holder.setPadding(1, 2, 3, 123)
        behavior.peekHeight = 257
        setField(fragment, "sheetStateToSave", BottomSheetBehavior.STATE_HIDDEN)
        binding.overlayLayout.root.alpha = 0.37f

        // Removal cannot recall a callback already copied into a dispatch snapshot.
        listener.onGlobalLayout()
        if (observer.isAlive) observer.dispatchOnGlobalLayout()
        binding.root.viewTreeObserver.dispatchOnGlobalLayout()

        assertEquals(123, holder.paddingBottom)
        assertEquals(257, behavior.peekHeight)
        assertEquals(BottomSheetBehavior.STATE_HIDDEN, field(fragment, "sheetStateToSave"))
        assertEquals(0.37f, binding.overlayLayout.root.alpha, 0f)
        assertFalse(binding.overlayLayout.overlayPlayPauseButton.performClick())
    }

    @Test
    fun recreatedDetailRejectsOldLayoutAndPreDrawCallbacksWithoutBindingWrites() = withDetail { activity, fragment ->
        val oldSheet = field(fragment, "sheet") as MiniPlayerSheetController
        val oldPlayerView = field(fragment, "playerView") as DetailPlayerViewHost
        val oldBinding = field(fragment, "_binding") as FragmentVideoDetailBinding
        val layout = field(oldSheet, "pendingLayoutListener") as ViewTreeObserver.OnGlobalLayoutListener
        val preDraw = field(oldPlayerView, "preDrawListener") as ViewTreeObserver.OnPreDrawListener
        oldPlayerView.scrollPastPlayer()
        val manager = activity.supportFragmentManager
        manager.beginTransaction().detach(fragment).commitNow()
        manager.beginTransaction().attach(fragment).commitNow()

        val newBinding = field(fragment, "_binding") as FragmentVideoDetailBinding
        assertTrue(fragment.isAdded)
        assertNotSame(oldBinding.root, fragment.requireView())
        assertNotSame(oldSheet, field(fragment, "sheet"))
        assertNull(field(oldPlayerView, "preDrawObserver"))
        assertNull(field(oldPlayerView, "scrollAnimator"))
        oldBinding.detailThumbnailRootLayout.detailThumbnailImageView.apply {
            layoutParams.height = 37
            minimumHeight = 37
        }
        newBinding.detailThumbnailRootLayout.detailThumbnailImageView.apply {
            layoutParams.height = 81
            minimumHeight = 81
        }
        oldBinding.overlayLayout.root.alpha = 0.37f
        val holder = activity.findViewById<ViewGroup>(R.id.fragment_holder)
        val behavior = BottomSheetBehavior.from(activity.findViewById<FrameLayout>(R.id.fragment_player_holder))
        holder.setPadding(1, 2, 3, 183)
        behavior.peekHeight = 297
        setField(fragment, "sheetStateToSave", BottomSheetBehavior.STATE_HIDDEN)

        // The fragment is attached again: checking only isAdded would accept the old binding.
        layout.onGlobalLayout()
        assertTrue("A stale pre-draw callback must not cancel a new view's draw", preDraw.onPreDraw())
        oldPlayerView.updateThumbnailHeight()
        oldSheet.updateOverlayData("obsolete", "obsolete", null)

        assertEquals(183, holder.paddingBottom)
        assertEquals(297, behavior.peekHeight)
        assertEquals(BottomSheetBehavior.STATE_HIDDEN, field(fragment, "sheetStateToSave"))
        assertEquals(0.37f, oldBinding.overlayLayout.root.alpha, 0f)
        assertEquals(37, oldBinding.detailThumbnailRootLayout.detailThumbnailImageView.layoutParams.height)
        assertEquals(37, oldBinding.detailThumbnailRootLayout.detailThumbnailImageView.minimumHeight)
        assertEquals(81, newBinding.detailThumbnailRootLayout.detailThumbnailImageView.layoutParams.height)
        assertEquals(81, newBinding.detailThumbnailRootLayout.detailThumbnailImageView.minimumHeight)
    }

    private fun withDetail(test: (MainActivity, VideoDetailFragment) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            // Create, capture and destroy before the first layout, in a single main-thread turn.
            instrumentation.runOnMainSync {
                val fragment = VideoDetailFragment.getInstanceInCollapsedState()
                activity.supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_player_holder, fragment).commitNow()
                test(activity, fragment)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }
}
