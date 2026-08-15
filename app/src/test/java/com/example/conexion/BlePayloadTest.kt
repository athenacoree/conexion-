package com.example.conexion

import org.junit.Assert.*
import org.junit.Test

class BlePayloadTest {

    @Test
    fun testBlePayloadEncodingAndDecodingWithGps() {
        val service = BackgroundDiscoveryService()
        val token = "A1B2C3D4E5F6"
        val userName = "Device1"
        val lat = 23.1136
        val lon = -82.3666
        val avatarIndex = 2

        val payload = service.buildManufacturerData(
            state = 1,
            sessionToken = token,
            avatarIndex = avatarIndex,
            userName = userName,
            latitude = lat,
            longitude = lon
        )

        assertNotNull(payload)
        assertTrue(payload.size >= 16)

        val decodedPeer = service.parseManufacturerData(payload, rssi = -55, distanceMeters = 1.5)
        assertNotNull(decodedPeer)
        assertEquals(token, decodedPeer!!.sessionToken)
        assertEquals(userName, decodedPeer.userName)
        assertEquals(1, decodedPeer.state)
        assertEquals(avatarIndex, decodedPeer.avatarIndex)
        assertNotNull(decodedPeer.latitude)
        assertNotNull(decodedPeer.longitude)
        assertEquals(lat, decodedPeer.latitude!!, 0.0001)
        assertEquals(lon, decodedPeer.longitude!!, 0.0001)
    }

    @Test
    fun testBlePayloadEncodingAndDecodingWithoutGps() {
        val service = BackgroundDiscoveryService()
        val token = "1234567890AB"
        val userName = "NoGPS"
        val avatarIndex = 0

        val payload = service.buildManufacturerData(
            state = 0,
            sessionToken = token,
            avatarIndex = avatarIndex,
            userName = userName,
            latitude = null,
            longitude = null
        )

        val decodedPeer = service.parseManufacturerData(payload, rssi = -70, distanceMeters = 3.5)
        assertNotNull(decodedPeer)
        assertEquals(token, decodedPeer!!.sessionToken)
        assertEquals(userName, decodedPeer.userName)
        assertNull(decodedPeer.latitude)
        assertNull(decodedPeer.longitude)
    }

    @Test
    fun testRssiFallbackLocationCalculation() {
        val userLat = 23.1136
        val userLon = -82.3666
        val distanceMeters = 5.0
        val token = "ABCDEF123456"

        val bearingDeg = ((token.hashCode() and 0x7FFFFFFF) % 360).toDouble()
        val bearingRad = Math.toRadians(bearingDeg)
        val dLat = (distanceMeters * Math.cos(bearingRad)) / 111000.0
        val dLon = (distanceMeters * Math.sin(bearingRad)) / (111000.0 * Math.cos(Math.toRadians(userLat)))
        val approxLat = userLat + dLat
        val approxLon = userLon + dLon

        assertTrue(approxLat != userLat || approxLon != userLon)

        val latDiffMeters = (approxLat - userLat) * 111000.0
        val lonDiffMeters = (approxLon - userLon) * 111000.0 * Math.cos(Math.toRadians(userLat))
        val calcDistance = Math.sqrt(latDiffMeters * latDiffMeters + lonDiffMeters * lonDiffMeters)
        assertEquals(distanceMeters, calcDistance, 0.1)
    }
}
