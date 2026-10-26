@file:OptIn(ExperimentalMaterial3Api::class)

package com.nxdump

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

class Pickers(
    val prodKeys: () -> Unit, val titleKeys: () -> Unit, val gamesFolder: () -> Unit,
    val outputFolder: () -> Unit, val addFile: () -> Unit,
)

@Composable
fun rememberPickers(vm: AppViewModel): Pickers {
    val prod = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importKeys(u, false) }
    val title = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importKeys(u, true) }
    val games = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) vm.setGamesFolder(u) }
    val out = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) vm.setOutputFolder(u) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.addFile(u) }
    return remember {
        Pickers(
            { prod.launch(arrayOf("*/*")) }, { title.launch(arrayOf("*/*")) },
            { games.launch(null) }, { out.launch(null) }, { file.launch(arrayOf("*/*")) },
        )
    }
}

fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "%d KB".format(b / 1024)
}

fun versionLabel(e: Entry): String = if (e.displayVersion.isNotBlank()) "v${e.displayVersion}" else "v${e.version}"

@Composable
fun NxApp(vm: AppViewModel) {
    val pickers = rememberPickers(vm)
    val snack = remember { SnackbarHostState() }
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(vm.setupDone) {
        if (vm.setupDone && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (!vm.setupDone) {
            Wizard(vm, pickers, snack)
        } else when (vm.screen) {
            Screen.LIBRARY -> LibraryScreen(vm, pickers, snack)
            Screen.SETTINGS -> {
                BackHandler { vm.screen = Screen.LIBRARY }
                SettingsScreen(vm, pickers, snack)
            }
        }
        ExtractDialog()
    }
}

@Composable
fun PickerCard(title: String, hint: String, value: String?, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    value ?: hint, style = MaterialTheme.typography.bodyMedium,
                    color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onClick) { Text(action) }
        }
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
fun GameIcon(e: Entry, size: Dp) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, e.titleId, e.hasIcon) {
        value = if (!e.hasIcon) null else withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(Library.iconFile(ctx, e.titleId).path)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Text(
            e.name.take(1).uppercase(), style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Setup wizard
// ---------------------------------------------------------------------------------------------

@Composable
fun StepPage(icon: ImageVector, title: String, body: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
fun Wizard(vm: AppViewModel, pickers: Pickers, snack: SnackbarHostState) {
    var step by remember { mutableIntStateOf(0) }
    val canNext = when (step) { 1 -> vm.keysSummary != null; 3 -> vm.outputName != null; else -> true }
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") } else Spacer(Modifier.size(1.dp))
                Button(onClick = { if (step < 3) step++ else vm.finishSetup() }, enabled = canNext) {
                    Text(if (step == 0) "Get started" else if (step < 3) "Next" else "Finish")
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            LinearProgressIndicator(progress = { (step + 1) / 4f }, Modifier.fillMaxWidth().padding(top = 16.dp))
            AnimatedContent(step, label = "step") { s ->
                when (s) {
                    0 -> StepPage(
                        Icons.Default.SportsEsports, "Welcome to NxDump",
                        "Extract ExeFS and RomFS from your own game dumps \u2014 with updates merged in. " +
                            "Everything happens on your device; nothing is uploaded.",
                    )
                    1 -> StepPage(
                        Icons.Default.VpnKey, "Your keys",
                        "NxDump needs prod.keys from your own console (for example dumped with Lockpick_RCM). " +
                            "title.keys is optional.",
                    ) {
                        PickerCard("prod.keys", "Required", vm.keysSummary, if (vm.keysSummary == null) "Select" else "Change", pickers.prodKeys)
                        PickerCard(
                            "title.keys", "Optional \u2014 only for games without an included ticket",
                            if (vm.titleKeyCount > 0) "${vm.titleKeyCount} title keys" else null,
                            if (vm.titleKeyCount > 0) "Change" else "Select", pickers.titleKeys,
                        )
                    }
                    2 -> StepPage(
                        Icons.Default.Folder, "Your games",
                        "Choose the folder that holds your NSP / XCI files. Games and their updates are listed automatically. " +
                            "You can skip this and add files one by one later.",
                    ) {
                        PickerCard("Game folder", "Optional", vm.gamesName, if (vm.gamesName == null) "Select" else "Change", pickers.gamesFolder)
                    }
                    else -> StepPage(
                        Icons.Default.FolderOpen, "Output folder",
                        "Extracted files are saved here. On Android 11+ pick (or create) a subfolder \u2014 " +
                            "Android doesn't allow the root of internal storage or Download.",
                    ) {
                        PickerCard("Output folder", "Required", vm.outputName, if (vm.outputName == null) "Select" else "Change", pickers.outputFolder)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------

@Composable
fun LibraryScreen(vm: AppViewModel, pickers: Pickers, snack: SnackbarHostState) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var selectedId by remember { mutableStateOf<Long?>(null) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            LargeTopAppBar(
                title = { Text("Library") }, scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = { vm.rescan(true) }) { Icon(Icons.Default.Refresh, "Rescan") }
                    IconButton(onClick = { vm.screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = pickers.addFile, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add file") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (vm.scanning) item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (vm.scanTotal > 0) "Scanning ${vm.scanDone} / ${vm.scanTotal}\u2026" else "Looking for games\u2026",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (vm.scanTotal > 0) LinearProgressIndicator(progress = { vm.scanDone / vm.scanTotal.toFloat() }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (vm.games.isEmpty() && !vm.scanning) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.SportsEsports, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("No games yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose a game folder in Settings, or tap Add file.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(vm.games, key = { it.appId }) { g -> GameCard(g) { selectedId = g.appId } }
            if (vm.problems.isNotEmpty()) item { ProblemsCard(vm.problems) }
        }
    }
    vm.games.find { it.appId == selectedId }?.let { g -> GameSheet(vm, g, pickers) { selectedId = null } }
}

@Composable
fun GameCard(g: Game, onClick: () -> Unit) {
    Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GameIcon(g.base, 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(g.base.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "%016X".format(g.appId), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(versionLabel(g.base), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    g.updates.firstOrNull()?.let {
                        Pill("Update ${versionLabel(it)}", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    if (g.dlcCount > 0) Pill("${g.dlcCount} DLC", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun ProblemsCard(list: List<Entry>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${list.size} file${if (list.size == 1) "" else "s"} couldn't be read", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            list.take(5).forEach {
                Text(
                    "${it.fileName}: ${it.error}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (list.size > 5) Text("\u2026and ${list.size - 5} more", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun OptionRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(sub) },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun GameSheet(vm: AppViewModel, g: Game, pickers: Pickers, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var exefs by remember { mutableStateOf(true) }
    var romfs by remember { mutableStateOf(true) }
    var useUpdate by remember(g.appId) { mutableStateOf(true) }
    var sel by remember(g.appId) { mutableIntStateOf(0) }
    val hasUpdate = g.updates.isNotEmpty()
    val canRun = (exefs || romfs) && vm.outputName != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GameIcon(g.base, 56.dp)
                Column {
                    Text(g.base.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${versionLabel(g.base)} \u00B7 ${fmtSize(g.base.size)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OptionRow("ExeFS", "main, main.npdm, rtld, sdk, subsdk\u2026", exefs) { exefs = it }
            OptionRow("RomFS", "Game assets and data", romfs) { romfs = it }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (hasUpdate) {
                OptionRow(
                    "Merge update ${versionLabel(g.updates[minOf(sel, g.updates.size - 1)])}",
                    "Uses the update's ExeFS and patches RomFS on top of the base game", useUpdate,
                ) { useUpdate = it }
                if (useUpdate && g.updates.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        g.updates.forEachIndexed { i, u ->
                            FilterChip(selected = sel == i, onClick = { sel = i }, label = { Text(versionLabel(u)) })
                        }
                    }
                }
            } else {
                Text(
                    "No update found for this game. Add the update NSP to merge it.",
                    Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = pickers.addFile) { Text(if (hasUpdate) "Add another update\u2026" else "Add update file\u2026") }
            Spacer(Modifier.height(4.dp))
            if (vm.outputName == null) {
                OutlinedButton(onClick = pickers.outputFolder, modifier = Modifier.fillMaxWidth()) { Text("Choose output folder first") }
            } else {
                Text(
                    "Saving to ${vm.outputName}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Button(
                onClick = {
                    vm.extract(g, exefs, romfs, if (hasUpdate && useUpdate) g.updates[minOf(sel, g.updates.size - 1)] else null)
                    onDismiss()
                },
                enabled = canRun, modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Extract")
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Progress dialog
// ---------------------------------------------------------------------------------------------

@Composable
fun ExtractDialog() {
    val st by Extractor.state.collectAsStateWithLifecycle()
    if (st.phase == Phase.IDLE) return
    val running = st.phase == Phase.RUNNING
    AlertDialog(
        onDismissRequest = { },
        title = {
            Text(
                when (st.phase) {
                    Phase.RUNNING -> "Extracting"
                    Phase.DONE -> "Done"
                    Phase.CANCELLED -> "Cancelled"
                    else -> "Failed"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(st.title, fontWeight = FontWeight.SemiBold)
                when (st.phase) {
                    Phase.RUNNING -> {
                        val frac = if (st.total > 0) st.done / st.total.toFloat() else 0f
                        if (st.total > 0) LinearProgressIndicator(progress = { frac }, Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("${fmtSize(st.done)} / ${fmtSize(st.total)}  \u00B7  ${(frac * 100).toInt()}%")
                        Text(
                            st.file, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Phase.DONE -> Text("Saved to \u201C${st.message}\u201D in your output folder.")
                    else -> Text(
                        st.message ?: "", color = if (st.phase == Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            if (running) TextButton(onClick = { Extractor.cancel() }) { Text("Cancel") }
            else TextButton(onClick = { Extractor.dismiss() }) { Text("Close") }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------

@Composable
private fun SectionLabel(t: String) {
    Text(
        t, Modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun SettingsScreen(vm: AppViewModel, pickers: Pickers, snack: SnackbarHostState) {
    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { vm.screen = Screen.LIBRARY }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("Keys")
            PickerCard("prod.keys", "Not set", vm.keysSummary, "Replace", pickers.prodKeys)
            PickerCard("title.keys", "Optional \u2014 not set", if (vm.titleKeyCount > 0) "${vm.titleKeyCount} title keys" else null, "Replace", pickers.titleKeys)
            SectionLabel("Folders")
            PickerCard("Game folder", "Not set", vm.gamesName, "Change", pickers.gamesFolder)
            if (vm.gamesName != null) TextButton(onClick = { vm.clearGamesFolder() }) { Text("Remove game folder") }
            PickerCard("Output folder", "Not set", vm.outputName, "Change", pickers.outputFolder)
            SectionLabel("Library")
            ListItem(
                headlineContent = { Text("Individually added files") },
                supportingContent = { Text("${vm.extraCount} file(s)") },
                trailingContent = { if (vm.extraCount > 0) TextButton(onClick = { vm.clearExtraFiles() }) { Text("Clear") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            OutlinedButton(onClick = { vm.rescan(true); vm.screen = Screen.LIBRARY }, modifier = Modifier.fillMaxWidth()) {
                Text("Rescan everything")
            }
            SectionLabel("About")
            Text(
                "NxDump extracts ExeFS and RomFS from XCI / NSP / NCA files you own. Keys never leave your device. " +
                    "Compressed NSZ/XCZ files are not supported.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
