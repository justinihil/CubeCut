/*
 * CubeCut - cuts a speedcubing session video into one clip per solve.
 * Copyright (C) 2026 DadCuber
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, version 3 of the License,
 * with the additional terms in NOTICE.md (GPL section 7): the author credit must stay in the app's
 * About window, and changed versions must be marked as changed.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program (the
 * LICENSE file). If not, see <https://www.gnu.org/licenses/>.
 */
package com.example.cubecut

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ---------------------------------------------------------------------------------------
// Version 3: the phone remembers your sessions.
//
// What is stored is NOT video. For every raw session video ("footage") the app keeps one small record
// (where the file is, what it is called, how long it is, the buffer settings) and one small record per cut
// (the same five things a CutItem already holds). The clips themselves are worked out from the cuts by
// computeClips() whenever they are needed, so 1000 solves are only a few dozen KB.
//
// Plain Android SQLite is used on purpose (no extra libraries, nothing to add to the Gradle files).
// The database has a version number; when a later version of CubeCut needs more columns, it is upgraded in
// Helper.onUpgrade() without losing anything.
// ---------------------------------------------------------------------------------------

/** One raw session video the app knows about. */
class Footage(
    val id: Long,
    val uri: String,              // the permanent link to the video (see takePersistableUriPermission in MainActivity)
    val displayName: String,      // the file name, e.g. "20261102_101530.mp4"
    val sizeBytes: Long,
    val durationMs: Long,
    val title: String,            // what the app shows; starts as the file name without ".mp4"
    val dateTakenMs: Long,        // when the session was filmed (0 = unknown)
    val addedMs: Long,            // when it was first imported
    val lastScanMs: Long,         // when Find solves last ran on it (0 = never)
    val beforePad: Float,         // the buffer settings used for this session
    val afterPad: Float,
    val scrambleAll: Boolean,
    val cutCount: Int,            // how many cuts are saved for it
    val includedCount: Int        // how many of them are switched on
)

/** What opening a video gave: its record, and the cuts saved earlier (empty for a video seen for the first time). */
class OpenedSession(val footage: Footage, val cuts: List<CutItem>, val isNew: Boolean)

/** "Nov 2, 2026" in the phone's own style. [ms] <= 0 gives "date unknown". */
fun formatDate(ms: Long): String =
    if (ms <= 0L) "date unknown" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

object SessionStore {

