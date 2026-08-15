package com.example.conexion

import org.junit.Assert.*
import org.junit.Test

class MapManagerTest {

    @Test
    fun testPointInPolygon() {
        val polygon = listOf(
            Pair(-82.5, 23.0),
            Pair(-82.0, 23.0),
            Pair(-82.0, 23.5),
            Pair(-82.5, 23.5)
        )

        val mapManager = MapManagerDummy()
        assertTrue(mapManager.checkPointInPolygon(-82.2, 23.2, polygon))
        assertFalse(mapManager.checkPointInPolygon(-81.5, 23.2, polygon))
    }

    private class MapManagerDummy {
        fun checkPointInPolygon(x: Double, y: Double, polygon: List<Pair<Double, Double>>): Boolean {
            var inside = false
            var j = polygon.size - 1
            for (i in polygon.indices) {
                val xi = polygon[i].first
                val yi = polygon[i].second
                val xj = polygon[j].first
                val yj = polygon[j].second

                val intersect = ((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }
    }
}
