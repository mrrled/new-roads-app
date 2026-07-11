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
import com.newroads.app.location.WalkSession
import com.newroads.app.location.getLastLocation
import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.OverpassException
import com.newroads.app.routing.ExplorationCalculator
import com.newroads.app.routing.ExplorationData
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

    data class ExplorationUi(
        val isLoading: Boolean = false,
        val error: String? = null,
        val center: GeoPt? = null,
        val data: ExplorationData? = null
    )

    private val _exploration = MutableStateFlow(ExplorationUi())
    val exploration: StateFlow<ExplorationUi> = _exploration.asStateFlow()

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
                // A loop only needs roads within roughly half its length of the start, and because
                // the walk meanders rather than going straight, a good bit less. Keeping the radius
                // tight matters a lot: the Overpass response for a big radius over a dense city can
                // be 10+ MB, which is slow to fetch and can't be cached. 0.8 leaves ample margin.
                val radius = targetDistance / 2.0 * 0.8

                val graph = withContext(Dispatchers.IO) {
                    graphRepository.getGraph(origin.lat, origin.lon, radius) {
                        _statusMessage.value = it
                    }
                }

                if (graph.isEmpty) {
                    val coords = "%.5f, %.5f".format(origin.lat, origin.lon)
                    _errorMessage.value = "Не нашли ни одной дороги в радиусе ${radius.toInt()} м от точки " +
                        "($coords). Проверьте, что это адекватное местоположение (не посреди воды и не " +
                        "координаты по умолчанию у эмулятора), либо выберите другую точку старта на карте."
                    return@launch
                }

                // The live walk session matches the GPS track against this graph while recording.
                WalkSession.prepare(graph)

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

    /**
     * Computes the "explored streets" stats and fog-of-war edges around the start point
     * (or current location) within [EXPLORATION_RADIUS_METERS].
     */
    fun loadExploration() {
        viewModelScope.launch {
            _exploration.value = ExplorationUi(isLoading = true)
            try {
                val origin = _startPoint.value ?: getLastLocation(getApplication())?.let {
                    GeoPt(it.latitude, it.longitude)
                }
                if (origin == null) {
                    _exploration.value = ExplorationUi(error = "Не удалось определить местоположение")
                    return@launch
                }
                val graph = withContext(Dispatchers.IO) {
                    graphRepository.getGraph(origin.lat, origin.lon, EXPLORATION_RADIUS_METERS) { }
                }
                val visitedKeys = withContext(Dispatchers.IO) { repository.getVisitedKeys() }
                val data = withContext(Dispatchers.Default) {
                    ExplorationCalculator.compute(graph, visitedKeys)
                }
                _exploration.value = ExplorationUi(center = origin, data = data)
            } catch (e: OverpassException) {
                _exploration.value = ExplorationUi(error = e.message)
            } catch (e: Exception) {
                _exploration.value = ExplorationUi(error = "Ошибка: ${e.message}")
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
            if (tracked.points.size < 2) {
                WalkSession.reset()
                return@launch
            }

            // Roads were being credited live during the walk; run one final matching pass over the
            // complete track to catch the tail walked since the last periodic pass.
            val newRoadFraction = withContext(Dispatchers.Default) {
                WalkSession.processTrack(tracked.points, repository)
                WalkSession.newRoadFraction()
            }
            WalkSession.reset()

            val record = WalkRecordEntity(
                startedAt = tracked.startedAt,
                endedAt = System.currentTimeMillis(),
                distanceMeters = tracked.distanceMeters,
                steps = (tracked.distanceMeters / STRIDE_METERS).toInt(),
                pathJson = RoadHistoryRepository.encodePoints(tracked.points),
                newRoadFraction = newRoadFraction
            )
            repository.saveWalkRecord(record)
            _route.value = null
            loadHistory()
        }
    }

    companion object {
        const val STRIDE_METERS = 0.75

        // Keep the exploration area modest: the stat reads naturally as "streets around you", and
        // the Overpass response for ~1km over a dense city stays small enough for the local cache.
        const val EXPLORATION_RADIUS_METERS = 1000.0
    }
}
