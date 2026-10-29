package com.wisperlow.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloaderTest {
    @Test fun archiveRootDoesNotBecomeANestedDirectory() {
        assertEquals("", ModelDownloader.archiveRelativePath("model", "model/", isDirectory = true))
        assertEquals("tokens.txt", ModelDownloader.archiveRelativePath("model/tokens.txt", "model/"))
        assertEquals("subdir", ModelDownloader.archiveRelativePath("model/subdir", "model/", isDirectory = true))
        assertEquals("model-other", ModelDownloader.archiveRelativePath("model-other", "model/"))
        assertEquals("model", ModelDownloader.archiveRelativePath("model", "model/"))
        assertEquals("tokens.txt", ModelDownloader.archiveRelativePath("tokens.txt", ""))
        assertEquals("", ModelDownloader.archiveRelativePath("", ""))
    }
}
