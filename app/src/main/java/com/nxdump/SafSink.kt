package com.nxdump

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.nxdump.core.Dumper
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

/** Writes extracted files into a SAF tree, inside a fresh uniquely-named top folder. */
/** createDocument latency, for the diag file: tells slow-but-moving from stuck. */
object SafStats {
    private val count = java.util.concurrent.atomic.AtomicLong()
    private val totalMs = java.util.concurrent.atomic.AtomicLong()
    private val maxMs = java.util.concurrent.atomic.AtomicLong()
    private val winCount = java.util.concurrent.atomic.AtomicLong()
    private val winMs = java.util.concurrent.atomic.AtomicLong()

    /** Per parent folder: creates so far plus the mean latency of each block of [BLOCK] creates (sibling-count theory). */
    private class PerDir { var n = 0L; var blockMs = 0L; val blocks = ArrayList<Long>() }
    private const val BLOCK = 2000
    private val perDir = HashMap<String, PerDir>()

    fun reset() { count.set(0); totalMs.set(0); maxMs.set(0); winCount.set(0); winMs.set(0); synchronized(perDir) { perDir.clear() } }
    fun record(dir: String, ms: Long) {
        count.incrementAndGet(); totalMs.addAndGet(ms); maxMs.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
        winCount.incrementAndGet(); winMs.addAndGet(ms)
        synchronized(perDir) {
            val d = perDir.getOrPut(dir) { PerDir() }
            d.n++; d.blockMs += ms
            if (d.n % BLOCK == 0L) { d.blocks.add(d.blockMs / BLOCK); d.blockMs = 0 }
        }
    }
    /** Mean latency since the previous call: the cumulative average hides a late slowdown. */
    fun window(): String { val n = winCount.getAndSet(0); val ms = winMs.getAndSet(0); return "win=${if (n > 0) ms / n else 0}ms/$n" }
    /** Folders with >= BLOCK creates: "path n=.. ms/2000-block=[a,b,..]". Rising numbers = create cost grows with siblings. */
    fun dirReport(): String = synchronized(perDir) {
        perDir.filter { it.value.blocks.isNotEmpty() }.entries.sortedByDescending { it.value.n }.take(4)
            .joinToString("") { (k, v) -> "  dir '$k' creates=${v.n} avgMsPer${BLOCK}=${v.blocks}\n" }
    }
    fun summary(): String { val n = count.get(); return "creates=$n avg=${if (n > 0) totalMs.get() / n else 0}ms max=${maxMs.get()}ms ${window()}" }
}

class SafSink(ctx: Context, private val tree: Uri, wantedName: String) : NamedSink {
    private val resolver = ctx.contentResolver
    private val dirs = HashMap<String, Uri>()
    private val lock = Any()
    override val topName: String

    private companion object { const val DIR_THREADS = 8 }

    init {
        SafStats.reset()
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var name = wantedName
        var n = 2
        while (findChild(root, name) != null) name = "$wantedName ($n)".also { n++ }
        topName = name
        dirs[""] = create(root, DocumentsContract.Document.MIME_TYPE_DIR, name, "<root>")
    }

    private fun findChild(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            }
        }
        return null
    }

    private fun create(parent: Uri, mime: String, name: String, dirKey: String): Uri {
        val t0 = SystemClock.elapsedRealtime()
        try {
            return DocumentsContract.createDocument(resolver, parent, mime, name)
                ?: throw IOException("Cannot create \"$name\" in the output folder")
        } finally {
            SafStats.record(dirKey, SystemClock.elapsedRealtime() - t0)
        }
    }

    private fun dir(path: String): Uri {
        dirs[path]?.let { return it }
        val parent = dir(path.substringBeforeLast('/', ""))
        val name = path.substringAfterLast('/')
        // Everything below the fresh top folder is ours, so a subfolder can't already exist: skip the
        // O(siblings) directory listing that findChild() would do for every new folder.
        val u = create(parent, DocumentsContract.Document.MIME_TYPE_DIR, name, path.substringBeforeLast('/', ""))
        dirs[path] = u
        return u
    }

    /**
     * Creates the whole folder tree up front. Folders at the same depth are independent once their parents
     * exist, so each depth level is created by several threads at once (the cost is IPC latency, not CPU).
     */
    override fun prepare(relativePaths: List<String>, prog: Dumper.Progress) {
        val wanted = HashSet<String>()
        for (p in relativePaths) {
            var d = p.substringBeforeLast('/', "")
            while (d.isNotEmpty() && wanted.add(d)) d = d.substringBeforeLast('/', "")
        }
        val t0 = SystemClock.elapsedRealtime()
        val total = wanted.size.toLong()
        prog.stage("Creating folders", 0, total)
        if (total == 0L) return
        val levels = wanted.groupBy { d -> d.count { it == '/' } }.toSortedMap()
        val pool = Executors.newFixedThreadPool(minOf(DIR_THREADS, wanted.size)) { r -> Thread(r, "nxdump-mkdir").apply { isDaemon = true } }
        val doneLock = Any()
        var done = 0L
        var open = true                     // closed on exit so a straggler task can't publish into a later extraction
        try {
            for ((_, level) in levels) {
                val fs = level.map { d ->
                    pool.submit {
                        if (prog.cancelled()) throw java.io.InterruptedIOException("Cancelled")
                        val parent = synchronized(lock) { dirs[d.substringBeforeLast('/', "")] } ?: throw IOException("Missing parent of $d")
                        val u = create(parent, DocumentsContract.Document.MIME_TYPE_DIR, d.substringAfterLast('/'), d.substringBeforeLast('/', ""))
                        synchronized(lock) { dirs[d] = u }
                        synchronized(doneLock) { if (open) { done++; prog.stage("Creating folders", done, total) } }
                    }
                }
                try {
                    fs.forEach { it.get() }
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            synchronized(doneLock) { open = false }
            pool.shutdownNow()
        }
        Log.i("SafSink", "prepared $total folders in ${levels.size} levels, ${SystemClock.elapsedRealtime() - t0}ms")
    }

    /** Empty files need no output stream: creating the document is enough (saves one IPC each). */
    override fun createEmpty(relativePath: String) {
        val d = synchronized(lock) { dir(relativePath.substringBeforeLast('/', "")) }
        create(d, "application/octet-stream", relativePath.substringAfterLast('/'), relativePath.substringBeforeLast('/', ""))
    }

    /** Called from several extraction threads: folder/file creation is serialised, the data writes are not. */
    override fun open(relativePath: String): OutputStream {
        val d = synchronized(lock) { dir(relativePath.substringBeforeLast('/', "")) }
        val t0 = SystemClock.elapsedRealtime()
        val f = create(d, "application/octet-stream", relativePath.substringAfterLast('/'), relativePath.substringBeforeLast('/', ""))
        val t1 = SystemClock.elapsedRealtime()
        val os = resolver.openOutputStream(f, "w") ?: throw IOException("Cannot write $relativePath")
        val t2 = SystemClock.elapsedRealtime()
        if (t2 - t0 > 500) Log.w("SafSink", "slow open $relativePath: create=${t1 - t0}ms openStream=${t2 - t1}ms")
        // Writers already hand us 1 MB chunks (passed straight through); a big buffer only costs 1 MB per tiny file.
        return BufferedOutputStream(os, 64 * 1024)
    }
}
