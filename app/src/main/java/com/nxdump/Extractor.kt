package com.nxdump

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nxdump.core.Container
import com.nxdump.core.Dumper
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.IOException
import java.io.InterruptedIOException

data class DlcRef(val uri: String, val name: String, val titleId: Long)

data class Request(
    val appId: Long, val title: String,
    val baseUri: String, val baseName: String,
    val updateUri: String?, val updateName: String?,
    val exefs: Boolean, val romfs: Boolean,
    val dlc: List<DlcRef> = emptyList(),
    val jobs: Int = 1,
)

enum class Phase { IDLE, RUNNING, DONE, FAILED, CANCELLED }

data class ExtractState(
    val phase: Phase = Phase.IDLE, val title: String = "", val file: String = "",
    val done: Long = 0, val total: Long = 0, val message: String? = null,
    /** Setup phase name ("Reading game data", "Creating folders"); empty once files are being copied. */
    val stage: String = "Starting\u2026", val stageDone: Long = 0, val stageTotal: Long = 0,
    val filesDone: Long = 0, val filesTotal: Long = 0,
    val bytesPerSec: Double = 0.0, val filesPerSec: Double = 0.0, val etaSec: Long = -1,
)

/** Exponentially smoothed throughput over ~1 s samples. */
class RateMeter {
    private var t0 = 0L; private var b0 = 0L; private var f0 = 0L
    var bytesPerSec = 0.0; private set
    var filesPerSec = 0.0; private set
    fun reset(now: Long, bytes: Long, files: Long) { t0 = now; b0 = bytes; f0 = files; bytesPerSec = 0.0; filesPerSec = 0.0 }
    fun sample(now: Long, bytes: Long, files: Long) {
        val dt = (now - t0) / 1000.0
        if (dt < 1.0) return
        val br = (bytes - b0) / dt; val fr = (files - f0) / dt
        bytesPerSec = if (bytesPerSec == 0.0) br else bytesPerSec * 0.6 + br * 0.4
        filesPerSec = if (filesPerSec == 0.0) fr else filesPerSec * 0.6 + fr * 0.4
        t0 = now; b0 = bytes; f0 = files
    }
}

object Extractor {
    val state = MutableStateFlow(ExtractState())
    @Volatile var cancelFlag = false
    @Volatile var pending: Request? = null

    fun start(ctx: Context, r: Request) {
        if (state.value.phase == Phase.RUNNING) return
        pending = r
        cancelFlag = false
        state.value = ExtractState(Phase.RUNNING, r.title)
        ContextCompat.startForegroundService(ctx, Intent(ctx, ExtractService::class.java))
    }

    fun cancel() { cancelFlag = true }
    fun dismiss() { if (state.value.phase != Phase.RUNNING) state.value = ExtractState() }
}

class ExtractService : Service() {
    private val channelId = "extract"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val req = Extractor.pending
        if (req == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Extraction", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, 1, notification(req.title, "Preparing\u2026", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        Thread { run(req, nm) }.start()
        return START_NOT_STICKY
    }

    private fun notification(title: String, text: String, pct: Int, indeterminate: Boolean, ongoing: Boolean = true): Notification =
        Notification.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(text).setOnlyAlertOnce(true).setOngoing(ongoing)
            .apply { if (ongoing) setProgress(100, pct, indeterminate) }
            .build()

