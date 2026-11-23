package dev.hamza.watchfiles.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hamza.watchfiles.R
import dev.hamza.watchfiles.smb.RemoteEntry
import dev.hamza.watchfiles.smb.SmbPaths
import java.io.File

private val ErrorColor = Color(0xFFF28B82)

// ---------------- Home: saved servers ----------------

@Composable
fun ServersScreen(
    vm: MainViewModel,
    onBrowse: () -> Unit,
    onAdd: () -> Unit,
    onWatchFiles: () -> Unit,
) {
    val network by vm.wifi.network.collectAsState()
    val context = LocalContext.current
    ListScreen {
        header("Home Files")
        if (network == null) {
            item {
                ActionChip("Wi-Fi off", R.drawable.ic_wifi, secondary = "Tap to open Wi-Fi settings") {
                    openWifiSettings(context)
                }
            }
        }
        vm.servers.forEach { server ->
            item {
                ActionChip(
                    server.label,
                    R.drawable.ic_server,
                    secondary = "\\\\${server.host}\\${server.share}",
                    colors = ChipDefaults.primaryChipColors(),
                ) {
                    vm.open(server)
                    onBrowse()
                }
            }
        }
        if (!vm.downloadsAccess) {
            item {
                ActionChip("Allow Downloads access", R.drawable.ic_download, secondary = "To save into the Download folder") {
                    openAllFilesAccess(context)
                }
            }
        }
        item { ActionChip("Add server", R.drawable.ic_add) { vm.startAdd(); onAdd() } }
        item {
            ActionChip("Watch files", R.drawable.ic_watch, secondary = "${vm.localFiles.size} files") {
                vm.refreshLocal(); onWatchFiles()
            }
        }
        if (vm.servers.isEmpty()) {
            note("Add a Windows shared folder or NAS share to get started.")
        }
    }
}

