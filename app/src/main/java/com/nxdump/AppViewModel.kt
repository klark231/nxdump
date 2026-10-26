package com.nxdump

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { LIBRARY, SETTINGS }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()
    val prefs = Prefs(app)

    var setupDone by mutableStateOf(prefs.setupDone); private set
    var screen by mutableStateOf(Screen.LIBRARY)
    var keysSummary by mutableStateOf<String?>(null); private set
    var titleKeyCount by mutableIntStateOf(0); private set
    var gamesName by mutableStateOf<String?>(null); private set
    var outputName by mutableStateOf<String?>(null); private set
    var extraCount by mutableIntStateOf(0); private set

    var games by mutableStateOf<List<Game>>(emptyList()); private set
    var problems by mutableStateOf<List<Entry>>(emptyList()); private set
    var scanning by mutableStateOf(false); private set
    var scanDone by mutableIntStateOf(0); private set
    var scanTotal by mutableIntStateOf(0); private set
    var message by mutableStateOf<String?>(null)

    private var scanJob: Job? = null

    init {
        refreshState()
        if (setupDone) rescan()
    }

    private fun refreshState() {
        val keys = KeyStore.load(ctx)
        keysSummary = keys?.let { KeyStore.summary(it) }
        titleKeyCount = keys?.titleKeyCount() ?: 0
        gamesName = prefs.gamesTree?.let { Library.treeName(ctx, it) ?: "Selected folder" }
        outputName = prefs.outputTree?.let { Library.treeName(ctx, it) ?: "Selected folder" }
        extraCount = prefs.extraFiles.size
    }

    fun importKeys(uri: Uri, title: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val err = KeyStore.import(ctx, uri, title)
            message = err ?: if (title) "title.keys imported" else "prod.keys imported"
            refreshState()
            if (err == null) {
                Library.clearCache(ctx)
                if (setupDone) rescan()
            }
        }
    }

    private fun persist(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        try { ctx.contentResolver.takePersistableUriPermission(uri, flags) } catch (e: Exception) { }
    }

    fun setGamesFolder(uri: Uri) {
        persist(uri, false)
        prefs.gamesTree = uri.toString()
        refreshState()
        if (setupDone) rescan()
    }

    fun setOutputFolder(uri: Uri) {
        persist(uri, true)
        prefs.outputTree = uri.toString()
        refreshState()
    }

    fun clearGamesFolder() { prefs.gamesTree = null; refreshState(); rescan() }

    fun addFile(uri: Uri) {
        persist(uri, false)
        prefs.extraFiles = prefs.extraFiles + uri.toString()
        refreshState()
        rescan()
    }

    fun clearExtraFiles() { prefs.extraFiles = emptySet(); refreshState(); rescan() }

    fun finishSetup() {
        prefs.setupDone = true
        setupDone = true
        rescan()
    }

    fun rescan(force: Boolean = false) {
        val keys = KeyStore.load(ctx)
        if (keys == null) { games = emptyList(); problems = emptyList(); return }
        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            scanning = true; scanDone = 0; scanTotal = 0
            try {
                val files = ArrayList<LibFile>()
                prefs.gamesTree?.let { files += Library.listTree(ctx, Uri.parse(it)) }
                prefs.extraFiles.forEach { u -> Library.stat(ctx, Uri.parse(u))?.let { files += it } }
                val cache = if (force) emptyList() else Library.loadCache(ctx)
                val entries = Library.scan(ctx, files.distinctBy { it.uri }, keys, cache, { !isActive }) { d, t ->
                    scanDone = d; scanTotal = t
                }
                if (isActive) {
                    Library.saveCache(ctx, entries)
                    games = Library.group(entries)
                    problems = entries.filter { it.kind < 0 || it.error != null }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) message = "Scan failed: ${e.message}"
            } finally {
                scanning = false
            }
        }
    }

    fun extract(game: Game, exefs: Boolean, romfs: Boolean, update: Entry?) {
        Extractor.start(
            ctx,
            Request(
                game.appId, game.base.name, game.base.uri, game.base.fileName,
                update?.uri, update?.fileName, exefs, romfs,
            ),
        )
    }
}
