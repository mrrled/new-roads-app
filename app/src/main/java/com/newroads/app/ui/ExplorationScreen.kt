package com.newroads.app.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.newroads.app.osm.GraphEdge
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay

/**
 * "Fog of war" map: the base map is dimmed and desaturated, unexplored streets are pale, and
 * every street the user has actually walked glows bright — plus the explored-percentage stat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorationScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val exploration by viewModel.exploration.collectAsState()
    var hasCentered by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadExploration() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Исследование района") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            when {
                exploration.isLoading -> {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Загружаем карту улиц...")
                    }
                }

                exploration.error != null -> {
                    Text(
                        exploration.error!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                exploration.data != null -> {
                    val data = exploration.data!!
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Исследовано ${"%.1f".format(data.fraction * 100)}% улиц",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { data.fraction.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Пройдено ${"%.1f".format(data.walkedMeters / 1000)} из " +
                                    "${"%.1f".format(data.totalMeters / 1000)} км улиц в радиусе 1 км",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setMultiTouchControls(true)
                                controller.setZoom(15.5)
                                // Dim + desaturate the base map so walked streets glow through the fog.
                                val matrix = ColorMatrix().apply { setSaturation(0.25f) }
                                matrix.postConcat(
                                    ColorMatrix(
                                        floatArrayOf(
                                            0.55f, 0f, 0f, 0f, 0f,
                                            0f, 0.55f, 0f, 0f, 0f,
                                            0f, 0f, 0.55f, 0f, 0f,
                                            0f, 0f, 0f, 1f, 0f
                                        )
                                    )
                                )
                                overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(matrix))
                            }
                        },
                        update = { mapView ->
                            mapView.overlays.clear()
                            mapView.overlays.add(RoadsFogOverlay(data.unwalkedEdges, data.walkedEdges))

                            exploration.center?.let { c ->
                                mapView.overlays.add(Marker(mapView).apply {
                                    position = GeoPoint(c.lat, c.lon)
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                })
                                if (!hasCentered) {
                                    mapView.controller.setCenter(GeoPoint(c.lat, c.lon))
                                    hasCentered = true
                                }
                            }
                            mapView.invalidate()
                        }
                    )
                }
            }
        }
    }
}

/**
 * Draws the whole street network in one canvas pass (thousands of Polyline overlays would be far
 * too slow): unexplored streets pale and thin, walked streets bright and bold.
 */
private class RoadsFogOverlay(
    private val unwalked: List<GraphEdge>,
    private val walked: List<GraphEdge>
) : Overlay() {

    private val dimPaint = Paint().apply {
        color = 0x5FFFFFFF
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val brightPaint = Paint().apply {
        color = Color.rgb(255, 167, 38) // amber glow
        strokeWidth = 9f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        val bounds = mapView.boundingBox.increaseByScale(1.3f)
        val pixel = Point()
        val geo = GeoPoint(0.0, 0.0)

        fun drawEdges(edges: List<GraphEdge>, paint: Paint) {
            val path = Path()
            for (edge in edges) {
                val first = edge.points.first()
                val last = edge.points.last()
                // Cheap viewport culling by the edge's endpoints.
                if (!bounds.contains(first.lat, first.lon) && !bounds.contains(last.lat, last.lon)) continue

                var moved = false
                for (pt in edge.points) {
                    geo.setCoords(pt.lat, pt.lon)
                    projection.toPixels(geo, pixel)
                    if (!moved) {
                        path.moveTo(pixel.x.toFloat(), pixel.y.toFloat())
                        moved = true
                    } else {
                        path.lineTo(pixel.x.toFloat(), pixel.y.toFloat())
                    }
                }
            }
            canvas.drawPath(path, paint)
        }

        drawEdges(unwalked, dimPaint)
        drawEdges(walked, brightPaint)
    }
}
