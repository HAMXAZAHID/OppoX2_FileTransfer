package dev.hamza.watchfiles.smb

import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Runs against a real SMB server. Set SMB_TEST_HOST, SMB_TEST_SHARE, SMB_TEST_USER and
 * SMB_TEST_PASS (and optionally SMB_TEST_PORT). Skipped when SMB_TEST_HOST is empty.
 */
class SmbBrowserIntegrationTest {
    private val host = System.getenv("SMB_TEST_HOST").orEmpty()
    private fun server(password: String = System.getenv("SMB_TEST_PASS").orEmpty(), share: String = System.getenv("SMB_TEST_SHARE") ?: "test") =
        SmbServer(
            id = "t", name = "test", host = host, share = share,
            port = System.getenv("SMB_TEST_PORT")?.toIntOrNull() ?: 445,
            username = System.getenv("SMB_TEST_USER").orEmpty(), password = password,
        )

    @Test fun roundTrip() {
        assumeTrue(host.isNotEmpty())
        SmbBrowser(server()).use { smb ->
            smb.testConnection()
            val dir = smb.createFolder("", "it-${System.nanoTime()}")
            val data = Random.nextBytes(1_000_000)
            var lastProgress = 0L
            val written = smb.upload(ByteArrayInputStream(data), data.size.toLong(), dir, "big.bin") { d, _ -> lastProgress = d }
            assertEquals(data.size.toLong(), lastProgress)
            assertEquals(SmbPaths.join(dir, "big.bin"), written)

            // Same name again gets numbered instead of overwriting.
            val second = smb.upload(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3, dir, "big.bin")
            assertEquals(SmbPaths.join(dir, "big (1).bin"), second)

            smb.createFolder(dir, "sub")
            val listing = smb.list(dir)
            assertEquals(listOf("sub", "big (1).bin", "big.bin"), listing.map { it.name })
            assertTrue(listing.first().isDirectory)
            assertEquals(data.size.toLong(), listing.first { it.name == "big.bin" }.size)

            val out = ByteArrayOutputStream()
            smb.download(written, out)
            assertContentEquals(data, out.toByteArray())

            smb.delete(listing.first { it.name == "big (1).bin" })
            // Some Samba builds finish a delete-on-close a moment after the close returns.
            assertEquals(listOf("sub", "big.bin"), poll({ it.size == 2 }) { smb.list(dir).map { it.name } })
            smb.delete(RemoteEntry("x", dir, true, 0, 0))
            assertTrue(poll({ l -> l.none { it == dir } }) { smb.list("").map { it.path } }.none { it == dir })
        }
    }

    /** Calls [read] for up to 3 s until [done] holds, and returns the last result. */
    private fun <T> poll(done: (T) -> Boolean, read: () -> T): T {
        var r = read()
        var tries = 0
        while (!done(r) && tries++ < 15) { Thread.sleep(200); r = read() }
        return r
    }

    @Test fun cancelledUploadLeavesNoFile() {
        assumeTrue(host.isNotEmpty())
        SmbBrowser(server()).use { smb ->
            val data = Random.nextBytes(2_000_000)
            assertFailsWith<IllegalStateException> {
                smb.upload(ByteArrayInputStream(data), data.size.toLong(), "", "cancel.bin") { d, _ ->
                    if (d > 500_000) throw IllegalStateException("cancel")
                }
            }
            assertTrue(poll({ l -> l.none { it.startsWith("cancel") } }) { smb.list("").map { it.name } }.none { it.startsWith("cancel") })
        }
    }

    @Test fun wrongPasswordIsFriendly() {
        assumeTrue(host.isNotEmpty())
        val e = assertFailsWith<SmbException> { SmbBrowser(server(password = "nope")).use { it.testConnection() } }
        assertEquals("Wrong username or password.", e.message)
    }

    @Test fun missingShareIsFriendly() {
        assumeTrue(host.isNotEmpty())
        val e = assertFailsWith<SmbException> { SmbBrowser(server(share = "nosuchshare")).use { it.testConnection() } }
        assertEquals("No share with that name on the server.", e.message)
    }

    @Test fun unreachableHostIsFriendly() {
        assumeTrue(host.isNotEmpty())
        val e = assertFailsWith<SmbException> {
            SmbBrowser(server().copy(host = "127.0.0.1", port = 1), timeoutSeconds = 3).use { it.testConnection() }
        }
        assertTrue(e.message!!.contains("refused"), e.message)
    }
}
