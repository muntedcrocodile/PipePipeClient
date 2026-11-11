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
            assertTrue("No partial extraction errors: ${update.errors.map { e -> e.javaClass.simpleName }}", update.errors.isEmpty())
            assertEquals("Synthetic full extraction", update.description)
            assertEquals(1L, update.subscriberCount)
            assertNull("Feed refresh must not retain channel extraction data in navigation cache",
                cache.getFromKey(0, url, InfoItem.InfoType.CHANNEL))
            assertNull("Feed refresh must not retain tab extraction data in navigation cache",
                cache.getFromKey(0, "$url/videos", InfoItem.InfoType.CHANNEL))
        }
    }
}
