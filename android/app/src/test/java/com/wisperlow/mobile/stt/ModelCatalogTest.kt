package com.wisperlow.mobile.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun catalogEntriesHaveImmutableArchiveMetadata() {
        assertEquals(ModelCatalog.all.size, ModelCatalog.all.map { it.id }.distinct().size)
        ModelCatalog.all.forEach { model ->
            assertTrue(model.downloadUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/"))
            assertTrue(model.archiveName.endsWith(".tar.bz2"))
            assertTrue(model.archiveSizeBytes > 400_000_000L)
            assertEquals(64, model.archiveSha256.length)
            assertTrue(model.archiveSha256.all { it in '0'..'9' || it in 'a'..'f' })
        }
    }
}
