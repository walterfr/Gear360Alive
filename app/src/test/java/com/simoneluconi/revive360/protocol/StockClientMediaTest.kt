package com.simoneluconi.revive360.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StockClientMediaTest {

    @Test
    fun parseBrowseItemsExtractsMediaThumbAndScreenUrls() {
        val sampleBrowseXml = """
        <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
            <Result>&lt;DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"&gt;
                &lt;item id="360_0314" parentID="8" restricted="1"&gt;
                    &lt;dc:title&gt;360_0314.mp4&lt;/dc:title&gt;
                    &lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;
                    &lt;res size="911012"&gt;http://192.168.43.1:7679/smp01380130001000314.mp4&lt;/res&gt;
                    &lt;res&gt;http://192.168.43.1:7679/smpthumbvideo01390130001000314.jpg&lt;/res&gt;
                    &lt;res&gt;http://192.168.43.1:7679/smpscreenvideo01400130001000314.jpg&lt;/res&gt;
                &lt;/item&gt;
                &lt;item id="SAM_0307" parentID="8" restricted="1"&gt;
                    &lt;dc:title&gt;SAM_0307.jpg&lt;/dc:title&gt;
                    &lt;upnp:class&gt;object.item.imageItem&lt;/upnp:class&gt;
                    &lt;res size="5764246"&gt;http://192.168.43.1:7679/smp01030130001000307.jpg&lt;/res&gt;
                    &lt;res&gt;http://192.168.43.1:7679/smpthumb01040130001000307.jpg&lt;/res&gt;
                    &lt;res&gt;http://192.168.43.1:7679/smpscreen01050130001000307.jpg&lt;/res&gt;
                &lt;/item&gt;
            &lt;/DIDL-Lite&gt;</Result>
            <NumberReturned>2</NumberReturned>
            <TotalMatches>2</TotalMatches>
            <UpdateID>1</UpdateID>
        </u:BrowseResponse>
        """.trimIndent()

        val items = StockClient.parseBrowseItems(sampleBrowseXml)
        assertEquals(2, items.size)

        // Item 1: Video
        val video = items[0]
        assertEquals("360_0314", video.id)
        assertEquals("360_0314.mp4", video.title)
        assertEquals("http://192.168.43.1:7679/smp01380130001000314.mp4", video.mediaUrl)
        assertEquals("http://192.168.43.1:7679/smpthumbvideo01390130001000314.jpg", video.thumbUrl)
        assertEquals("http://192.168.43.1:7679/smpscreenvideo01400130001000314.jpg", video.screenUrl)
        assertEquals(911012L, video.sizeBytes)
        assertEquals(FileKind.VIDEO, video.kind)

        // Item 2: Photo
        val photo = items[1]
        assertEquals("SAM_0307", photo.id)
        assertEquals("SAM_0307.jpg", photo.title)
        assertEquals("http://192.168.43.1:7679/smp01030130001000307.jpg", photo.mediaUrl)
        assertEquals("http://192.168.43.1:7679/smpthumb01040130001000307.jpg", photo.thumbUrl)
        assertEquals(5764246L, photo.sizeBytes)
        assertEquals(FileKind.PHOTO, photo.kind)
    }
}
