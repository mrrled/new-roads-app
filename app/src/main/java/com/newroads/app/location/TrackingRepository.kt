package com.newroads.app.location

import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.haversineMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackingState(
    val isTracking: Boolean = false,
    val startedAt: Long = 0L,
    val points: List<GeoPt> = emptyList(),
    val distanceMeters: Double = 0.0
)

/** In-process shared state between [WalkTrackingService] and the UI layer. */
object TrackingRepository {
    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    fun start() {
        _state.value = TrackingState(isTracking = true, startedAt = System.currentTimeMillis())
    }

    fun addPoint(point: GeoPt) {
        val current = _state.value
        if (!current.isTracking) return
        val addedDistance = current.points.lastOrNull()?.let {
            haversineMeters(it.lat, it.lon, point.lat, point.lon)
        } ?: 0.0
        _state.value = current.copy(
            points = current.points + point,
            distanceMeters = current.distanceMeters + addedDistance
        )
    }

    fun stopAndReset(): TrackingState {
        val last = _state.value
        _state.value = TrackingState()
        return last
    }
}
