package io.github.rezzaghi.edgellm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun idsAreUnique() {
        val ids = Catalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun entriesAreComplete() {
        Catalog.all.forEach { spec ->
            assertTrue(spec.id.isNotBlank())
            assertTrue(spec.url.startsWith("https://"))
            assertTrue(spec.sizeBytes > 0)
            assertEquals(64, spec.sha256?.length)
        }
    }

    @Test
    fun customBuildsSpecVerbatim() {
        val spec = Catalog.custom(
            id = "my-model",
            url = "https://example.com/m.gguf",
            sha256 = "abc",
            sizeBytes = 42,
        )
        assertEquals("my-model", spec.id)
        assertEquals("https://example.com/m.gguf", spec.url)
        assertEquals("abc", spec.sha256)
        assertEquals(42, spec.sizeBytes)
        assertEquals(ModelFormat.GGUF, spec.format)
    }
}
