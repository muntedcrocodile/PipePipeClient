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
import org.schabi.newpipe.BuildConfig
import java.io.IOException
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

/** Synthetic responses parsed by the real RSS or full channel/tab extractors. */
class LargeFeedRefreshFixture(
    private val fullExtraction: Boolean = InstrumentationRegistry.getArguments()
        .getString("fullExtraction", "false") == "true",
) : AutoCloseable {
    val subscriptionCount = if (fullExtraction) 450 else 1000
    val requestsPerCycle get() = subscriptionCount * if (fullExtraction) 2 else 1
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
    private val savedUiPreferences = mutableMapOf<String, Any?>()
    private val arguments = InstrumentationRegistry.getArguments()
    private val entriesPerChannel = arguments.getString("entriesPerChannel", "20")!!.toInt()
        .also { require(it in 20..200) }
    private val databaseName = if (arguments.getString("diskDatabase", "false") == "true")
        "refresh-ui-synthetic-${System.nanoTime()}.db" else null
    private val database: AppDatabase

    init {
        check(context.packageName.endsWith(".queue.devicetest.refresh")) {
            "Run only in a fresh .queue.devicetest.refresh diagnostic installation"
        }
        val builder = if (databaseName == null)
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        else Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        database = builder.addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    // Match the production callback needed by the enabled persistent player queue.
                    if (databaseName != null) db.execSQL("PRAGMA synchronous=FULL")
                    PersistentQueueStore.createTable(db)
                }
            }).build()
        try {
        databaseField.set(null, database)
        Log.i("LargeFeedRefresh", "databaseMode=${if (databaseName == null) "memory" else "disk-FULL"} entriesPerChannel=$entriesPerChannel")
        check(preferences.edit().putBoolean(preferenceKey, !fullExtraction).commit())
        NewPipe.init(object : Downloader() {
            override fun executeAsync(request: Request, callback: AsyncCallback): CancellableCall {
                if (request.url() == "https://github.com/InfinityLoop1308/PipePipe/wiki/Announcement") {
                    // Keep the fresh-package diagnostic on the returning-user playback screen.
                    throw IOException("Announcement UI excluded from synthetic refresh diagnostic")
                }
                return previousDownloader.executeAsync(request, callback)
            }

            override fun postStreaming(
                url: String, headers: Map<String, List<String>>?, data: ByteArray?,
                localization: Localization?
            ) = previousDownloader.postStreaming(url, headers, data, localization)

            override fun execute(request: Request): Response {
                val url = request.url()
                val body = request.dataToSend()?.toString(Charsets.UTF_8).orEmpty()
                if (fullExtraction && url.contains("/youtubei/v1/browse") && body.contains("UC000000000000000000")) {
                    Thread.sleep(50)
                    val id = org.json.JSONObject(body).getString("browseId")
                    requests.incrementAndGet()
                    val json = fullChannelJson(id)
                    return Response(200, "OK", emptyMap(), json, json.toByteArray(), url)
                }
                if (!url.startsWith("https://www.youtube.com/feeds/videos.xml?channel_id=UC000000000000000000")) {
                    return previousDownloader.execute(request)
                }
                // Ensure the refresh overlaps several seconds of decoded playback.
                Thread.sleep(50)
                val id = url.substringAfter("channel_id=")
                val ordinal = id.takeLast(4).toInt()
                requests.incrementAndGet()
                val entries = (0 until entriesPerChannel).joinToString("") { entry ->
                    val video = "q" + (ordinal * entriesPerChannel + entry).toString().padStart(10, '0')
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

    private fun fullChannelJson(id: String): String {
        val ordinal = id.takeLast(4).toInt()
        val items = (0 until entriesPerChannel).joinToString(",") { entry ->
            val video = "q" + (ordinal * entriesPerChannel + entry).toString().padStart(10, '0')
            """{"gridVideoRenderer":{"videoId":"$video","title":{"simpleText":"Synthetic entry $entry"},
            "thumbnail":{"thumbnails":[{"url":"https://example.invalid/fixture.jpg","width":120,"height":90}]},
            "lengthText":{"simpleText":"1:00"},"publishedTimeText":{"simpleText":"1 minute ago"},
            "viewCountText":{"simpleText":"1 view"},"shortViewCountText":{"simpleText":"1 view"},
            "ownerText":{"runs":[{"text":"Synthetic channel","navigationEndpoint":{"browseEndpoint":{"browseId":"$id"}}}]}}}"""
        }
        return """{"header":{"c4TabbedHeaderRenderer":{"channelId":"$id","title":"Synthetic channel",
          "avatar":{"thumbnails":[]},"banner":{"thumbnails":[]},"badges":[],"subscriberCountText":{"simpleText":"1 subscriber"}}},
          "metadata":{"channelMetadataRenderer":{"title":"Synthetic channel","externalId":"$id","description":"Synthetic full extraction"}},
          "microformat":{"microformatDataRenderer":{"tags":[]}},
          "contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"selected":true,"title":"Videos",
          "endpoint":{"commandMetadata":{"webCommandMetadata":{"url":"/channel/$id/videos"}}},
          "content":{"sectionListRenderer":{"contents":[{"itemSectionRenderer":{"contents":[{"gridRenderer":{"items":[$items]}}]}}]}}}}]}}}"""
    }

    fun prepareMainActivity() {
        for (key in listOf("isFirstRun", "version_code", "last_show_donation_time", "default_resolution", "default_resolution_mobile")) {
            savedUiPreferences[key] = preferences.all[key]
        }
        check(preferences.edit().putInt("isFirstRun", 1)
            .putInt("version_code", BuildConfig.VERSION_CODE)
            .putLong("last_show_donation_time", System.currentTimeMillis())
            .putString("default_resolution", "1080p").putString("default_resolution_mobile", "1080p").commit())
    }

    fun seedSubscriptions(count: Int = subscriptionCount) {
        if (database.subscriptionDAO().rowCount().blockingFirst() == count.toLong()) return
        val subscriptions = (0 until count).map { index ->
            SubscriptionEntity().apply {
                serviceId = 0
                name = "Synthetic subscription $index"
                url = "https://www.youtube.com/channel/UC000000000000000000" + index.toString().padStart(4, '0')
            }
        }
        database.subscriptionDAO().insertAll(subscriptions)
        assertEquals(count.toLong(), database.subscriptionDAO().rowCount().blockingFirst())
        assertEquals(count, subscriptions.map { it.url }.distinct().size)
    }

    fun requestCount(): Int = requests.get()

    fun storedStreamCount(): Int = database.streamDAO().getAll().blockingFirst().size

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
        assertTrue("Missing parsed entries", results.all { it.value!!.streams.size == entriesPerChannel })
        assertEquals(1000 * entriesPerChannel, results.flatMap { it.value!!.streams }.map { it.url }.distinct().size)
        Log.i("LargeFeedRefresh", "subscriptions=1000 distinct=1000 entries=${1000 * entriesPerChannel} results=${results.size} elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
    }

    override fun close() {
        try {
            NewPipe.init(previousDownloader, localization, country)
        } finally {
            try {
                databaseField.set(null, previousDatabase)
            } finally {
                try {
                    try { database.close() } finally {
                        databaseName?.let {
                            // Room opens lazily; a fake-history-only check may never create its file.
                            val path = context.getDatabasePath(it)
                            if (path.exists()) check(context.deleteDatabase(it)) { "Could not remove synthetic database" }
                            check(!path.exists()) { "Synthetic database remained after cleanup" }
                        }
                    }
                } finally {
                    try {
                        val editor = preferences.edit()
                        if (hadPreference) editor.putBoolean(preferenceKey, oldPreference) else editor.remove(preferenceKey)
                        for ((key, value) in savedUiPreferences) {
                            when (value) {
                                is Int -> editor.putInt(key, value)
                                is Long -> editor.putLong(key, value)
                                is String -> editor.putString(key, value)
                                else -> editor.remove(key)
                            }
                        }
                        check(editor.commit()) { "Could not restore fixture preference" }
                    } finally {
                        FeedEventManager.reset()
                    }
                }
            }
        }
    }
}
