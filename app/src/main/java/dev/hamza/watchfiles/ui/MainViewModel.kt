package dev.hamza.watchfiles.ui

import android.app.Application
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.hamza.watchfiles.data.LocalFiles
import dev.hamza.watchfiles.data.ServerStore
import dev.hamza.watchfiles.net.WifiNetwork
import dev.hamza.watchfiles.smb.RemoteEntry
import dev.hamza.watchfiles.smb.SmbBrowser
import dev.hamza.watchfiles.smb.SmbException
import dev.hamza.watchfiles.smb.SmbPaths
import dev.hamza.watchfiles.smb.SmbServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

sealed interface Listing {
    data object Loading : Listing
    data class Loaded(val entries: List<RemoteEntry>) : Listing
    data class Failed(val message: String) : Listing
}

data class Transfer(
    val name: String,
    val upload: Boolean,
    val done: Long = 0,
    val total: Long = 0,
    val error: String? = null,
    val finished: Boolean = false,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ServerStore(app)
    val local = LocalFiles(app)
    val wifi = WifiNetwork(app)

    var servers by mutableStateOf(store.load())
        private set
    var current by mutableStateOf<SmbServer?>(null)
        private set
    val listings = mutableStateMapOf<String, Listing>()
    var selectedRemote by mutableStateOf<RemoteEntry?>(null)
    var selectedLocal by mutableStateOf<File?>(null)
    var localFiles by mutableStateOf<List<File>>(emptyList())
        private set
    var transfer by mutableStateOf<Transfer?>(null)
        private set

    /** Server being edited; null id means a new one. */
    var editing by mutableStateOf<SmbServer?>(null)
    var editStatus by mutableStateOf<String?>(null)
        private set
    var editBusy by mutableStateOf(false)
        private set

    private val settings = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    var crownSpeed by mutableStateOf(
        runCatching { CrownSpeed.valueOf(settings.getString("crown_speed", null) ?: "") }.getOrDefault(CrownSpeed.Normal)
    )
        private set

    fun cycleCrownSpeed() {
        crownSpeed = crownSpeed.next()
        settings.edit().putString("crown_speed", crownSpeed.name).apply()
    }

    private var browser: SmbBrowser? = null
    private var browserKey: Pair<SmbServer, Network?>? = null
    private var transferJob: Job? = null

    init {
        wifi.start()
        local.cleanPartials()
        refreshLocal()
    }

    // ---------- Servers ----------

    fun startAdd() {
        editing = SmbServer(id = "", name = "", host = "", share = "")
        editStatus = null
    }

    fun startEdit(server: SmbServer) {
        editing = server
        editStatus = null
    }

    /** Checks that the server answers with these settings, then saves. Calls [onSaved] on success. */
    fun testAndSave(draft: SmbServer, onSaved: () -> Unit) {
        val server = normalize(draft)
        if (server.host.isBlank() || server.share.isBlank()) {
            editStatus = "Address and share are required."
            return
        }
        editBusy = true
        editStatus = "Connecting…"
        viewModelScope.launch {
            val result = runCatching {
                val net = awaitWifi()
                withContext(Dispatchers.IO) {
                    SmbBrowser(server, net?.socketFactory).use { it.testConnection() }
                }
            }
            editBusy = false
            result.onSuccess {
                val saved = if (server.id.isEmpty()) server.copy(id = UUID.randomUUID().toString()) else server
                servers = servers.filterNot { it.id == saved.id } + saved
                store.save(servers)
                if (current?.id == saved.id) open(saved)
                editStatus = null
                onSaved()
            }.onFailure { editStatus = message(it) }
        }
    }

    fun deleteServer(server: SmbServer) {
        servers = servers.filterNot { it.id == server.id }
        store.save(servers)
        if (current?.id == server.id) closeBrowser()
    }

    fun open(server: SmbServer) {
        if (current != server) {
            closeBrowser()
            current = server
            listings.clear()
        }
    }

    /**
     * Accepts what people tend to type: "192.168.1.5", "nas:4455", "smb://nas/Media" or
     * "\\PC\Share", and "DOMAIN\user".
     */
    private fun normalize(s: SmbServer): SmbServer {
        var host = s.host.trim().removePrefix("smb://").removePrefix("SMB://").trim('\\', '/')
        var share = s.share.trim().trim('\\', '/')
        val parts = host.split('/', '\\').filter { it.isNotEmpty() }
        if (parts.size > 1) {
            host = parts[0]
            if (share.isEmpty()) share = parts[1]
        }
        var port = s.port
        val colon = host.lastIndexOf(':')
        if (colon > 0 && host.count { it == ':' } == 1) {
            host.substring(colon + 1).toIntOrNull()?.let { port = it }
            host = host.substring(0, colon)
        }
        var user = s.username.trim()
        var domain = s.domain.trim()
        if ('\\' in user) {
            domain = user.substringBefore('\\')
            user = user.substringAfter('\\')
        }
        return s.copy(
            name = s.name.trim().ifEmpty { "$host/$share" },
            host = host, share = share, port = port, username = user, domain = domain,
        )
    }

    // ---------- Browsing ----------

    fun load(path: String, force: Boolean = false) {
        val key = SmbPaths.normalize(path)
        if (!force && listings[key] is Listing.Loaded) return
        if (listings[key] == Listing.Loading) return
        listings[key] = Listing.Loading
        viewModelScope.launch {
            listings[key] = runCatching {
                val b = browser()
                withContext(Dispatchers.IO) { b.list(key) }
            }.fold({ Listing.Loaded(it) }, { Listing.Failed(message(it)) })
        }
    }

    fun createFolder(parent: String, name: String, onDone: () -> Unit) {
        if (name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val b = browser()
                withContext(Dispatchers.IO) { b.createFolder(parent, name.trim()) }
            }.onSuccess { load(parent, force = true); onDone() }
                .onFailure { listings[SmbPaths.normalize(parent)] = Listing.Failed(message(it)) ; onDone() }
        }
    }

    fun deleteRemote(entry: RemoteEntry, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val r = runCatching {
                val b = browser()
                withContext(Dispatchers.IO) { b.delete(entry) }
            }
            load(SmbPaths.parent(entry.path), force = true)
            onDone(r.exceptionOrNull()?.let { message(it) })
        }
    }

    // ---------- Transfers ----------

    fun download(entry: RemoteEntry) {
        startTransfer(Transfer(entry.name, upload = false, total = entry.size)) { job ->
            val b = browser()
            val target = local.newFile(entry.name)
            val part = local.partialFor(target)
            try {
                withContext(Dispatchers.IO) {
                    part.outputStream().buffered().use { out ->
                        b.download(entry.path, out) { done, total -> job.ensureActive(); progress(done, total) }
                    }
                    if (!part.renameTo(target)) throw SmbException("Couldn't save the file on the watch.")
                }
            } finally {
                part.delete()
                refreshLocal()
            }
        }
    }

    fun upload(file: File, remoteDir: String) {
        startTransfer(Transfer(file.name, upload = true, total = file.length())) { job ->
            val b = browser()
            withContext(Dispatchers.IO) {
                file.inputStream().buffered().use { input ->
                    b.upload(input, file.length(), remoteDir, file.name) { done, total -> job.ensureActive(); progress(done, total) }
                }
            }
            load(remoteDir, force = true)
        }
    }

    private fun startTransfer(initial: Transfer, work: suspend (Job) -> Unit) {
        if (transferJob?.isActive == true) return
        transfer = initial
        transferJob = viewModelScope.launch {
            val job = coroutineContext.job
            try {
                work(job)
                transfer = transfer?.copy(finished = true, done = transfer?.total ?: 0)
            } catch (e: CancellationException) {
                transfer = transfer?.copy(error = "Stopped.", finished = true)
            } catch (e: Throwable) {
                transfer = transfer?.copy(error = message(e), finished = true)
            }
        }
    }

    private var lastUiUpdate = 0L
    private fun progress(done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 200 && done < total) return
        lastUiUpdate = now
        viewModelScope.launch(Dispatchers.Main) { transfer = transfer?.copy(done = done, total = total) }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
    }

    fun clearTransfer() {
        if (transferJob?.isActive != true) transfer = null
    }

    // ---------- Watch files ----------

    fun refreshLocal() {
        localFiles = local.list()
    }

    fun deleteLocal(file: File) {
        file.delete()
        refreshLocal()
    }

    // ---------- Plumbing ----------

    private suspend fun awaitWifi(): Network? {
        wifi.start()
        return wifi.network.value ?: withTimeoutOrNull(15_000) { wifi.network.filterNotNull().first() }
            ?: throw SmbException("No Wi-Fi. Turn on Wi-Fi on the watch and join your home network.")
    }

    private suspend fun browser(): SmbBrowser {
        val server = current ?: throw SmbException("No server selected.")
        val net = awaitWifi()
        val key = server to net
        browser?.let { if (browserKey == key) return it }
        closeBrowser(keepCurrent = true)
        return SmbBrowser(server, net?.socketFactory).also { browser = it; browserKey = key }
    }

    private fun closeBrowser(keepCurrent: Boolean = false) {
        val b = browser
        browser = null
        browserKey = null
        if (!keepCurrent) current = null
        if (b != null) viewModelScope.launch(Dispatchers.IO) { b.close() }
    }

    private fun message(e: Throwable): String =
        (e as? SmbException)?.message ?: SmbBrowser.friendly(e).message ?: "Something went wrong."

    override fun onCleared() {
        transferJob?.cancel()
        browser?.let { b -> Thread { b.close() }.start() }
        wifi.stop()
    }
}