private fun openAllFilesAccess(context: android.content.Context) {
    val pkg = android.net.Uri.parse("package:" + context.packageName)
    val intents = listOf(
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    Toast.makeText(context, "Grant it from a PC: see the app's README (adb appops)", Toast.LENGTH_LONG).show()
}

private fun openWifiSettings(context: android.content.Context) {
    val intents = listOf(
        Intent("com.google.android.clockwork.settings.connectivity.wifi.ADD_NETWORK_SETTINGS"),
        Intent(Settings.ACTION_WIFI_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
        } catch (_: ActivityNotFoundException) {
        }
    }
    Toast.makeText(context, "Open Settings > Connectivity > Wi-Fi", Toast.LENGTH_LONG).show()
}

// ---------------- Add / edit server ----------------

@Composable
fun EditServerScreen(vm: MainViewModel, onDone: () -> Unit) {
    val start = vm.editing ?: return
    var name by rememberSaveable { mutableStateOf(start.name) }
    var host by rememberSaveable { mutableStateOf(if (start.host.isEmpty()) "" else if (start.port == 445) start.host else "${start.host}:${start.port}") }
    var share by rememberSaveable { mutableStateOf(start.share) }
    var user by rememberSaveable { mutableStateOf(if (start.domain.isEmpty()) start.username else "${start.domain}\\${start.username}") }
    var pass by rememberSaveable { mutableStateOf(start.password) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isNew = start.id.isEmpty()

    ListScreen {
        header(if (isNew) "Add server" else "Edit server")
        item { Field("Address (IP or name)", host, { host = it }, KeyboardType.Uri, placeholder = "192.168.1.10") }
        item { Field("Share", share, { share = it }, placeholder = "Media") }
        item { Field("Username (blank = guest)", user, { user = it }, KeyboardType.Email) }
        item { Field("Password", pass, { pass = it }, password = true) }
        item { Field("Name on watch (optional)", name, { name = it }, placeholder = "My NAS") }
        vm.editStatus?.let { note(it, if (vm.editBusy) Color(0xFFBDC1C6) else ErrorColor) }
        item {
            ActionChip(
                if (vm.editBusy) "Connecting…" else "Test & save",
                R.drawable.ic_check,
                colors = ChipDefaults.primaryChipColors(),
                enabled = !vm.editBusy,
            ) {
                vm.testAndSave(start.copy(name = name, host = host, share = share, username = user, password = pass, domain = "", port = 445), onDone)
            }
        }
        if (!isNew) {
            item {
                ActionChip(
                    if (confirmDelete) "Tap again to remove" else "Remove server",
                    R.drawable.ic_delete,
                ) {
                    if (confirmDelete) { vm.deleteServer(start); onDone() } else confirmDelete = true
                }
            }
        }
        note("Tip: on Windows, right-click a folder > Properties > Sharing. Use your PC's IP address.")
    }
}

// ---------------- Folder browser ----------------

@Composable
fun BrowseScreen(
    vm: MainViewModel,
    path: String,
    onFolder: (String) -> Unit,
    onFile: (RemoteEntry) -> Unit,
    onUploadHere: () -> Unit,
    onNewFolder: () -> Unit,
    onEditServer: () -> Unit,
) {
    val server = vm.current ?: return
    LaunchedEffect(server, path) { vm.load(path) }
    val listing = vm.listings[SmbPaths.normalize(path)]

    ListScreen {
        header(if (path.isEmpty()) server.label else SmbPaths.name(path))
        when (listing) {
            null, Listing.Loading -> item { CircularProgressIndicator(modifier = Modifier.size(36.dp)) }
            is Listing.Failed -> {
                note(listing.message, ErrorColor)
                item { ActionChip("Try again", R.drawable.ic_refresh) { vm.load(path, force = true) } }
            }
            is Listing.Loaded -> {
                if (listing.entries.isEmpty()) note("This folder is empty.")
                listing.entries.forEach { e ->
                    item {
                        if (e.isDirectory) {
                            ActionChip(e.name, R.drawable.ic_folder, colors = ChipDefaults.primaryChipColors()) { onFolder(e.path) }
                        } else {
                            ActionChip(e.name, R.drawable.ic_file, secondary = formatSize(e.size)) { onFile(e) }
                        }
                    }
                }
            }
        }
        item { ActionChip("Upload here", R.drawable.ic_upload) { vm.refreshLocal(); onUploadHere() } }
        item { ActionChip("New folder", R.drawable.ic_new_folder) { onNewFolder() } }
        item { ActionChip("Refresh", R.drawable.ic_refresh) { vm.load(path, force = true) } }
        if (path.isEmpty()) {
            item { ActionChip("Server settings", R.drawable.ic_edit) { vm.startEdit(server); onEditServer() } }
        }
    }
}

@Composable
fun RemoteFileScreen(vm: MainViewModel, onDownload: () -> Unit, onDeleted: () -> Unit) {
    val entry = vm.selectedRemote ?: return
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val free = remember { vm.local.freeBytes() }
    ListScreen {
        header(entry.name)
        note(listOf(formatSize(entry.size), formatDate(entry.modifiedMillis)).filter { it.isNotEmpty() }.joinToString(" · "))
        item {
            ActionChip(
                "Download to watch", R.drawable.ic_download,
                secondary = "${formatSize(free)} free",
                colors = ChipDefaults.primaryChipColors(),
                enabled = entry.size < free,
            ) {
                vm.download(entry); onDownload()
            }
        }
        item {
            ActionChip(if (confirmDelete) "Tap again to delete" else "Delete from server", R.drawable.ic_delete, enabled = !busy) {
                if (!confirmDelete) { confirmDelete = true; return@ActionChip }
                busy = true
                vm.deleteRemote(entry) { err ->
                    busy = false
                    if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show() else onDeleted()
                }
            }
        }
    }
}

@Composable
fun NewFolderScreen(vm: MainViewModel, parent: String, onDone: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    ListScreen {
        header("New folder")
        item { Field("Folder name", name, { name = it }) }
        item {
            ActionChip("Create", R.drawable.ic_check, colors = ChipDefaults.primaryChipColors(), enabled = name.isNotBlank() && !busy) {
                busy = true
                vm.createFolder(parent, name) { onDone() }
            }
        }
    }
}

// ---------------- Files on the watch ----------------

/** Lists watch files. With [uploadTo] set, tapping a file uploads it there. */
@Composable
fun WatchFilesScreen(vm: MainViewModel, uploadTo: String?, onFile: (File) -> Unit, onUploadStarted: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshLocal() }
    ListScreen {
        header(if (uploadTo != null) "Pick a file to upload" else "Watch files")
        if (vm.localFiles.isEmpty()) {
            note(if (uploadTo != null) "No files on the watch yet. Download something first." else "Files you download appear here.")
        }
        vm.localFiles.forEach { f ->
            item {
                ActionChip(f.name, R.drawable.ic_file, secondary = formatSize(f.length())) {
                    if (uploadTo != null) { vm.upload(f, uploadTo); onUploadStarted() } else onFile(f)
                }
            }
        }
        if (uploadTo == null) note("In ${vm.local.dirLabel} · ${formatSize(vm.local.freeBytes())} free")
    }
}

@Composable
fun WatchFileScreen(vm: MainViewModel, onDeleted: () -> Unit) {
    val file = vm.selectedLocal ?: return
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    ListScreen {
        header(file.name)
        note("${formatSize(file.length())} · ${formatDate(file.lastModified())}")
        item {
            ActionChip("Open", R.drawable.ic_open, colors = ChipDefaults.primaryChipColors()) {
                try {
                    context.startActivity(vm.local.viewIntent(file))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, "No app on the watch can open this file.", Toast.LENGTH_LONG).show()
                }
            }
        }
        item {
            ActionChip(if (confirmDelete) "Tap again to delete" else "Delete from watch", R.drawable.ic_delete) {
                if (confirmDelete) { vm.deleteLocal(file); onDeleted() } else confirmDelete = true
            }
        }
        note("To upload, open a server folder and choose \"Upload here\".")
    }
}

// ---------------- Transfer progress ----------------

@Composable
fun TransferScreen(vm: MainViewModel, onClose: () -> Unit) {
    val t = vm.transfer
    val view = LocalView.current
    // Keep the screen awake so the watch doesn't suspend the app mid-transfer.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    if (t == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = if (t.finished && t.error == null) 1f else t.fraction,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            strokeWidth = 6.dp,
            indicatorColor = if (t.error != null) ErrorColor else MaterialTheme.colors.primary,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
        ) {
            Text(
                when {
                    t.error != null -> if (t.upload) "Upload failed" else "Download failed"
                    t.finished -> if (t.upload) "Uploaded" else "Saved to watch"
                    t.upload -> "Uploading"
                    else -> "Downloading"
                },
                style = MaterialTheme.typography.title3,
            )
            Text(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption2)
            Text(
                t.error ?: "${formatSize(t.done)} / ${formatSize(t.total)}",
                color = if (t.error != null) ErrorColor else Color(0xFFBDC1C6),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.caption2,
                maxLines = 3,
            )
            Row(Modifier.padding(top = 8.dp)) {
                if (t.finished) {
                    Button(onClick = { vm.clearTransfer(); onClose() }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = "Done")
                    }
                } else {
                    Button(
                        onClick = { vm.cancelTransfer() },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                    ) {
                        Icon(painterResource(R.drawable.ic_close), contentDescription = "Stop")
                    }
                }
            }
        }
    }
}
