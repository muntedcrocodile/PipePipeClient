package org.schabi.newpipe.fragments.list.kiosk

import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.subjects.SingleSubject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.about.AboutActivity
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/** Real kiosk lifecycle/refresh callbacks; only the extraction result is controlled, never network. */
@RunWith(AndroidJUnit4::class)
class KioskRefreshTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var preferences: SharedPreferences
    private lateinit var pullKey: String
    private var originalPull: Boolean? = null
    private var originalSwipes: Boolean? = null

    @Before
    fun setUp() {
        val context = instrumentation.targetContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        pullKey = context.getString(R.string.pull_to_refresh_key)
        originalPull = preferences.all[pullKey] as? Boolean
        originalSwipes = preferences.all["video_swipe_enabled"] as? Boolean
        preferences.edit().putBoolean(pullKey, true).putBoolean("video_swipe_enabled", true).commit()
    }

    @After
    fun restorePreferences() {
        preferences.edit().apply {
            originalPull?.let { putBoolean(pullKey, it) } ?: remove(pullKey)
            originalSwipes?.let { putBoolean("video_swipe_enabled", it) } ?: remove("video_swipe_enabled")
        }.commit()
    }

    @Test
    fun pullingAKioskVideoForcesReloadAndReplacesRows() = withKiosk { _, fragment ->
        complete(fragment, "initial")
        onMain { assertEquals(listOf(false), fragment.loads.map { it.force }) }
        pull(fragment)
        await { fragment.loads.size == 2 }
        onMain {
            assertTrue(fragment.loads.last().force)
            assertTrue(fragment.refresh().isRefreshing)
        }
        complete(fragment, "refreshed")
        onMain {
            assertEquals(listOf("refreshed"), fragment.titles())
            assertFalse(fragment.refresh().isRefreshing)
        }
    }

    @Test
    fun failedKioskRefreshClearsSpinnerAndErrorRetryForcesReload() = withKiosk { _, fragment ->
        complete(fragment, "initial")
        pull(fragment)
        await { fragment.loads.size == 2 }
        onMain { fragment.loads.last().result.onError(IOException("controlled kiosk failure")) }
        await { !fragment.loading() && fragment.isErrorPanelVisible }
        onMain {
            assertFalse(fragment.refresh().isRefreshing)
            assertTrue(fragment.requireView().findViewById<View>(R.id.error_retry_button).performClick())
        }
        // ErrorPanelHelper debounces real retry clicks for 300 ms before invoking reloadContent.
        await { fragment.loads.size == 3 }
        onMain {
            assertEquals(3, fragment.loads.size)
            assertTrue(fragment.loads.last().force)
        }
        complete(fragment, "retried")
    }

    @Test
    fun emptyKioskRefreshClearsSpinner() = withKiosk { _, fragment ->
        complete(fragment, "initial")
        pull(fragment)
        await { fragment.loads.size == 2 }
        complete(fragment)
        onMain {
            assertTrue(fragment.titles().isEmpty())
            assertFalse(fragment.refresh().isRefreshing)
        }
    }

    @Test
    fun kioskPullPreferenceDoesNotDisableExplicitReload() = withKiosk { _, fragment ->
        complete(fragment, "initial")
        onMain {
            preferences.edit().putBoolean(pullKey, false).commit()
            assertFalse(fragment.refresh().isEnabled)
        }
        pull(fragment)
        onMain {
            assertEquals(1, fragment.loads.size)
            fragment.reloadContent()
            assertEquals(2, fragment.loads.size)
            assertTrue(fragment.loads.last().force)
        }
        complete(fragment, "explicit")
        onMain {
            preferences.edit().putBoolean(pullKey, true).commit()
            assertTrue(fragment.refresh().isEnabled)
        }
    }

    @Test
    fun recreatedKioskViewHasOneRefreshCallbackAndClearsOldSpinner() = withKiosk { host, fragment ->
        complete(fragment, "initial")
        lateinit var oldRefresh: SwipeRefreshLayout
        onMain {
            oldRefresh = fragment.refresh()
            oldRefresh.isRefreshing = true
            host.supportFragmentManager.beginTransaction().detach(fragment).commitNow()
            assertFalse(oldRefresh.isRefreshing)
            host.supportFragmentManager.beginTransaction().attach(fragment).commitNow()
            assertNotSame(oldRefresh, fragment.refresh())
            assertEquals(1, fragment.loads.size)
        }
        instrumentation.waitForIdleSync()
        pull(fragment)
        await { fragment.loads.size == 2 }
        complete(fragment, "recreated")
        onMain { assertEquals(listOf(false, true), fragment.loads.map { it.force }) }
    }

    private fun withKiosk(test: (FragmentActivity, ControlledKiosk) -> Unit) {
        val context = instrumentation.targetContext
        val host = instrumentation.startActivitySync(
            Intent(context, AboutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as FragmentActivity
        lateinit var fragment: ControlledKiosk
        try {
            onMain {
                val container = FrameLayout(host).apply { id = View.generateViewId() }
                host.setContentView(container)
                fragment = ControlledKiosk()
                host.supportFragmentManager.beginTransaction().replace(container.id, fragment).commitNow()
            }
            test(host, fragment)
        } finally {
            onMain { host.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun complete(fragment: ControlledKiosk, vararg titles: String) {
        onMain { fragment.loads.last().result.onSuccess(info(*titles)) }
        await { !fragment.loading() && fragment.titles() == titles.toList() }
        instrumentation.waitForIdleSync()
    }

    private fun pull(fragment: ControlledKiosk) {
        await {
            val list = fragment.requireView().findViewById<RecyclerView>(R.id.items_list)
            val row = list.findViewHolderForAdapterPosition(0)?.itemView
            row != null && row.isShown && row.height > 0 && fragment.requireView().height > row.height
        }
        instrumentation.waitForIdleSync()
        onMain {
            val root = fragment.requireView()
            val list = root.findViewById<RecyclerView>(R.id.items_list)
            val row = requireNotNull(list.findViewHolderForAdapterPosition(0)).itemView
            val rowLocation = IntArray(2).also { row.getLocationInWindow(it) }
            val rootLocation = IntArray(2).also { root.getLocationInWindow(it) }
            val x = rowLocation[0] - rootLocation[0] + row.width / 2f
            val startY = rowLocation[1] - rootLocation[1] + row.height / 2f
            val distance = (root.height - startY) * 0.85f
            val down = SystemClock.uptimeMillis()
            for (i in 0..24) {
                val action = when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    24 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                MotionEvent.obtain(down, down + i * 16L, action, x, startY + distance * i / 24f, 0)
                    .also { root.dispatchTouchEvent(it); it.recycle() }
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        } while (SystemClock.uptimeMillis() < deadline)
        onMain { assertTrue("Kiosk callback did not reach the expected state", condition()) }
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun info(vararg titles: String): KioskInfo {
        val constructor = KioskInfo::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, ListLinkHandler::class.java, String::class.java
        ).apply { isAccessible = true }
        return constructor.newInstance(0,
            ListLinkHandler(URL, URL, "Recommended Lives", emptyList(), null), "fixture").apply {
            relatedItems = titles.map { title ->
                StreamInfoItem(0, "$URL/$title", title, StreamType.VIDEO_STREAM).apply {
                    uploaderName = "fixture uploader"
                    thumbnailUrl = ""
                }
            }
        }
    }

    class ControlledKiosk : KioskFragment() {
        data class Load(val force: Boolean, val result: SingleSubject<KioskInfo>)
        val loads = mutableListOf<Load>()

        init {
            kioskId = "Recommended Lives"
            setInitialData(0, URL, "fixture")
            setUseDefaultStateSaving(false)
            useAsFrontPage(true)
        }

        override fun loadResult(forceReload: Boolean): Single<KioskInfo> =
            SingleSubject.create<KioskInfo>().also { loads.add(Load(forceReload, it)) }

        fun refresh(): SwipeRefreshLayout = requireView().findViewById(R.id.swipe_refresh_layout)
        fun loading(): Boolean = isLoading.get()
        fun titles(): List<String> = infoListAdapter.itemsList.map { it.name }
    }

    companion object {
        private const val URL = "https://example.com/kiosk-refresh-fixture"
    }
}
