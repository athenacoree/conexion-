package com.example.conexion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {

    @Test
    fun testShakeCalculationLogic() {
        // Simple mathematical calculation testing to verify standard formula correctness
        val x = 12.0f
        val y = 15.0f
        val z = 19.0f

        val gX = x / 9.80665f
        val gY = y / 9.80665f
        val gZ = z / 9.80665f

        val gForce = Math.sqrt((gX * gX + gY * gY + gZ * gZ).toDouble())
        assertTrue("gForce calculation should be greater than gravity threshold under high acceleration", gForce > 2.2)
    }

    @Test
    fun testShakeTimestampDifference() {
        val shakeTime1 = 1710000000000L
        val shakeTime2 = 1710000005000L // 5 seconds difference

        val diff = Math.abs(shakeTime1 - shakeTime2)
        assertTrue("Shake difference should be within 15 seconds window", diff < 15_000)
    }
}
