package com.newroads.app.location

import com.newroads.app.osm.GeoPt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrackingRepositoryTest {

    @Before
    fun reset() {
        TrackingRepository.stopAndReset()
    }

    @Test
    fun addPoint_accumulatesDistance() {
        TrackingRepository.start()
        TrackingRepository.addPoint(GeoPt(55.0, 37.0))
        // ~111m north of the first point.
        TrackingRepository.addPoint(GeoPt(55.001, 37.0))

        val state = TrackingRepository.state.value
        assertEquals(2, state.points.size)
        assertTrue("distance ${state.distanceMeters} should be ~111m", state.distanceMeters in 100.0..125.0)
    }

    @Test
    fun addPoint_ignoredWhenNotTracking() {
        TrackingRepository.addPoint(GeoPt(55.0, 37.0))
        assertTrue(TrackingRepository.state.value.points.isEmpty())
    }

    @Test
    fun stopAndReset_returnsFinalStateAndClears() {
        TrackingRepository.start()
        TrackingRepository.addPoint(GeoPt(55.0, 37.0))
        TrackingRepository.addPoint(GeoPt(55.001, 37.0))

        val finished = TrackingRepository.stopAndReset()
        assertEquals(2, finished.points.size)
        assertTrue(finished.isTracking)

        val after = TrackingRepository.state.value
        assertFalse(after.isTracking)
        assertTrue(after.points.isEmpty())
        assertEquals(0.0, after.distanceMeters, 0.0001)
    }

    @Test
    fun start_whileAlreadyTracking_wouldResetState() {
        // Documents why WalkTrackingService must not call start() on a START_STICKY redelivery:
        // start() unconditionally wipes accumulated points.
        TrackingRepository.start()
        TrackingRepository.addPoint(GeoPt(55.0, 37.0))
        TrackingRepository.start()
        assertTrue(TrackingRepository.state.value.points.isEmpty())
    }
}
