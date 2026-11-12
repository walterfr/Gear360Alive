package com.simoneluconi.revive360.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneItemNameTest {
    @Test
    fun aSystemCopyKeepsWhatTheFileIs() {
        assertTrue(PhoneItem.isStitchedName("360_0295_360.mp4"))
        assertTrue(PhoneItem.isStitchedName("360_0295_360 (1).mp4"))
        assertTrue(PhoneItem.isStitchedName("360_0303_360 (12).jpg"))
        assertFalse(PhoneItem.isStitchedName("360_0295.MP4"))
        assertFalse(PhoneItem.isStitchedName("360_0295 (1).MP4"))

        assertTrue(PhoneItem.isOriginalName("360_0295.MP4"))
        assertTrue(PhoneItem.isOriginalName("360_0295 (1).MP4"))
        assertTrue(PhoneItem.isOriginalName("SAM_0299.JPG"))
        assertFalse(PhoneItem.isOriginalName("360_0303_360_view_132439.jpg"))
    }

    @Test
    fun aFileSavedBeforeIsRecognisedWhateverItsCaseOrCopyNumber() {
        val listed = PhoneItem.savedKey("SAM_0307.jpg")
        assertEquals(listed, PhoneItem.savedKey("SAM_0307.JPG"))
        assertEquals(listed, PhoneItem.savedKey("SAM_0307 (1).jpg"))
        assertEquals(PhoneItem.savedKey("360_0314.mp4"), PhoneItem.savedKey("360_0314.MP4"))
        assertNotEquals(listed, PhoneItem.savedKey("SAM_0308.jpg"))
        // The stitched copy is another file
        assertNotEquals(PhoneItem.savedKey("360_0303.JPG"), PhoneItem.savedKey("360_0303_360.jpg"))
    }
}
