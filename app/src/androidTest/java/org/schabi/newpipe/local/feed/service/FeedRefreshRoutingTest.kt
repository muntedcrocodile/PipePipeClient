package org.schabi.newpipe.local.feed.service

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentHostCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Notification
import io.reactivex.rxjava3.core.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.local.feed.FeedFragment

/** Captures real manual refresh intents and routes them through the service's loader boundary. */
@RunWith(AndroidJUnit4::class)
class FeedRefreshRoutingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun manualAllSubscriptionsRefreshBypassesOutdatedThreshold() {
        assertLoad(manualRefreshIntent(null), FeedGroupEntity.GROUP_ALL_ID, true)
    }

    @Test
    fun manualGroupRefreshForcesOnlyTheSelectedGroup() {
        assertLoad(manualRefreshIntent(73L), 73L, true)
    }

    @Test
    fun backgroundServiceRequestsKeepOutdatedThresholdByDefault() {
        for (group in listOf(null, 73L)) {
            for (explicitFalse in listOf(false, true)) {
                val intent = Intent(instrumentation.targetContext, FeedLoadService::class.java)
                group?.let { intent.putExtra(FeedLoadService.EXTRA_GROUP_ID, it) }
                if (explicitFalse) intent.putExtra(FeedLoadService.EXTRA_FORCE_REFRESH, false)
                assertLoad(intent, group ?: FeedGroupEntity.GROUP_ALL_ID, false)
            }
        }
    }

    private fun manualRefreshIntent(group: Long?): Intent {
        lateinit var intent: Intent
        instrumentation.runOnMainSync {
            val context = RecordingContext(instrumentation.targetContext)
            // Supply only a context host: no Activity, database, views or Android service is started.
            val host = object : FragmentHostCallback<Unit>(context, Handler(Looper.getMainLooper()), 0) {
                override fun onGetHost() = Unit
            }
            val fragment = FeedFragment()
            val hostField = Fragment::class.java.getDeclaredField("mHost").apply { isAccessible = true }
            hostField.set(fragment, host)
            try {
                group?.let {
                    FeedFragment::class.java.getDeclaredField("groupId")
                        .apply { isAccessible = true }.setLong(fragment, it)
                }
                // This is the production callback used by the refresh buttons and pull listener.
                fragment.reloadContent()
                assertEquals(1, context.started.size)
                intent = context.started.single()
                assertEquals(ComponentName(context, FeedLoadService::class.java), intent.component)
            } finally {
                hostField.set(fragment, null)
            }
        }
        return intent
    }

    private fun assertLoad(intent: Intent, expectedGroup: Long, expectedForce: Boolean) {
        val result = Single.just<List<Notification<FeedUpdateInfo>>>(emptyList())
        var calls = 0
        val routed = FeedLoadService.loadRequestedFeed(intent) { group, ignoreOutdatedThreshold ->
            calls++
            assertEquals(expectedGroup, group)
            assertEquals(expectedForce, ignoreOutdatedThreshold)
            result
        }
        assertEquals(1, calls)
        assertSame(result, routed)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        override fun startService(service: Intent): ComponentName? {
            started.add(service)
            return service.component
        }
    }
}
