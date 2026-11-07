package com.simoneluconi.revive360

import com.simoneluconi.revive360.ui.Features
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera must stay on its original firmware: the app has no way to change it. */
class StockSafetyTest {
    private val forbidden = listOf(
        "fwupgrade",
        "smr210.bin",
        "smc200.bin",
        "debug/upload",
        "debug/restart",
        "FirmwareManager",
        "FirmwareScreen",
        "Screen.Firmware",
        "INSTALL_GUIDE",
    )

    @Test
    fun appSourcesHaveNoFirmwarePath() {
        val sources = File("src/main").walkTopDown().filter { it.extension in setOf("kt", "java", "xml") }.toList()
        assertTrue("sources not found from ${File(".").absolutePath}", sources.size > 50)
        val hits = sources.flatMap { file ->
            val text = file.readText()
            forbidden.filter { it in text }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun unvalidatedFeaturesStayHidden() {
        assertFalse(Features.EXPERIMENTAL)
    }
}
