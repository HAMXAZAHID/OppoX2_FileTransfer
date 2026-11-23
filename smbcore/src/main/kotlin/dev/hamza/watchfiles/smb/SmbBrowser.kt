package dev.hamza.watchfiles.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Talks to one SMB share. Connects lazily and reconnects if the link dropped
 * (watches drop Wi-Fi often). Calls are blocking: run them off the main thread.
 */
class SmbBrowser(
    private val server: SmbServer,
    private val socketFactory: SocketFactory? = null,
    private val timeoutSeconds: Long = 20,
) : Closeable {

    private val client: SMBClient by lazy {
        val config = SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .withSoTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .withBufferSize(BUFFER)
            .apply { if (socketFactory != null) withSocketFactory(socketFactory) }
            .build()
        SMBClient(config)
    }

    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    private fun share(): DiskShare {
        share?.let { if (it.isConnected && connection?.isConnected == true) return it }
        closeQuietly()
        try {
            val conn = client.connect(server.host, server.port)
            connection = conn
            val auth = if (server.isGuest) AuthenticationContext.guest()
            else AuthenticationContext(server.username, server.password.toCharArray(), server.domain.ifBlank { null })
            val sess = conn.authenticate(auth)
            session = sess
            val s = sess.connectShare(server.share) as? DiskShare
                ?: throw SmbException("\"${server.share}\" is not a folder share")
            share = s
            return s
        } catch (e: Exception) {
            closeQuietly()
            throw friendly(e)
        }
    }

    /** Opens the connection and the share, so the user learns early if settings are wrong. */
    fun testConnection() {
        guarded { share().list("") }
    }

    /** Lists a folder: folders first, then files, both A to Z. Hidden entries are skipped. */
    fun list(path: String): List<RemoteEntry> = guarded {
        val dir = SmbPaths.normalize(path)
        share().list(dir)
            .filter { it.fileName != "." && it.fileName != ".." }
            .filterNot { EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) }
            .map {
                val isDir = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                RemoteEntry(
                    name = it.fileName,
                    path = SmbPaths.join(dir, it.fileName),
                    isDirectory = isDir,
                    size = if (isDir) 0 else it.endOfFile,
                    modifiedMillis = it.lastWriteTime.toEpochMillis(),
                )
            }
            .sortedWith(compareBy<RemoteEntry>({ !it.isDirectory }, { it.name.lowercase() }))
    }

    /**
     * Copies a remote file into [out]. [onProgress] gets (bytesDone, totalBytes); it may throw
     * (for example a coroutine CancellationException) to stop the copy.
     */
    fun download(path: String, out: OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }) = guarded {
        val s = share()
        s.openFile(
            SmbPaths.normalize(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            val total = file.fileInformation.standardInformation.endOfFile
            file.inputStream.use { input -> copy(input, out, total, onProgress) }
        }
    }

    /**
     * Uploads [input] into folder [remoteDir]. If [name] is taken, a numbered name like
     * "photo (1).jpg" is used. Returns the share path written.
     */
    fun upload(
        input: InputStream,
        size: Long,
        remoteDir: String,
        name: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String = guarded {
        val s = share()
        val clean = SmbPaths.sanitizeName(name)
        var target = SmbPaths.join(remoteDir, clean)
        var n = 1
        while (s.fileExists(target) || s.folderExists(target)) {
            target = SmbPaths.join(remoteDir, SmbPaths.numbered(clean, n++))
        }
        s.openFile(
            target,
            // DELETE lets us remove a half-written file if the upload is stopped.
            EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        ).use { file ->
            try {
                file.outputStream.use { out -> copy(input, out, size, onProgress) }
            } catch (e: Throwable) {
                // Don't leave a half-written file behind.
                runCatching { file.deleteOnClose() }
                throw e
            }
        }
        target
    }

    fun createFolder(parent: String, name: String): String = guarded {
        val path = SmbPaths.join(parent, SmbPaths.sanitizeName(name))
        share().mkdir(path)
        path
    }

    fun delete(entry: RemoteEntry) = guarded {
        val s = share()
        if (entry.isDirectory) s.rmdir(entry.path, true) else s.rm(entry.path)
    }

    /** Turns network/SMB failures into [SmbException]; anything else (e.g. a cancel from a callback) passes through. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: SmbException) {
        throw e
    } catch (e: SMBApiException) {
        throw friendly(e)
    } catch (e: IOException) {
        closeQuietly() // Broken link: drop it so the next call reconnects.
        throw friendly(e)
    } catch (e: SMBRuntimeException) {
        closeQuietly()
        throw friendly(e)
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, onProgress: (Long, Long) -> Unit) {
        val buf = ByteArray(BUFFER)
        var done = 0L
        var lastReport = 0L
        onProgress(0, total)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            done += r
            if (done - lastReport >= REPORT_EVERY || done == total) {
                lastReport = done
                onProgress(done, total)
            }
        }
        out.flush()
        onProgress(done, total)
    }

    @Synchronized
    private fun closeQuietly() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close(true) }
        share = null; session = null; connection = null
    }

    @Synchronized
    override fun close() {
        closeQuietly()
        runCatching { client.close() }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 128 * 1024L

        fun friendly(e: Throwable): SmbException {
            if (e is SmbException) return e
            var c: Throwable? = e
            while (c != null) {
                when (c) {
                    is UnknownHostException -> return SmbException("Can't find that server name. Try its IP address.", e)
                    is NoRouteToHostException -> return SmbException("Server unreachable. Is the watch on home Wi-Fi?", e)
                    is ConnectException -> return SmbException("Server refused the connection. Is file sharing on?", e)
                    is SocketTimeoutException -> return SmbException("Server didn't answer in time.", e)
                    is SMBApiException -> return SmbException(statusMessage(c.status), e)
                }
                c = c.cause
            }
            return SmbException(e.message?.take(120) ?: e.javaClass.simpleName, e)
        }

        private fun statusMessage(s: NtStatus): String = when (s) {
            NtStatus.STATUS_LOGON_FAILURE -> "Wrong username or password."
            NtStatus.STATUS_ACCOUNT_DISABLED -> "This account can't sign in over the network."
            NtStatus.STATUS_ACCESS_DENIED -> "Access denied."
            NtStatus.STATUS_BAD_NETWORK_NAME -> "No share with that name on the server."
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "That file or folder no longer exists."
            NtStatus.STATUS_OBJECT_NAME_COLLISION -> "Something with that name already exists."
            NtStatus.STATUS_DISK_FULL -> "The server's disk is full."
            NtStatus.STATUS_SHARING_VIOLATION -> "The file is open in another program."
            NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "Folder isn't empty."
            else -> "Server error: ${s.name.removePrefix("STATUS_").lowercase().replace('_', ' ')}"
        }
    }
}
