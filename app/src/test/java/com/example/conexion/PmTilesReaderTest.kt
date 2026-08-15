package com.example.conexion

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PmTilesReaderTest {

    @Test
    fun testPmTilesReader() {
        val mapFile = File("mapas/Havana/Marianao.pmtiles")
        if (mapFile.exists()) {
            val reader = PmTilesReader(mapFile)
            // Marianao.pmtiles zoom 0 tile (0, 0, 0)
            val tileData = reader.getTile(0, 0, 0)
            assertNotNull("Tile 0/0/0 should exist in Marianao.pmtiles", tileData)
            assertTrue("Tile 0/0/0 data size > 0", tileData!!.isNotEmpty())
        }
    }
}
