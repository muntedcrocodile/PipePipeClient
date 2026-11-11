package org.schabi.newpipe.local.feed.service

import android.util.Log
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import androidx.preference.PreferenceManager
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.AppDatabase
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.CancellableCall
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.player.playqueue.PersistentQueueStore
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounded repro fixture, never a claim that synthetic RSS reproduces full channel extraction. */
class LargeFeedRefreshFixture : AutoCloseable {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseField = NewPipeDatabase::class.java.getDeclaredField("databaseInstance")
        .apply { isAccessible = true }
    private val previousDatabase = databaseField.get(null)
    private val previousDownloader = NewPipe.getDownloader()
    private val localization = NewPipe.getPreferredLocalization()
    private val country = NewPipe.getPreferredContentCountry()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val preferenceKey = context.getString(R.string.feed_use_dedicated_fetch_method_key)
    private val hadPreference = preferences.contains(preferenceKey)
    private val oldPreference = preferences.getBoolean(preferenceKey, false)
    private val requests = AtomicInteger()
    private val database: AppDatabase

    init {
        check(context.packageName.endsWith(".queue.devicetest.refresh")) {
            "Run only in a fresh .queue.devicetest.refresh diagnostic installation"
        }
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Match the production callback needed by the enabled persistent player queue.
                    PersistentQueueStore.createTable(db)
                }
            }).build()
        try {
        databaseField.set(null, database)
        check(preferences.edit().putBoolean(preferenceKey, true).commit())
        NewPipe.init(object : Downloader() {
            override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall =
                previousDownloader.executeAsync(request, callback)

            override fun postStreaming(
                url: String, headers: Map<String, List<String>>?, data: ByteArray?,
                localization: Localization?
            ) = previousDownloader.postStreaming(url, headers, data, localization)

            override fun execute(request: Request): Response {
                val url = request.url()
                if (!url.startsWith("https://www.youtube.com/feeds/videos.xml?channel_id=UC000000000000000000")) {
                    return previousDownloader.execute(request)
                }
                // Ensure the refresh overlaps several seconds of decoded playback.
                Thread.sleep(50)
                val id = url.substringAfter("channel_id=")
                val ordinal = id.takeLast(4).toInt()
                requests.incrementAndGet()
                val entries = (0 until 20).joinToString("") { entry ->
                    val video = "q" + (ordinal * 20 + entry).toString().padStart(10, '0')
                    """<entry><title>Synthetic entry $entry</title>
                    <link href="https://www.youtube.com/watch?v=$video"/>
                    <author><name>Synthetic channel</name><uri>https://www.youtube.com/channel/$id</uri></author>
                    <published>${OffsetDateTime.now().minusMinutes(1)}</published>
                    <media:group><media:thumbnail url="https://example.invalid/fixture.jpg"/>
                    <media:community><media:statistics views="1"/></media:community></media:group></entry>"""
                }
                val xml = """<feed xmlns="http://www.w3.org/2005/Atom"
                    xmlns:media="http://search.yahoo.com/mrss/">
                    <author><name>Synthetic channel</name><uri>https://www.youtube.com/channel/$id</uri></author>
                    $entries</feed>"""
                return Response(200, "OK", emptyMap(), xml, xml.toByteArray(), url)
            }
        }, localization, country)
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun seedSubscriptions() {
        if (database.subscriptionDAO().rowCount().blockingFirst() == 1000L) return
        val subscriptions = (0 until 1000).map { index ->
            SubscriptionEntity().apply {
                serviceId = 0
                name = "Synthetic subscription $index"
                url = "https://www.youtube.com/channel/UC000000000000000000" + index.toString().padStart(4, '0')
            }
        }
        database.subscriptionDAO().insertAll(subscriptions)
        assertEquals(1000L, database.subscriptionDAO().rowCount().blockingFirst())
        assertEquals(1000, subscriptions.map { it.url }.distinct().size)
    }

    fun requestCount(): Int = requests.get()

    fun refresh() {
        seedSubscriptions()
        val started = android.os.SystemClock.elapsedRealtime()
        val heartbeatFailure = AtomicReference<String>()
        val heartbeatSamples = AtomicInteger()
        val main = Handler(Looper.getMainLooper())
        val monitor = Executors.newSingleThreadScheduledExecutor()
        val heartbeat = monitor.scheduleWithFixedDelay({
            val acknowledged = CountDownLatch(1)
            main.post { acknowledged.countDown() }
            if (!acknowledged.await(1500, TimeUnit.MILLISECONDS)) {
                heartbeatFailure.compareAndSet(null, "Main looper unresponsive for 1500ms")
            }
            heartbeatSamples.incrementAndGet()
        }, 0, 500, TimeUnit.MILLISECONDS)
        val results = try {
            FeedLoadManager(context).startLoading(ignoreOutdatedThreshold = true)
                .timeout(180, TimeUnit.SECONDS).blockingGet()
        } finally {
            heartbeat.cancel(true)
            monitor.shutdownNow()
            check(monitor.awaitTermination(3, TimeUnit.SECONDS)) { "Heartbeat did not stop" }
        }
        assertEquals(null, heartbeatFailure.get())
        assertTrue("Expected repeated main-looper samples", heartbeatSamples.get() >= 4)
        assertEquals(1000, requests.get())
        assertEquals(1000, results.size)
        assertTrue("Synthetic RSS extraction failed", results.all { it.isOnNext })
        assertTrue("Extraction reported partial errors", results.all { it.value!!.errors.isEmpty() })
        assertTrue("Missing parsed entries", results.all { it.value!!.streams.size == 20 })
        assertEquals(20000, results.flatMap { it.value!!.streams }.map { it.url }.distinct().size)
        Log.i("LargeFeedRefresh", "subscriptions=1000 distinct=1000 entries=20000 results=${results.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
    }

    override fun close() {
        try {
            NewPipe.init(previousDownloader, localization, country)
        } finally {
            try {
                databaseField.set(null, previousDatabase)
            } finally {
                try {
                    database.close()
                } finally {
                    try {
                        val editor = preferences.edit()
                        if (hadPreference) editor.putBoolean(preferenceKey, oldPreference) else editor.remove(preferenceKey)
                        check(editor.commit()) { "Could not restore fixture preference" }
                    } finally {
                        FeedEventManager.reset()
                    }
                }
            }
        }
    }
}
