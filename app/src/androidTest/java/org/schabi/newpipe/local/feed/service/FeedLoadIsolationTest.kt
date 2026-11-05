package org.schabi.newpipe.local.feed.service

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Notification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

/** Actual service lookup and YouTube RSS extraction through the production per-channel boundary. */
@RunWith(AndroidJUnit4::class)
class FeedLoadIsolationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var previousDownloader: Downloader
    private lateinit var previousLocalization: Localization
    private lateinit var previousCountry: ContentCountry
    private lateinit var manager: FeedLoadManager
    private lateinit var downloader: FixtureDownloader

    @Before
    fun setUp() {
        previousDownloader = NewPipe.getDownloader()
        previousLocalization = NewPipe.getPreferredLocalization()
        previousCountry = NewPipe.getPreferredContentCountry()
        downloader = FixtureDownloader()
        NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT)
        manager = FeedLoadManager(context)
        assertNull("This regression requires service 3 to be unavailable", ServiceList.getById(3))
    }

    @After
    fun tearDown() {
        NewPipe.init(previousDownloader, previousLocalization, previousCountry)
    }

    @Test
    fun missingServiceBeforeSupportedChannelDoesNotAbortRefresh() {
        assertMissingServiceIsLocal(listOf(missing(), supported()))
    }

    @Test
    fun missingServiceAfterSupportedChannelDoesNotDiscardSuccessfulResult() {
        assertMissingServiceIsLocal(listOf(supported(), missing()))
    }

    @Test
    fun extractionFailureKeepsItsSubscriptionIdentityAndAllowsFollowingChannel() {
        val unavailable = supported().apply { uid = 63; url = FAILED_URL }
        val results = load(listOf(unavailable, supported()))
        val error = results.single { it.isOnError }.error as FeedLoadService.RequestException
        assertEquals(63L, error.subscriptionId)
        assertEquals("0:$FAILED_URL", error.message)
        assertTrue(error.cause is ContentNotAvailableException)
        assertEquals(62L, results.single { it.isOnNext }.value!!.uid)
        assertEquals(2, downloader.requests)
    }

    private fun assertMissingServiceIsLocal(subscriptions: List<SubscriptionEntity>) {
        val results = load(subscriptions)
        assertEquals(2, results.size)
        val error = results.single { it.isOnError }.error as FeedLoadService.RequestException
        assertEquals(61L, error.subscriptionId)
        assertEquals("3:https://missing-service.example/channel", error.message)
        assertTrue(error.cause is ExtractionException)
        assertTrue(error.cause!!.message!!.contains("id = \"3\""))
        val update = results.single { it.isOnNext }.value!!
        assertEquals(62L, update.uid)
        assertEquals(0, update.serviceId)
        assertEquals("Supported channel", update.name)
        assertEquals("Supported feed entry", update.streams.single().name)
        assertTrue(update.errors.isEmpty())
        assertEquals(1, downloader.requests)
        // Failed subscriptions remain available for a later plugin installation/retry.
        val missing = subscriptions.single { it.serviceId == 3 }
        assertEquals(61L, missing.uid)
        assertEquals("Unavailable service subscription", missing.name)
        assertEquals("https://missing-service.example/channel", missing.url)
        assertFalse(results.any { it.isOnComplete })
    }

    private fun load(subscriptions: List<SubscriptionEntity>): List<Notification<FeedUpdateInfo>> =
        Flowable.fromIterable(subscriptions)
            .concatMap { subscription ->
                manager.loadSubscription(
                    subscription, true, true,
                    context.getSharedPreferences("feed-isolation-test", Context.MODE_PRIVATE),
                )
            }
            .toList().timeout(5, TimeUnit.SECONDS).blockingGet()

    private fun missing() = SubscriptionEntity().apply {
        uid = 61
        serviceId = 3
        name = "Unavailable service subscription"
        url = "https://missing-service.example/channel"
    }

    private fun supported() = SubscriptionEntity().apply {
        uid = 62
        serviceId = 0
        name = "Supported channel"
        url = CHANNEL_URL
    }

    private class FixtureDownloader : Downloader() {
        var requests = 0

        override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
            throw AssertionError("Unexpected asynchronous request in RSS feed regression")

        override fun execute(request: Request): Response {
            requests++
            val url = request.url()
            require(url.startsWith("https://www.youtube.com/feeds/videos.xml?channel_id="))
            if (url.endsWith(FAILED_URL.substringAfterLast('/'))) {
                return Response(404, "Not found", emptyMap(), "", byteArrayOf(), url)
            }
            val xml = """
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:yt="http://www.youtube.com/xml/schemas/2015"
                      xmlns:media="http://search.yahoo.com/mrss/">
                  <author><name>Supported channel</name><uri>$CHANNEL_URL</uri></author>
                  <entry><title>Supported feed entry</title>
                    <link href="https://www.youtube.com/watch?v=aqz-KE-bpKQ"/>
                    <author><name>Supported channel</name><uri>$CHANNEL_URL</uri></author>
                    <published>${OffsetDateTime.now().minusMinutes(1)}</published>
                    <media:group><media:thumbnail url="https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg"/>
                      <media:community><media:statistics views="1"/></media:community>
                    </media:group>
                  </entry>
                </feed>
            """.trimIndent()
            return Response(200, "OK", emptyMap(), xml, xml.toByteArray(), url)
        }
    }

    companion object {
        private const val CHANNEL_URL = "https://www.youtube.com/channel/UC16niRr50-MSBwiO3YDb3RA"
        private val FAILED_URL = "https://www.youtube.com/channel/UC" + "0".repeat(22)
    }
}