    private const val DB_NAME = "cubecut_sessions.db"
    private const val DB_VERSION = 1

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)   // deleting a footage record deletes its cuts too
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE footage (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "uri TEXT NOT NULL, " +
                        "display_name TEXT NOT NULL, " +
                        "size_bytes INTEGER NOT NULL, " +
                        "duration_ms INTEGER NOT NULL, " +
                        "title TEXT NOT NULL, " +
                        "date_taken_ms INTEGER NOT NULL DEFAULT 0, " +
                        "added_ms INTEGER NOT NULL, " +
                        "last_scan_ms INTEGER NOT NULL DEFAULT 0, " +
                        "before_pad REAL NOT NULL DEFAULT 2.5, " +
                        "after_pad REAL NOT NULL DEFAULT 2.5, " +
                        "scramble_all INTEGER NOT NULL DEFAULT 1)"
            )
            db.execSQL(
                "CREATE TABLE cuts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "footage_id INTEGER NOT NULL REFERENCES footage(id) ON DELETE CASCADE, " +
                        "stop_sec REAL NOT NULL, " +
                        "time_text TEXT NOT NULL, " +
                        "included INTEGER NOT NULL, " +
                        "note TEXT NOT NULL, " +
                        "include_scramble INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX cuts_by_footage ON cuts(footage_id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // nothing to upgrade yet. Later versions: add "ALTER TABLE ... ADD COLUMN ..." steps here, one per version.
        }
    }

    @Volatile private var helper: Helper? = null

    private fun db(context: Context): SQLiteDatabase {
        val h = helper ?: synchronized(this) {
            helper ?: Helper(context.applicationContext).also { helper = it }
        }
        return h.writableDatabase
    }

    // ---------------------------------------------------------------- the video file itself

    /** File name and size of the video behind [uri] (name "" / size -1 if the provider doesn't say). */
    private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = ""
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n) ?: ""
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
        }
        return Pair(name, size)
    }

    /** When the video was filmed: the date inside the file, else the file's "last modified", else 0 (unknown). */
    private fun dateTaken(context: Context, uri: Uri): Long {
        try {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val raw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                if (raw != null && raw.length >= 15) {
                    val f = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
                    f.timeZone = TimeZone.getTimeZone("UTC")
                    val d = f.parse(raw.substring(0, 15))
                    if (d != null && d.time > 0L) return d.time
                }
            } finally {
                r.release()
            }
        } catch (e: Exception) {
        }
        try {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) {
                    val t = c.getLong(0)
                    if (t > 0L) return t
                }
            }
        } catch (e: Exception) {
        }
        return 0L
    }

    /** True if the video can still be opened (it may have been deleted or moved). */
    fun canOpen(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** Asks Android to remember our permission to read this video (so the link still works after a restart). */
    fun keepAccess(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // some providers don't allow it; the video still works for this run
        }
    }

    private fun releaseAccess(context: Context, uri: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- opening a video

    /**
     * Called when a video is imported. Recognises a video the app has seen before (same file name, size and length;
     * a different link to the same file is fine and just replaces the stored link) and returns its saved cuts and settings.
     * A video seen for the first time gets a new record that starts with the current buffer settings.
     */
    fun openOrCreate(
        context: Context, uri: Uri, durationMs: Long,
        beforePad: Float, afterPad: Float, scrambleAll: Boolean
    ): OpenedSession {
        val (name, size) = nameAndSize(context, uri)
        val d = db(context)
        val where = if (name.isNotEmpty()) "display_name = ? AND size_bytes = ? AND duration_ms = ?" else "size_bytes = ? AND duration_ms = ?"
        val args = if (name.isNotEmpty()) arrayOf(name, size.toString(), durationMs.toString()) else arrayOf(size.toString(), durationMs.toString())
        var id = -1L
        d.query("footage", arrayOf("id"), where, args, null, null, "added_ms DESC", "1").use { c ->
            if (c.moveToFirst()) id = c.getLong(0)
        }
        if (id >= 0) {
            val values = ContentValues()
            values.put("uri", uri.toString())
            d.update("footage", values, "id = ?", arrayOf(id.toString()))
            return OpenedSession(getFootage(context, id)!!, loadCuts(context, id), false)
        }
        val shownName = if (name.isNotEmpty()) name else "Session video"
        val values = ContentValues()
        values.put("uri", uri.toString())
        values.put("display_name", shownName)
        values.put("size_bytes", size)
        values.put("duration_ms", durationMs)
        values.put("title", shownName.substringBeforeLast('.', shownName))
        values.put("date_taken_ms", dateTaken(context, uri))
        values.put("added_ms", System.currentTimeMillis())
        values.put("before_pad", beforePad.toDouble())
        values.put("after_pad", afterPad.toDouble())
        values.put("scramble_all", if (scrambleAll) 1 else 0)
        val newId = d.insertOrThrow("footage", null, values)
        return OpenedSession(getFootage(context, newId)!!, emptyList(), true)
    }

    // ---------------------------------------------------------------- reading

    private const val FOOTAGE_SELECT =
        "SELECT f.id, f.uri, f.display_name, f.size_bytes, f.duration_ms, f.title, f.date_taken_ms, f.added_ms, f.last_scan_ms, " +
                "f.before_pad, f.after_pad, f.scramble_all, " +
                "(SELECT COUNT(*) FROM cuts c WHERE c.footage_id = f.id), " +
                "(SELECT COUNT(*) FROM cuts c WHERE c.footage_id = f.id AND c.included = 1) " +
                "FROM footage f"

    private fun readFootage(c: android.database.Cursor) = Footage(
        id = c.getLong(0), uri = c.getString(1), displayName = c.getString(2), sizeBytes = c.getLong(3),
        durationMs = c.getLong(4), title = c.getString(5), dateTakenMs = c.getLong(6), addedMs = c.getLong(7),
        lastScanMs = c.getLong(8), beforePad = c.getFloat(9), afterPad = c.getFloat(10), scrambleAll = c.getInt(11) == 1,
        cutCount = c.getInt(12), includedCount = c.getInt(13)
    )

    fun getFootage(context: Context, id: Long): Footage? =
        db(context).rawQuery("$FOOTAGE_SELECT WHERE f.id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) readFootage(c) else null
        }

    /** Every known session, newest first (by the date it was filmed, else by when it was imported). */
    fun listFootage(context: Context): List<Footage> =
        db(context).rawQuery(
            "$FOOTAGE_SELECT ORDER BY CASE WHEN f.date_taken_ms > 0 THEN f.date_taken_ms ELSE f.added_ms END DESC", null
        ).use { c ->
            val out = ArrayList<Footage>()
            while (c.moveToNext()) out.add(readFootage(c))
            out
        }

    fun loadCuts(context: Context, footageId: Long): List<CutItem> =
        db(context).query(
            "cuts", arrayOf("stop_sec", "time_text", "included", "note", "include_scramble"),
            "footage_id = ?", arrayOf(footageId.toString()), null, null, "stop_sec ASC, id ASC"
        ).use { c ->
            val out = ArrayList<CutItem>()
            while (c.moveToNext()) {
                out.add(CutItem(c.getDouble(0), c.getString(1), c.getInt(2) == 1, c.getString(3), c.getInt(4) == 1))
            }
            out
        }

    // ---------------------------------------------------------------- writing

    /** Saves the whole current state of one session (cuts and settings) in one go, all or nothing. */
    fun saveSession(context: Context, footageId: Long, cuts: List<CutItem>, beforePad: Float, afterPad: Float, scrambleAll: Boolean) {
        val d = db(context)
        d.beginTransaction()
        try {
            val settings = ContentValues()
            settings.put("before_pad", beforePad.toDouble())
            settings.put("after_pad", afterPad.toDouble())
            settings.put("scramble_all", if (scrambleAll) 1 else 0)
            d.update("footage", settings, "id = ?", arrayOf(footageId.toString()))
            d.delete("cuts", "footage_id = ?", arrayOf(footageId.toString()))
            for (cut in cuts) {
                val v = ContentValues()
                v.put("footage_id", footageId)
                v.put("stop_sec", cut.stopSec)
                v.put("time_text", cut.timeText)
                v.put("included", if (cut.included) 1 else 0)
                v.put("note", cut.note)
                v.put("include_scramble", if (cut.includeScramble) 1 else 0)
                d.insert("cuts", null, v)
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Remembers that Find solves has just run on this session. */
    fun markScanned(context: Context, footageId: Long) {
        val v = ContentValues()
        v.put("last_scan_ms", System.currentTimeMillis())
        db(context).update("footage", v, "id = ?", arrayOf(footageId.toString()))
    }

    /** Forgets a session and its cuts. The video file itself is never touched. */
    fun delete(context: Context, footage: Footage) {
        db(context).delete("footage", "id = ?", arrayOf(footage.id.toString()))
        releaseAccess(context, footage.uri)
    }
}