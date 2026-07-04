package com.newroads.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newroads.app.data.RoadGraphRepository
import com.newroads.app.data.RoadHistoryRepository
import com.newroads.app.data.db.AppDatabase
import com.newroads.app.data.db.entity.VisitedSegmentEntity
import com.newroads.app.data.db.entity.WalkRecordEntity
import com.newroads.app.location.TrackingRepository
import com.newroads.app.location.TrackingState
import com.newroads.app.location.getLastLocation
import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.OverpassException
import com.newroads.app.routing.RouteGenerator
import com.newroads.app.routing.RouteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val repository = RoadHistoryRepository(db)
    private val graphRepository = RoadGraphRepository(db.roadGraphCacheDao())

    private val _targetSteps = MutableStateFlow(10000)
    val targetSteps: StateFlow<Int> = _targetSteps.asStateFlow()

    private val _route = MutableStateFlow<RouteResult?>(null)
    val route: StateFlow<RouteResult?> = _route.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _history = MutableStateFlow<List<WalkRecordEntity>>(emptyList())
    val history: StateFlow<List<WalkRecordEntity>> = _history.asStateFlow()

    private val _visitedSegments = MutableStateFlow<List<VisitedSegmentEntity>>(emptyList())
    val visitedSegments: StateFlow<List<VisitedSegmentEntity>> = _visitedSegments.asStateFlow()

    val trackingState: StateFlow<TrackingState> = TrackingRepository.state

    private val _startPoint = MutableStateFlow<GeoPt?>(null)
    val startPoint: StateFlow<GeoPt?> = _startPoint.asStateFlow()

    fun setTargetSteps(steps: Int) {
        _targetSteps.value = steps.coerceIn(1000, 40000)
    }

    fun setStartPoint(point: GeoPt?) {
        _startPoint.value = point
    }

    fun generateRoute() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _route.value = null
            try {
                val origin = _startPoint.value ?: run {
                    _statusMessage.value = "Определяем местоположение..."
                    val location = getLastLocation(getApplication())
                    if (location == null) {
                        _errorMessage.value = "Не удалось определить местоположение"
                        return@launch
                    }
                    GeoPt(location.latitude, location.longitude)
                }
                val targetDistance = _targetSteps.value * STRIDE_METERS
                val radius = targetDistance / 2.0 * 1.2

                val graph = withContext(Dispatchers.IO) {
                    graphRepository.getGraph(origin.lat, origin.lon, radius) {
                        _statusMessage.value = it
                    }
                }

                _statusMessage.value = "Читаем историю пройденных дорог..."
                val visitedKeys = withContext(Dispatchers.IO) { repository.getVisitedKeys() }

                _statusMessage.value = "Строим маршрут..."
                val result = withContext(Dispatchers.IO) {
                    RouteGenerator(graph, visitedKeys, STRIDE_METERS)
                        .generateLoop(origin.lat, origin.lon, targetDistance)
                }

                if (result == null) {
                    _errorMessage.value = "Не удалось построить маршрут в этом районе"
                } else {
                    _route.value = result
                }
            } catch (e: OverpassException) {
                _errorMessage.value = e.message
            } catch (e: Exception) {
                _errorMessage.value = "Ошибка: ${e.message}"
            } finally {
                _isLoading.value = false
                _statusMessage.value = null
            }
        }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _history.value = repository.getAllWalkRecords()
            _visitedSegments.value = repository.getAllVisitedSegments()
        }
    }

    fun finishWalk() {
        viewModelScope.launch {
            val tracked = TrackingRepository.stopAndReset()
            if (tracked.points.size < 2) return@launch

            val plannedRoute = _route.value
            if (plannedRoute != null) {
                repository.markRouteVisited(plannedRoute)
            }

            val record = WalkRecordEntity(
                startedAt = tracked.startedAt,
                endedAt = System.currentTimeMillis(),
                distanceMeters = tracked.distanceMeters,
                steps = (tracked.distanceMeters / STRIDE_METERS).toInt(),
                pathJson = RoadHistoryRepository.encodePoints(tracked.points),
                newRoadFraction = plannedRoute?.newRoadFraction ?: 0.0
            )
            repository.saveWalkRecord(record)
            _route.value = null
            loadHistory()
        }
    }

    companion object {
        const val STRIDE_METERS = 0.75
    }
}
