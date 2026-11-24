package com.shidaifufen.todocard

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URI

class LayoutDownloadTest {
    @Test fun acceptsRepositoryAndReleaseDownloadLinks() {
        assertEquals("https://raw.githubusercontent.com/owner/repo/main/layouts/a%20b.json",
            LayoutDownload.resolve(" https://github.com/owner/repo/blob/main/layouts/a%20b.json?raw=true#L1 ").toString())
        assertEquals("https://raw.githubusercontent.com/owner/repo/refs/heads/main/layouts/default.json",
            LayoutDownload.resolve("https://github.com/owner/repo/raw/refs/heads/main/layouts/default.json").toString())
        val raw = "https://raw.githubusercontent.com/owner/repo/main/layouts/default.json"
        val release = "https://github.com/owner/repo/releases/download/v1/default.json"
        val latest = "https://github.com/owner/repo/releases/latest/download/default.json"
        listOf(raw, release, latest).forEach { assertEquals(it, LayoutDownload.resolve(it).toString()) }
    }

    @Test fun rejectsPagesAndUntrustedOrAuthenticatedUrls() {
        listOf("http://github.com/a/b/blob/main/x.json", "https://github.com.example.com/a/b/blob/main/x.json",
            "https://user:password@github.com/a/b/blob/main/x.json", "https://github.com:8443/a/b/blob/main/x.json",
            "https://github.com/a/b", "https://github.com/a/b/tree/main/layouts",
            "https://github.com/a/b/releases/tag/v1", "file:///tmp/config.json").forEach {
            assertTrue(it, runCatching { LayoutDownload.resolve(it) }.isFailure)
        }
    }

    @Test fun boundsStreamingDownloadsAndAcceptsUtf8Bom() {
        assertEquals("中文", LayoutDownload.read(ByteArrayInputStream("\uFEFF中文".toByteArray(Charsets.UTF_8))))
        assertEquals(LayoutPack.MAX_BYTES, LayoutDownload.read(ByteArrayInputStream(ByteArray(LayoutPack.MAX_BYTES) { 32 })).length)
        assertTrue(runCatching { LayoutDownload.read(ByteArrayInputStream(ByteArray(LayoutPack.MAX_BYTES + 1))) }.isFailure)
    }

    @Test fun rawNetworkFallbackPreservesReferenceAndEncodedFilePath() {
        assertEquals("https://api.github.com/repos/owner/repo/contents/layouts/a%20b.json?ref=main",
            LayoutDownload.apiFallback(LayoutDownload.resolve("https://github.com/owner/repo/blob/main/layouts/a%20b.json")).toString())
        assertEquals("https://api.github.com/repos/owner/repo/contents/layouts/default.json?ref=main",
            LayoutDownload.apiFallback(LayoutDownload.resolve("https://github.com/owner/repo/raw/refs/heads/main/layouts/default.json")).toString())
        assertNull(LayoutDownload.apiFallback(URI("https://github.com/owner/repo/releases/download/v1/default.json")))
    }
}
