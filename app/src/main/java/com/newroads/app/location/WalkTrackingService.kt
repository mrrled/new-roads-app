package com.newroads.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.newroads.app.data.RoadHistoryRepository
import com.newroads.app.data.db.AppDatabase
import com.newroads.app.osm.GeoPt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground service that records the user's GPS path while a walk is in progress. */
class WalkTrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var historyRepository: RoadHistoryRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var matchingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        historyRepository = RoadHistoryRepository(AppDatabase.get(applicationContext))
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                TrackingRepository.addPoint(GeoPt(location.latitude, location.longitude))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14+ throws a SecurityException when starting a location-type foreground
        // service without a granted location permission — bail out instead of crashing.
        if (!hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY means the system may re-deliver onStartCommand mid-walk after killing the
        // process; only reset the walk state when a walk isn't already being recorded.
        if (!TrackingRepository.state.value.isTracking) {
            TrackingRepository.start()
            scope.launch { WalkSession.begin(historyRepository) }
        }
        startLocationUpdates()
        startLiveMatching()
        return START_STICKY
    }

    /**
     * Credits walked roads *during* the walk: every [MATCH_INTERVAL_MS] the track so far is
     * map-matched and newly covered segments are persisted immediately, so nothing is lost if the
     * app dies mid-walk and the map can show progress live.
     */
    private fun startLiveMatching() {
        if (matchingJob?.isActive == true) return
        matchingJob = scope.launch {
            while (isActive) {
                delay(MATCH_INTERVAL_MS)
                WalkSession.processTrack(TrackingRepository.state.value.points, historyRepository)
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
    }

    override fun onDestroy() {
        fusedClient.removeLocationUpdates(locationCallback)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "walk_tracking"
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(channelId, "Запись маршрута", NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Запись прогулки")
            .setContentText("Отслеживаем ваш маршрут")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val MATCH_INTERVAL_MS = 15_000L
    }
}
