package com.newroads.app.location

import com.newroads.app.data.RoadHistoryRepository
import com.newroads.app.osm.GeoPt
import com.newroads.app.osm.RoadGraph
import com.newroads.app.osm.segmentKey
import com.newroads.app.routing.MapMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Live "which roads have I walked" accounting for the walk in progress.
 *
 * Roads are credited *during* the walk, not only at the end: [WalkTrackingService] periodically
 * calls [processTrack], which map-matches the track recorded so far and immediately persists any
 * newly covered segments. That both survives the app being killed mid-walk and lets the map show
 * streets turning "walked" as the user goes.
 */
object WalkSession {

    private val mutex = Mutex()

    @Volatile
    private var graph: RoadGraph? = null
    private var previouslyVisited: Set<String> = emptySet()

    /** Segment keys credited during the current walk; the UI uses changes to refresh the map. */
    private val _walkedKeys = MutableStateFlow<Set<String>>(emptySet())
    val walkedKeys: StateFlow<Set<String>> = _walkedKeys.asStateFlow()

    /** Called after route generation so the session has the local road graph to match against. */
    fun prepare(graph: RoadGraph) {
        this.graph = graph
    }

    /** Called when recording starts: snapshots history so "new roads" can be computed honestly. */
    suspend fun begin(repository: RoadHistoryRepository) {
        mutex.withLock {
            previouslyVisited = repository.getVisitedKeys()
            _walkedKeys.value = emptySet()
        }
    }

    /**
     * Map-matches [track] and persists edges that became covered since the last call.
     * Safe to call repeatedly and concurrently; already-credited edges are skipped.
     */
    suspend fun processTrack(track: List<GeoPt>, repository: RoadHistoryRepository) {
        val g = graph ?: return
        if (track.size < 2) return
        mutex.withLock {
            val newlyWalked = MapMatcher.matchWalkedEdges(track, g, excludeKeys = _walkedKeys.value)
            if (newlyWalked.isEmpty()) return
            repository.markEdgesVisited(newlyWalked)
            _walkedKeys.value = _walkedKeys.value +
                newlyWalked.map { segmentKey(it.wayId, it.fromNode, it.toNode) }
        }
    }

    /** Fraction of this walk's credited roads that were walked for the first time ever. */
    fun newRoadFraction(): Double {
        val walked = _walkedKeys.value
        if (walked.isEmpty()) return 0.0
        return walked.count { it !in previouslyVisited }.toDouble() / walked.size
    }

    fun reset() {
        _walkedKeys.value = emptySet()
        previouslyVisited = emptySet()
    }
}
