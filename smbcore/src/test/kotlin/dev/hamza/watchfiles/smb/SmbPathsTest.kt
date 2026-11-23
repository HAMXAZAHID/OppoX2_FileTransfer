package dev.hamza.watchfiles.smb

import kotlin.test.Test
import kotlin.test.assertEquals

class SmbPathsTest {
    @Test fun normalizeAndJoin() {
        assertEquals("a\\b", SmbPaths.normalize("/a//b/"))
        assertEquals("a", SmbPaths.join("", "a"))
        assertEquals("a\\b", SmbPaths.join("a", "b"))
        assertEquals("", SmbPaths.parent("a"))
        assertEquals("a", SmbPaths.parent("a\\b"))
        assertEquals("b", SmbPaths.name("a\\b"))
    }

    @Test fun numberedNames() {
        assertEquals("song (1).mp3", SmbPaths.numbered("song.mp3", 1))
        assertEquals("README (2)", SmbPaths.numbered("README", 2))
        assertEquals(".env (1)", SmbPaths.numbered(".env", 1))
    }

    @Test fun sanitize() {
        assertEquals("a_b_c", SmbPaths.sanitizeName("a:b?c"))
    }
}
