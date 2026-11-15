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
)

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
            val sink = SafSink(ctx, tree, folder)
            msg = sink.topName
            var lastUi = 0L
            var lastNotif = 0L
            val progress = object : Dumper.Progress {
                override fun update(file: String, done: Long, total: Long) {
                    val now = SystemClock.elapsedRealtime()
                    if (done < total && now - lastUi < 150) return
                    lastUi = now
                    Extractor.state.value = Extractor.state.value.copy(file = file, done = done, total = total)
                    if (now - lastNotif > 1000) {
                        lastNotif = now
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        nm.notify(1, notification(r.title, "$pct%  \u00B7  $file", pct, false))
                    }
                }
                override fun cancelled() = Extractor.cancelFlag
            }
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
                opened.values.forEach { try { it.close() } catch (_: Exception) { } }
            }
        } catch (e: InterruptedIOException) {
            phase = Phase.CANCELLED; msg = "Cancelled. Partial files were left in the output folder."
        } catch (e: Throwable) {
            phase = Phase.FAILED; msg = e.message ?: e.toString()
        }
        Extractor.state.value = Extractor.state.value.copy(phase = phase, message = msg)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val text = when (phase) { Phase.DONE -> "Extraction complete"; Phase.CANCELLED -> "Cancelled"; else -> "Failed: $msg" }
        nm.notify(2, notification(r.title, text, 0, false, ongoing = false))
        Extractor.pending = null
        stopSelf()
    }
}
