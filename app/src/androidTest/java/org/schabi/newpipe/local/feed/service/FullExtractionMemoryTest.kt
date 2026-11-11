package org.schabi.newpipe.local.feed.service

import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.util.InfoCache

@RunWith(AndroidJUnit4::class)
class FullExtractionMemoryTest {
    @Test
    fun discardedResultsStillPersistStreamsAndRetainedResultsKeepNewVideoNotifications() {
        LargeFeedRefreshFixture(true).use { fixture ->
            fixture.seedSubscriptions(6)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val initial = FeedLoadManager(context).startLoading(
                ignoreOutdatedThreshold = true).blockingGet()
            assertEquals(6, initial.size)
            assertTrue(initial.all { it.isOnNext && it.value!!.newStreams.isNotEmpty() })
            // Manual refresh uses this mode: work must be consumed/stored despite no retained list.
            val discarded = FeedLoadManager(context).startLoading(
                ignoreOutdatedThreshold = true, retainResults = false).blockingGet()
            assertTrue(discarded.isEmpty())
            assertEquals(24, fixture.requestCount())
            val count = fixture.storedStreamCount()
            assertTrue(count >= 6 * 20)
            // The notification caller still gets complete per-channel results after storage.
            val retained = FeedLoadManager(context).startLoading(
                ignoreOutdatedThreshold = true).blockingGet()
            assertEquals(6, retained.size)
            assertTrue(retained.all { it.isOnNext && it.value!!.streams.isNotEmpty() })
            assertTrue(retained.all { it.value!!.newStreams.isEmpty() })
            assertEquals(count, fixture.storedStreamCount())
            assertEquals(36, fixture.requestCount())
        }
    }

    @Test
    fun fullChannelRefreshDoesNotRetainNavigationCacheEntries() {
        LargeFeedRefreshFixture(true).use {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val url = "https://www.youtube.com/channel/UC0000000000000000000000"
            val cache = InfoCache.getInstance()
            cache.removeInfo(0, url, InfoItem.InfoType.CHANNEL)
            cache.removeInfo(0, "$url/videos", InfoItem.InfoType.CHANNEL)
            val sub = SubscriptionEntity().apply { uid = 1; serviceId = 0; name = "Synthetic"; this.url = url }
            val result = FeedLoadManager(context).loadSubscription(sub, false, true,
                PreferenceManager.getDefaultSharedPreferences(context)).blockingFirst()
            assertTrue("Full channel extraction must succeed", result.isOnNext)
            val update = result.value!!
            assertTrue("Need nonempty parsed tab", update.streams.isNotEmpty())
            update.errors.take(3).forEach { error -> android.util.Log.e("FullExtractionFixture", "Synthetic parse error", error) }
            assertTrue("No partial extraction errors: ${update.errors.map { e -> e.message }}", update.errors.isEmpty())
            assertEquals("Synthetic full extraction", update.description)
            assertEquals(1L, update.subscriberCount)
            assertNull("Feed refresh must not retain channel extraction data in navigation cache",
                cache.getFromKey(0, url, InfoItem.InfoType.CHANNEL))
            assertNull("Feed refresh must not retain tab extraction data in navigation cache",
                cache.getFromKey(0, "$url/videos", InfoItem.InfoType.CHANNEL))
        }
    }
}
