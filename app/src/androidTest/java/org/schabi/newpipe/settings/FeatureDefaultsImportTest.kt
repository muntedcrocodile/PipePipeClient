package org.schabi.newpipe.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.info_list.VideoSwipeActions
import org.schabi.newpipe.player.playqueue.PersistentQueueManager
import org.schabi.newpipe.streams.io.StoredFileHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.ObjectOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises serialized backup replacement and ZIP extraction without touching the live database. */
@RunWith(AndroidJUnit4::class)
class FeatureDefaultsImportTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var original: Map<String, *>
    private lateinit var home: File
    private lateinit var manager: ContentSettingsManager
    private lateinit var locator: NewPipeFileLocator

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        original = HashMap(prefs.all)
        home = File.createTempFile("feature-defaults", "", context.cacheDir)
        assertTrue(home.delete())
        assertTrue(home.mkdir())
        locator = NewPipeFileLocator(home)
        manager = ContentSettingsManager(locator)
        assertTrue(manager.ensureDbDirectoryExists())
    }

    @After
    fun tearDown() {
        // Restore every preference type through the same serialized-map boundary.
        ObjectOutputStream(locator.settings.outputStream()).use { it.writeObject(original) }
        manager.loadSharedPreferences(prefs)
        home.deleteRecursively()
    }

    private fun historyKey() = context.getString(R.string.enable_watch_history_key)

    private fun assertFeatures(enabled: Boolean) {
        assertEquals(enabled, PersistentQueueManager.isEnabled(context))
        assertEquals(enabled, VideoSwipeActions.isEnabled(context))
    }

    private fun setOptOuts() {
        assertTrue(
            prefs.edit().putBoolean("persistent_queue_enabled", false)
                .putBoolean("video_swipe_enabled", false)
                .putBoolean(historyKey(), false).commit()
        )
    }

    private fun backup(settings: Map<String, *>? = null): StoredFileHelper {
        val file = File(home, "backup.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("newpipe.db"))
            zip.write(byteArrayOf(1, 2, 3)) // Only extraction is tested, never opened as SQLite.
            zip.closeEntry()
            if (settings != null) {
                val bytes = ByteArrayOutputStream()
                ObjectOutputStream(bytes).use { it.writeObject(settings) }
                zip.putNextEntry(ZipEntry("newpipe.settings"))
                zip.write(bytes.toByteArray())
                zip.closeEntry()
            }
        }
        return StoredFileHelper(context, null, Uri.fromFile(file), null)
    }

    private fun importSettings(settings: Map<String, *>) {
        val file = backup(settings)
        assertTrue(manager.extractDb(file))
        assertTrue(manager.extractSettings(file))
        manager.loadSharedPreferences(prefs)
    }

    @Test
    fun missingKeysEnableFeaturesBeforeAndAfterStartupWithoutChangingHistoryOptOut() {
        assertTrue(
            prefs.edit().remove("persistent_queue_enabled").remove("video_swipe_enabled")
                .remove("persistent_queue_visible").remove("persistent_queue_history_depth")
                .remove("video_swipe_left_action").remove("video_swipe_right_action")
                .putBoolean(historyKey(), false).commit()
        )
        assertFeatures(true)
        NewPipeSettings.initSettings(context)
        assertFeatures(true)
        assertTrue(prefs.getBoolean("persistent_queue_enabled", false))
        assertTrue(prefs.getBoolean("video_swipe_enabled", false))
        assertFalse(prefs.getBoolean(historyKey(), true))
        assertTrue(prefs.getBoolean("persistent_queue_visible", false))
        assertEquals("20", prefs.getString("persistent_queue_history_depth", null))
        assertEquals("enqueue_next", prefs.getString("video_swipe_left_action", null))
        assertEquals("enqueue", prefs.getString("video_swipe_right_action", null))
    }

    @Test
    fun legacySettingsReplaceCurrentOptOutsAndMissingFeatureKeysUseEnabledDefaults() {
        setOptOuts()
        importSettings(hashMapOf(historyKey() to false, "local_playlist_swipe_action" to "disable"))
        // Import replaces rather than merges: the previous explicit feature opt-outs are gone.
        assertFalse(prefs.contains("persistent_queue_enabled"))
        assertFalse(prefs.contains("video_swipe_enabled"))
        assertFeatures(true)
        NewPipeSettings.initSettings(context)
        assertFeatures(true)
        assertFalse(prefs.getBoolean(historyKey(), true))
    }

    @Test
    fun explicitImportedOptOutsSurviveStartup() {
        importSettings(
            hashMapOf(
                "persistent_queue_enabled" to false, "video_swipe_enabled" to false,
                historyKey() to false, "persistent_queue_visible" to false,
                "persistent_queue_history_depth" to "0", "video_swipe_left_action" to "none"
            )
        )
        assertFeatures(false)
        NewPipeSettings.initSettings(context)
        assertFeatures(false)
        assertFalse(prefs.getBoolean(historyKey(), true))
        assertFalse(prefs.getBoolean("persistent_queue_visible", true))
        assertEquals("0", prefs.getString("persistent_queue_history_depth", null))
        assertEquals("none", prefs.getString("video_swipe_left_action", null))
    }

    @Test
    fun databaseOnlyImportPreservesCurrentPreferencesIncludingOptOuts() {
        setOptOuts()
        val before = HashMap(prefs.all)
        val file = backup()
        assertTrue(manager.extractDb(file))
        assertFalse(manager.extractSettings(file))
        // BackupSettingsFragment skips loadSharedPreferences when no settings entry is found.
        assertEquals(before, prefs.all)
        NewPipeSettings.initSettings(context)
        assertFeatures(false)
        assertFalse(prefs.getBoolean(historyKey(), true))
    }
}