    /** Samples throughput every 5 s and dumps worker stacks when progress stalls; output: Android/data/com.nxdump/files/nxdump-diag.txt */
    private fun startDiag(ctx: Context, mode: String): Thread {
        val file = java.io.File(ctx.getExternalFilesDir(null), "nxdump-diag.txt")
        // Diagnostics are best-effort: a full disk or missing dir must never fail or crash an extraction.
        val enabled = try { file.writeText("diag start ${java.util.Date()} output=$mode\n"); true } catch (_: Exception) { false }
        val t = Thread {
            if (!enabled) return@Thread
            var last = 0L; var lastMoved = SystemClock.elapsedRealtime(); var lastDump = 0L
            val t0 = lastMoved
            try {
                while (true) {
                    Thread.sleep(5000)
                    val now = SystemClock.elapsedRealtime()
                    val st = Extractor.state.value
                    val rate = (st.done - last) / 5.0 / 1048576
                    if (st.done != last) lastMoved = now
                    last = st.done
                    val sb = StringBuilder("t=${(now - t0) / 1000}s done=${st.done / 1048576}MB/${st.total / 1048576}MB rate=" + "%.1f".format(rate) + "MB/s file=${st.file} stage='${st.stage}' ${st.stageDone}/${st.stageTotal} files=${st.filesDone}/${st.filesTotal} saf[${SafStats.summary()}]\n")
                    if (rate < 20) SafStats.dirReport().let { if (it.isNotEmpty()) sb.append(it) }
                    if (rate < 20 && now - lastDump > 20000) {
                        lastDump = now
                        sb.append("--- thread dump ---\n")
                        for ((th, stack) in Thread.getAllStackTraces())
                            if (th.name.startsWith("nxdump-") || th.name == "Thread-2" || th.name.startsWith("Thread-")) {
                                sb.append("[${th.name}] ${th.state}\n")
                                stack.take(14).forEach { sb.append("    at $it\n") }
                            }
                    }
                    file.appendText(sb.toString())
                    android.util.Log.i("NxDiag", sb.toString())
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                android.util.Log.w("NxDiag", "diagnostics disabled: $e")
            }
        }
        t.isDaemon = true; t.start()
        return t
    }

    private fun run(r: Request, nm: NotificationManager) {
        val ctx = applicationContext
        var phase = Phase.DONE
        var msg: String? = null
        try {
            val keys = KeyStore.load(ctx) ?: throw IOException("Keys are not set up")
            val tree = Prefs(ctx).outputTree?.let { Uri.parse(it) } ?: throw IOException("Output folder is not set")
            val tags = buildList { if (r.updateUri != null) add("update"); if (r.dlc.isNotEmpty()) add("DLC") }
            val folder = (r.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "game" }) +
                " [%016X]".format(r.appId) + if (tags.isEmpty()) "" else " + " + tags.joinToString(" + ")
            val direct = if (Prefs(ctx).directAccess && DirectAccess.granted()) DirectAccess.resolve(tree) else null
            val sink: NamedSink = if (direct != null) FileSink(direct, folder) else SafSink(ctx, tree, folder)
            val mode = if (direct != null) "direct files (${direct.path})" else "SAF"
            android.util.Log.i("NxDump", "output via $mode")
            msg = sink.topName
            var lastUi = 0L
            var lastNotif = 0L
            val meter = RateMeter()
            val progress = object : Dumper.Progress {
                private val lock = Any()
                // Latest values from every callback, kept even when the publication throttle skips a push, so each
                // published snapshot is complete (a throttled file-total used to be lost, leaving the ETA blank).
                private var sStage = "Starting\u2026"; private var sStageDone = 0L; private var sStageTotal = 0L
                private var sFilesDone = 0L; private var sFilesTotal = 0L
                private var sFile = ""; private var sDone = 0L; private var sTotal = 0L

                private fun push(force: Boolean) {
                    val now = SystemClock.elapsedRealtime()
                    if (!force && now - lastUi < 150) return
                    lastUi = now
                    meter.sample(now, sDone, sFilesDone)
                    // ETA from files left / files per second (byte-based ETAs swing wildly with file size mix).
                    val eta = if (meter.filesPerSec > 0.05 && sFilesTotal > 0) ((sFilesTotal - sFilesDone) / meter.filesPerSec).toLong() else -1L
                    Extractor.state.value = Extractor.state.value.copy(
                        file = sFile, done = sDone, total = sTotal,
                        stage = sStage, stageDone = sStageDone, stageTotal = sStageTotal,
                        filesDone = sFilesDone, filesTotal = sFilesTotal,
                        bytesPerSec = meter.bytesPerSec, filesPerSec = meter.filesPerSec, etaSec = eta,
                    )
                    if (now - lastNotif > 1000) {
                        lastNotif = now
                        if (sStage.isNotEmpty()) {
                            val pct = if (sStageTotal > 0) (sStageDone * 100 / sStageTotal).toInt() else 0
                            val what = if (sStageTotal > 0) "$sStage $sStageDone/$sStageTotal" else sStage
                            nm.notify(1, notification(r.title, what, pct, sStageTotal <= 0))
                        } else {
                            val pct = if (sTotal > 0) (sDone * 100 / sTotal).toInt() else 0
                            nm.notify(1, notification(r.title, "$pct%  \u00B7  $sFilesDone/$sFilesTotal files  \u00B7  " + "%.1f".format(meter.filesPerSec) + " files/s", pct, false))
                        }
                    }
                }

                override fun stage(name: String, done: Long, total: Long) = synchronized(lock) {
                    sStage = name; sStageDone = done; sStageTotal = total
                    if (name.isEmpty()) { meter.reset(SystemClock.elapsedRealtime(), 0, 0); sDone = 0; sFilesDone = 0 }
                    push(done >= total || name.isEmpty())
                }
                override fun files(done: Long, total: Long) = synchronized(lock) {
                    sFilesDone = done; sFilesTotal = total
                    push(done >= total)
                }
                override fun update(file: String, done: Long, total: Long) = synchronized(lock) {
                    sFile = file; sDone = done; sTotal = total
                    push(done >= total)
                }
                override fun cancelled() = Extractor.cancelFlag
            }
            val diag = startDiag(ctx, mode)
            // Each file is opened once, even when base / update / DLC live in the same bundle NSP.
            val opened = HashMap<String, Container>()
            fun openOnce(uri: String, name: String): Container =
                opened.getOrPut(uri) { openContainer(ctx, Uri.parse(uri), name) }
            try {
                val needGame = r.exefs || r.romfs
                val base = if (needGame) openOnce(r.baseUri, r.baseName) else null
                val upd = if (needGame) r.updateUri?.let { openOnce(it, r.updateName ?: "update") } else null
                val dlc = r.dlc.map { Dumper.Dlc(openOnce(it.uri, it.name), it.titleId) }
                Dumper.dump(base, upd, r.appId, r.exefs, r.romfs, dlc, keys, sink, progress, r.jobs)
            } finally {
                diag.interrupt()
                opened.values.forEach { try { it.close() } catch (_: Exception) { } }
            }
        } catch (e: InterruptedIOException) {
            phase = Phase.CANCELLED; msg = "Cancelled. Partial files were left in the output folder."
        } catch (e: Throwable) {
            phase = Phase.FAILED
            // Core errors already read well; anything else gets its class so odd provider messages are traceable.
            msg = if (e is com.nxdump.core.NxException) (e.message ?: e.toString())
                  else "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
            val trace = android.util.Log.getStackTraceString(e)
            android.util.Log.e("NxDump", "extraction failed", e)
            try { java.io.File(ctx.getExternalFilesDir(null), "nxdump-diag.txt").appendText("\nFAILED: $msg\n$trace\n") } catch (_: Exception) { }
        }
        Extractor.state.value = Extractor.state.value.copy(phase = phase, message = msg)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val text = when (phase) { Phase.DONE -> "Extraction complete"; Phase.CANCELLED -> "Cancelled"; else -> "Failed: $msg" }
        nm.notify(2, notification(r.title, text, 0, false, ongoing = false))
        Extractor.pending = null
        stopSelf()
    }
}
