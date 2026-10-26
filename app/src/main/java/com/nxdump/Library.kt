package com.nxdump

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.nxdump.core.Analyzer
import com.nxdump.core.Container
import com.nxdump.core.Keys
import com.nxdump.core.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException

data class LibFile(val uri: String, val name: String, val size: Long, val modified: Long)

data class Entry(
    val uri: String, val fileName: String, val size: Long, val modified: Long,
    val kind: Int, val titleId: Long, val appId: Long, val version: Long,
    val name: String, val displayVersion: String, val hasIcon: Boolean, val error: String?,
)

data class Game(val appId: Long, val base: Entry, val updates: List<Entry>, val dlcCount: Int)

fun openContainer(ctx: Context, uri: Uri, name: String): Container {
    val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $name")
    val fis = FileInputStream(pfd.fileDescriptor)
    val src = Source.OfChannel(fis.channel, Closeable { fis.close(); pfd.close() })
    try {
        return Container.open(src, name, src)
    } catch (e: Exception) {
        src.close()
        throw e
    }
}

object Library {
    private val EXT = listOf(".nsp", ".xci", ".nsz", ".xcz")

    fun iconFile(ctx: Context, titleId: Long): File =
        File(File(ctx.filesDir, "icons").apply { mkdirs() }, "%016x.jpg".format(titleId))

    fun treeName(ctx: Context, tree: String): String? = try {
        val t = Uri.parse(tree)
        val doc = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    fun listTree(ctx: Context, tree: Uri, maxDepth: Int = 4): List<LibFile> {
        val out = ArrayList<LibFile>()
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        fun walk(docId: String, depth: Int) {
            val dirs = ArrayList<String>()
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            dirs.add(id)
                        } else if (EXT.any { name.endsWith(it, ignoreCase = true) }) {
                            val u = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                            out.add(LibFile(u.toString(), name, c.getLong(3), c.getLong(4)))
                        }
                    }
                }
            } catch (e: Exception) {
            }
            if (depth < maxDepth) dirs.forEach { walk(it, depth + 1) }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return out
    }

    fun stat(ctx: Context, uri: Uri): LibFile? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) LibFile(uri.toString(), c.getString(0) ?: "file", c.getLong(1), 0L) else null
            }
    } catch (e: Exception) {
        null
    }

    fun scan(
        ctx: Context, files: List<LibFile>, keys: Keys, cache: List<Entry>,
        cancelled: () -> Boolean, progress: (Int, Int) -> Unit,
    ): List<Entry> {
        val byUri = cache.groupBy { it.uri }
        val out = ArrayList<Entry>()
        files.forEachIndexed { i, f ->
            if (cancelled()) return out
            progress(i, files.size)
            val hit = byUri[f.uri]?.takeIf { l -> l.isNotEmpty() && l.all { it.size == f.size && it.modified == f.modified } }
            if (hit != null) out.addAll(hit) else out.addAll(analyze(ctx, f, keys))
        }
        progress(files.size, files.size)
        return out
    }

    private fun analyze(ctx: Context, f: LibFile, keys: Keys): List<Entry> = try {
        openContainer(ctx, Uri.parse(f.uri), f.name).use { c ->
            Analyzer.analyze(c, keys).map { i ->
                val icon: ByteArray? = i.icon
                if (icon != null) try { iconFile(ctx, i.titleId).writeBytes(icon) } catch (e: Exception) { }
                val kind = if (i.error != null && i.titleId == 0L) -1 else i.kind
                Entry(
                    f.uri, f.name, f.size, f.modified, kind, i.titleId, i.appId, i.version,
                    i.name.ifBlank { f.name.substringBeforeLast('.') }, i.displayVersion, icon != null, i.error,
                )
            }
        }
    } catch (e: Exception) {
        listOf(Entry(f.uri, f.name, f.size, f.modified, -1, 0, 0, 0, f.name, "", false, e.message ?: "Unreadable file"))
    }

    fun group(entries: List<Entry>): List<Game> {
        val ok = entries.filter { it.kind >= 0 && it.error == null }
        return ok.filter { it.kind == Analyzer.BASE }.distinctBy { it.appId }.map { b ->
            Game(
                b.appId, b,
                ok.filter { it.kind == Analyzer.UPDATE && it.appId == b.appId }.sortedByDescending { it.version },
                ok.count { it.kind == Analyzer.DLC && it.appId == b.appId },
            )
        }.sortedBy { it.base.name.lowercase() }
    }

    // ---- cache -------------------------------------------------------------------------------
    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "library.json")
    fun clearCache(ctx: Context) { cacheFile(ctx).delete() }

    fun loadCache(ctx: Context): List<Entry> = try {
        val a = JSONArray(cacheFile(ctx).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Entry(
                o.getString("uri"), o.getString("file"), o.getLong("size"), o.getLong("mod"), o.getInt("kind"),
                o.getString("tid").toULong(16).toLong(), o.getString("app").toULong(16).toLong(), o.getLong("ver"),
                o.getString("name"), o.getString("dv"), o.getBoolean("icon"), if (o.isNull("err")) null else o.getString("err"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveCache(ctx: Context, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { e ->
            a.put(JSONObject().put("uri", e.uri).put("file", e.fileName).put("size", e.size).put("mod", e.modified)
                .put("kind", e.kind).put("tid", "%016x".format(e.titleId)).put("app", "%016x".format(e.appId))
                .put("ver", e.version).put("name", e.name).put("dv", e.displayVersion).put("icon", e.hasIcon)
                .put("err", e.error ?: JSONObject.NULL))
        }
        try { cacheFile(ctx).writeText(a.toString()) } catch (e: Exception) { }
    }
}
