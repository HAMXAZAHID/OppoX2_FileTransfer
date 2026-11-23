package dev.hamza.watchfiles.smb

/** A saved SMB server + share. An empty [username] means guest access. */
data class SmbServer(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val port: Int = 445,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val isGuest: Boolean get() = username.isBlank()
    val label: String get() = name.ifBlank { "$host/$share" }
}

/** One file or folder on the share. [path] is share-relative and uses backslashes ("" is the root). */
data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedMillis: Long,
)

/** Thrown with a message that is short enough to show on a watch. */
class SmbException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /**
     * True when the link itself broke (timeout, reset, stale connection) rather than the server
     * refusing the request. Worth one automatic retry on a fresh connection.
     */
    val isConnectionProblem: Boolean
        get() = generateSequence(cause) { it.cause }.none { it is com.hierynomus.mssmb2.SMBApiException } &&
            generateSequence(cause) { it.cause }.any {
                it is java.io.IOException || it is com.hierynomus.smbj.common.SMBRuntimeException
            }
}
