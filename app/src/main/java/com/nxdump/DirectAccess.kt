package com.nxdump

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.nxdump.core.Dumper
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream

/** A sink that knows the name of the fresh top-level folder it wrote into. */
interface NamedSink : Dumper.Sink { val topName: String }

/** Plain-file access to shared storage. Needs "All files access"; SAF is far slower per file/folder. */
object DirectAccess {
    fun granted(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    fun requestIntent(ctx: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun fallbackIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** The real directory behind an external-storage SAF tree (internal storage, SD card, USB), or null. */
    fun resolve(tree: Uri): File? = try {
        if (tree.authority != "com.android.externalstorage.documents") null else {
            val id = DocumentsContract.getTreeDocumentId(tree)           // "primary:Download/x" or "1234-ABCD:x"
            val vol = id.substringBefore(':')
            val rel = id.substringAfter(':', "")
            val base = if (vol == "primary") Environment.getExternalStorageDirectory() else File("/storage/$vol")
            (if (rel.isEmpty()) base else File(base, rel)).takeIf { it.isDirectory && it.canWrite() }
        }
    } catch (e: Exception) {
        null
    }
}

/** Writes extracted files with java.io.File into a fresh uniquely-named folder under [root]. */
class FileSink(root: File, wantedName: String) : NamedSink {
    override val topName: String
    private val top: File

    init {
        var name = wantedName
        var n = 2
        while (File(root, name).exists()) name = "$wantedName ($n)".also { n++ }
        topName = name
        top = File(root, name)
        if (!top.mkdirs() && !top.isDirectory) throw IOException("Cannot create \"$name\" in the output folder")
    }

    /** Names come from the archive, and unlike SAF nothing sanitises them for us: refuse anything that could escape. */
    private fun target(rel: String): File {
        for (seg in rel.split('/'))
            if (seg.isEmpty() || seg == "." || seg == ".." || seg.indexOf('\u0000') >= 0)
                throw IOException("Unsafe path in archive: \"$rel\"")
        return File(top, rel)
    }

    override fun prepare(relativePaths: List<String>, prog: Dumper.Progress) {
        val dirs = HashSet<String>()
        for (p in relativePaths) dirs.add(p.substringBeforeLast('/', ""))
        dirs.remove("")
        val total = dirs.size.toLong()
        prog.stage("Creating folders", 0, total)
        var i = 0L
        for (d in dirs) {
            if (prog.cancelled()) throw InterruptedIOException("Cancelled")
            val f = target(d + "/x").parentFile!!
            if (!f.isDirectory && !f.mkdirs() && !f.isDirectory) throw IOException("Cannot create folder \"$d\"")
            prog.stage("Creating folders", ++i, total)
        }
    }

    private fun parentOf(f: File, rel: String): File {
        val p = f.parentFile!!
        if (!p.isDirectory && !p.mkdirs() && !p.isDirectory) throw IOException("Cannot create folder for \"$rel\"")
        return p
    }

    /**
     * The top folder is fresh, so any existing file is a name clash (e.g. "A.bin" and "a.bin" on a case-insensitive
     * volume such as exFAT). Fail loudly instead of silently truncating the earlier file.
     */
    private fun createNew(f: File, rel: String): OutputStream = try {
        Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    } catch (e: FileAlreadyExistsException) {
        throw IOException("\"$rel\" already exists in the output (names that differ only by case clash on this storage)")
    }

    override fun createEmpty(relativePath: String) {
        val f = target(relativePath)
        parentOf(f, relativePath)
        createNew(f, relativePath).close()
    }

    override fun open(relativePath: String): OutputStream {
        val f = target(relativePath)
        parentOf(f, relativePath)
        return createNew(f, relativePath)
    }
}
