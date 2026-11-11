package org.schabi.newpipe.local.feed.service

import android.content.Intent
import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.MainActivity
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.local.feed.FeedFragment
import org.schabi.newpipe.player.helper.PlayerHolder
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.NavigationHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Diagnostic original-UI scenario; no production behavior is modified by this test. */
@RunWith(AndroidJUnit4::class)
class MainActivityRefreshReproTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Test
    fun actualPlayerAndFeedRepeatedRefreshWithInputAndViewRecreation() {
        LargeFeedRefreshFixture().use { fixture ->
            fixture.seedSubscriptions()
            fixture.prepareMainActivity()
            val cycleTimeoutSeconds = InstrumentationRegistry.getArguments()
                .getString("cycleTimeoutSeconds", "360")!!.toLong().coerceIn(180, 600)
            NewPipe.setYoutubePlayerClient("mweb")
            val url = InstrumentationRegistry.getArguments().getString("url",
                "https://www.youtube.com/watch?v=G-eNlqqkn1w")!!
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
            var feed: FeedFragment? = null
            val heartbeatFailure = AtomicReference<String>()
            val monitor = Executors.newSingleThreadScheduledExecutor()
            val heartbeat = monitor.scheduleWithFixedDelay({
                val ack = CountDownLatch(1)
                mainHandler.post { ack.countDown() }
                if (!ack.await(1500, TimeUnit.MILLISECONDS)) {
                    val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n")
                    Log.e(TAG, "MAIN_STALL\n$stack")
                    heartbeatFailure.compareAndSet(null, stack)
                }
                val runtime = Runtime.getRuntime()
                Log.i(TAG, "memory javaUsed=${runtime.totalMemory() - runtime.freeMemory()} nativeAllocated=${Debug.getNativeHeapAllocatedSize()}")
            }, 0, 1, TimeUnit.SECONDS)
            try {
                onMain {
                    feed = FeedFragment.newInstance()
                    activity.supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_holder, feed!!).commitNow()
                    NavigationHelper.playOnMainPlayer(activity, SinglePlayQueue(info))
                }
                await("actual PlayerService playback", 150) {
                    var playing = false
                    onMain {
                        PlayerHolder.getInstance().player?.let {
                            if (!it.playWhenReady) it.play()
                            playing = it.isPlaying && it.currentPosition > 2000
                        }
                    }
                    playing
                }
                onMain {
                    assertNotNull(activity.supportFragmentManager.findFragmentById(R.id.fragment_player_holder))
                    BottomSheetBehavior.from(activity.findViewById<FrameLayout>(R.id.fragment_player_holder))
                        .state = BottomSheetBehavior.STATE_COLLAPSED
                }
                repeat(5) { cycle ->
                    val completed = CountDownLatch(1)
                    val failure = AtomicReference<Throwable>()
                    val requested = AtomicBoolean()
                    val started = AtomicBoolean()
                    val events = FeedEventManager.events().subscribe { event ->
                        when (event) {
                            is FeedEventManager.Event.ProgressEvent -> {
                                if (requested.get() && event.maxProgress == 1000 && event.currentProgress == 0) started.set(true)
                            }
                            is FeedEventManager.Event.SuccessResultEvent -> {
                                // BehaviorProcessor replays the previous cycle's result to new observers.
                                if (!started.get()) return@subscribe
                                if (event.itemsErrors.isNotEmpty()) failure.set(AssertionError("Feed errors: ${event.itemsErrors.size}"))
                                completed.countDown()
                            }
                            is FeedEventManager.Event.ErrorResultEvent -> {
                                if (!requested.get()) return@subscribe
                                failure.set(event.error)
                                completed.countDown()
                            }
                            else -> Unit
                        }
                    }
                    try {
                        val requestsBefore = fixture.requestCount()
                        var before = 0L
                        onMain {
                            before = PlayerHolder.getInstance().player!!.currentPosition
                            // The real refresh button calls FeedFragment.reloadContent -> service.
                            requested.set(true)
                            assertTrue(feed!!.requireView().findViewById<View>(R.id.refreshIcon).performClick())
                        }
                        val deadline = SystemClock.elapsedRealtime() + cycleTimeoutSeconds * 1000
                        var recreated = false
                        while (!completed.await(1, TimeUnit.SECONDS)) {
                            assertTrue("Refresh timed out", SystemClock.elapsedRealtime() < deadline)
                            assertNull("Main looper stalled", heartbeatFailure.get())
                            dispatchFeedSwipe(activity)
                            if (!recreated) {
                                onMain {
                                    activity.supportFragmentManager.beginTransaction().detach(feed!!).commitNow()
                                    activity.supportFragmentManager.beginTransaction().attach(feed!!).commitNow()
                                    if (cycle % 2 == 1) {
                                        val detail = activity.supportFragmentManager.findFragmentById(R.id.fragment_player_holder)!!
                                        activity.supportFragmentManager.beginTransaction().detach(detail).commitNow()
                                        activity.supportFragmentManager.beginTransaction().attach(detail).commitNow()
                                    }
                                }
                                recreated = true
                            }
                        }
                        failure.get()?.let { throw AssertionError("Refresh failed", it) }
                        assertEquals(1000, fixture.requestCount() - requestsBefore)
                        onMain {
                            val player = PlayerHolder.getInstance().player!!
                            assertTrue("Playback stopped during refresh", player.isPlaying)
                            assertTrue("Playback did not advance during refresh", player.currentPosition >= before + 3000)
                        }
                        assertNull("Main looper stalled", heartbeatFailure.get())
                        Log.i(TAG, "cycle=${cycle + 1} subscriptions=1000 playbackAdvanced=true inputDispatched=true feedViewRecreated=$recreated")
                        // Let the service's success callback finish stopping before the next tap.
                        onMain { }
                        Thread.sleep(300)
                    } finally {
                        // A heartbeat assertion must not close Room while the real service still writes.
                        try {
                            check(completed.await(cycleTimeoutSeconds, TimeUnit.SECONDS)) { "Refresh did not quiesce for cleanup" }
                            await("feed service success callback and shutdown", 30) {
                                var stopped = false
                                onMain {
                                    val manager = instrumentation.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                                    stopped = manager.getRunningServices(100).none {
                                        it.service.className == FeedLoadService::class.java.name
                                    }
                                }
                                stopped
                            }
                        } finally {
                            events.dispose()
                        }
                    }
                }
            } finally {
                heartbeat.cancel(true)
                monitor.shutdownNow()
                monitor.awaitTermination(3, TimeUnit.SECONDS)
                var stoppedPlayer: org.schabi.newpipe.player.Player? = null
                onMain {
                    stoppedPlayer = PlayerHolder.getInstance().player
                    instrumentation.targetContext.stopService(Intent(instrumentation.targetContext, FeedLoadService::class.java))
                    PlayerHolder.getInstance().stopService()
                    activity.finish()
                }
                // stopService() is asynchronous: keep the synthetic DB open through onDestroy.
                await("activity and player destruction", 20) {
                    var destroyed = false
                    onMain { destroyed = activity.isDestroyed && (stoppedPlayer?.exoPlayerIsNull() != false) }
                    destroyed
                }
                onMain { }
            }
        }
    }

    private fun dispatchFeedSwipe(activity: MainActivity) {
        var width = 0
        var height = 0
        onMain {
            width = activity.window.decorView.width
            height = activity.window.decorView.height
        }
        val down = SystemClock.uptimeMillis()
        for (step in 0..8) {
            val action = when (step) { 0 -> MotionEvent.ACTION_DOWN; 8 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                width * 0.5f, height * (0.62f - step * 0.025f), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            Thread.sleep(16)
        }
    }

    private fun onMain(action: () -> Unit) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        mainHandler.post {
            try { action() } catch (error: Throwable) { failure.set(error) } finally { done.countDown() }
        }
        check(done.await(10, TimeUnit.SECONDS)) {
            "Main operation timed out: " + Looper.getMainLooper().thread.stackTrace.joinToString("\n")
        }
        failure.get()?.let { throw AssertionError("Main operation failed", it) }
    }

    private fun await(label: String, seconds: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $label" }
            Thread.sleep(250)
        }
    }

    companion object { private const val TAG = "MainActivityRefreshRepro" }
}
