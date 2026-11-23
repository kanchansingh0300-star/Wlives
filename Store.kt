package com.example.livewall

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.webkit.MimeTypeMap
import java.io.File
import java.util.UUID

/** Copies picked media into app-private storage so it keeps working offline, even if originals move. */
object Store {
    const val KEY_PAUSE = "pause_on_saver"
    const val KEY_INTERVAL = "interval_sec"
    private const val KEY_ORDER = "order"
    private const val KEY_REV = "rev"

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("livewall", Context.MODE_PRIVATE)

    private fun dir(c: Context) = File(c.filesDir, "media").also { it.mkdirs() }

    fun isVideo(f: File): Boolean =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?.startsWith("video/") == true

    fun list(c: Context): List<File> {
        val existing = dir(c).listFiles { f -> !f.name.endsWith(".part") }
            ?.associateBy { it.name } ?: emptyMap()
        val order = prefs(c).getString(KEY_ORDER, "")!!.split("\n").filter { it.isNotBlank() }
        val ordered = order.mapNotNull { existing[it] }
        val rest = existing.values.filter { it.name !in order }.sortedBy { it.lastModified() }
        return ordered + rest
    }

    fun add(c: Context, uri: Uri): Boolean {
        val mime = c.contentResolver.getType(uri) ?: return false
        if (!mime.startsWith("image/") && !mime.startsWith("video/")) return false
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return false
        val name = "${UUID.randomUUID()}.$ext"
        val part = File(dir(c), "$name.part")
        val out = File(dir(c), name)
        return try {
            c.contentResolver.openInputStream(uri)?.use { i ->
                part.outputStream().use { o -> i.copyTo(o) }
            } ?: return false
            part.renameTo(out)
            save(c)
            true
        } catch (e: Exception) {
            part.delete(); false
        }
    }

    fun remove(c: Context, f: File) {
        f.delete()
        save(c)
    }

    private fun save(c: Context) {
        prefs(c).edit()
            .putString(KEY_ORDER, list(c).joinToString("\n") { it.name })
            .putLong(KEY_REV, System.nanoTime()) // always notifies the wallpaper to reload
            .apply()
    }
}
