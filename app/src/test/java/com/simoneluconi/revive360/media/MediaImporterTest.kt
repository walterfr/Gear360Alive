package com.simoneluconi.revive360.media

import com.simoneluconi.revive360.protocol.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaImporterTest {
    @Test
    fun onlyCameraPhotosAndVideosAreImported() {
        assertEquals(FileKind.PHOTO, MediaImporter.kindOf("360_0001.JPG"))
        assertEquals(FileKind.VIDEO, MediaImporter.kindOf("360_0002.MP4"))
        assertNull(MediaImporter.kindOf("360_0003.raw"))
        assertNull(MediaImporter.kindOf("info.tg"))
        assertNull(MediaImporter.kindOf("noextension"))
    }

    @Test
    fun aDuplicateNeedsTheSameNameAndSize() {
        val existing = listOf("360_0001.JPG" to 100L)
        assertTrue(MediaImporter.isDuplicate("360_0001.JPG", 100L, existing))
        assertFalse(MediaImporter.isDuplicate("360_0001.JPG", 101L, existing))
        assertFalse(MediaImporter.isDuplicate("360_0002.JPG", 100L, existing))
    }
}
