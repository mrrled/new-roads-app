package com.newroads.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.newroads.app.data.RoadHistoryRepository
import com.newroads.app.location.WalkTrackingService
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val route by viewModel.route.collectAsState()
    val tracking by viewModel.trackingState.collectAsState()
    val visitedSegments by viewModel.visitedSegments.collectAsState()

    LaunchedEffect(Unit) { viewModel.loadHistory() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Маршрут") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        setMultiTouchControls(true)
                        controller.setZoom(16.0)
                    }
                },
                update = { mapView ->
                    mapView.overlays.clear()

                    visitedSegments.forEach { segment ->
                        val pts = RoadHistoryRepository.decodePoints(segment.pointsJson)
                        if (pts.size >= 2) {
                            mapView.overlays.add(Polyline(mapView).apply {
                                setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                                outlinePaint.color = 0x99888888.toInt()
                                outlinePaint.strokeWidth = 6f
                            })
                        }
                    }

                    route?.let { r ->
                        mapView.overlays.add(Polyline(mapView).apply {
                            setPoints(r.points.map { GeoPoint(it.lat, it.lon) })
                            outlinePaint.color = 0xFF2196F3.toInt()
                            outlinePaint.strokeWidth = 10f
                        })
                        if (r.points.isNotEmpty()) {
                            mapView.controller.setCenter(GeoPoint(r.points.first().lat, r.points.first().lon))
                        }
                    }

                    if (tracking.points.size >= 2) {
                        mapView.overlays.add(Polyline(mapView).apply {
                            setPoints(tracking.points.map { GeoPoint(it.lat, it.lon) })
                            outlinePaint.color = 0xFF4CAF50.toInt()
                            outlinePaint.strokeWidth = 10f
                        })
                    }

                    mapView.invalidate()
                }
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (!tracking.isTracking) {
                    Button(onClick = {
                        context.startForegroundService(Intent(context, WalkTrackingService::class.java))
                    }) { Text("Начать запись") }
                } else {
                    Button(onClick = {
                        context.stopService(Intent(context, WalkTrackingService::class.java))
                        viewModel.finishWalk()
                        onBack()
                    }) { Text("Закончить прогулку") }
                }
            }
        }
    }
}
