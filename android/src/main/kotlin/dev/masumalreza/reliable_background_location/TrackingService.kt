package dev.masumalreza.reliable_background_location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * The foreground service that keeps location recording alive.
 *
 * Three separate platform behaviours have to be handled here, and missing any
 * one of them produces a track with a hole in it:
 *
 *  1. A foreground service does **not** keep the Dart isolate running. Doze
 *     suspends it regardless, so a partial wakelock is held for the session.
 *  2. `startForeground` can be refused, and refusal kills the service rather
 *     than merely hiding its notification. The reason is mapped to a
 *     [StartFailure] name and persisted for Dart to read.
 *  3. `START_STICKY` restarts this service with a null intent, with no isolate
 *     alive. Everything needed to rebuild is read from [ServiceState].
 */
class TrackingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }

        // A null intent means the OS restarted us on its own. Dart is gone, so
        // consult disk: only resume when a session was genuinely in progress.
        if (intent == null && !ServiceState.isRunActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!startForegroundSafely()) {
            // Having been launched via startForegroundService, we are obliged
            // to call startForeground within a few seconds or the app is
            // killed with an ANR. Since that call is the thing that failed,
            // the only clean exit is to stop immediately.
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()
        requestLocationUpdates()
        ServiceState.setRunActive(this, true)

        // START_STICKY so the OS brings the service back if it kills us.
        return START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    /**
     * Calls `startForeground`, translating any refusal into a persisted
     * [StartFailure] name.
     *
     * Returns true only when the service actually reached foreground state.
     */
    private fun startForegroundSafely(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            ServiceState.setLastStartError(this, null, null)
            isForegroundActive = true
            true
        } catch (e: Throwable) {
            isForegroundActive = false
            val failure = classify(e)
            Log.w(TAG, "startForeground refused: $failure", e)
            ServiceState.setLastStartError(this, failure, e.toString())
            false
        }
    }

    /**
     * Maps a `startForeground` throwable onto a [StartFailure] name.
     *
     * The exception types are checked by name because
     * `ForegroundServiceStartNotAllowedException` only exists from API 31, and
     * `MissingForegroundServiceTypeException` from API 34 — referencing either
     * directly would not compile against a lower `compileSdk`, and catching
     * only the base type would lose the distinction Dart needs.
     */
    private fun classify(e: Throwable): String {
        val name = e.javaClass.simpleName
        val message = e.message.orEmpty()
        return when {
            name == "ForegroundServiceStartNotAllowedException" ->
                "notAllowedFromBackground"
            e is SecurityException && message.contains("location", true) ->
                "locationPermissionMissing"
            e is SecurityException -> "notificationPermissionMissing"
            message.contains("permission", true) && message.contains("location", true) ->
                "locationPermissionMissing"
            else -> "unknown"
        }
    }

    private fun buildNotification(): Notification {
        val channelId = ServiceState.channelId(this)
        ensureChannel(channelId)

        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(ServiceState.notificationTitle(this))
            .setContentText(ServiceState.notificationText(this))
            .setSmallIcon(resolveSmallIcon())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    /**
     * Resolves the caller's drawable, falling back to the app icon.
     *
     * A notification with an invalid icon resource throws on some OEM builds,
     * which would turn a cosmetic mistake into a failed start.
     */
    private fun resolveSmallIcon(): Int {
        val name = ServiceState.smallIconResource(this)
        if (!name.isNullOrBlank()) {
            val id = resources.getIdentifier(name, "drawable", packageName)
            if (id != 0) return id
            val mipmap = resources.getIdentifier(name, "mipmap", packageName)
            if (mipmap != 0) return mipmap
            Log.w(TAG, "smallIconResource '$name' not found; using the app icon")
        }
        return applicationInfo.icon
    }

    private fun ensureChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return

        val channel = NotificationChannel(
            channelId,
            ServiceState.channelName(this),
            // Low: the notification must exist, but it should never make a
            // sound or intrude. Anything higher reads as spam to the user.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = ServiceState.channelDescription(this@TrackingService)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    // ------------------------------------------------------------------ wakelock

    /**
     * Holds a partial wakelock for the whole session.
     *
     * Deliberately untimed. A timed acquire renewed on each fix looks safer but
     * is worse: miss a fix during Doze and the lock expires, the isolate is
     * suspended, and no further fix arrives to renew it — the failure is
     * self-sustaining.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .also { it.acquire() }
        } catch (e: Exception) {
            // Tracking without a wakelock still beats no tracking.
            Log.w(TAG, "could not acquire wakelock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "could not release wakelock", e)
        }
        wakeLock = null
    }

    // ------------------------------------------------------------------ location

    /**
     * Registers for fixes, always re-registering rather than assuming a
     * previous registration survived.
     *
     * Play Services usually keeps a `PendingIntent` registration across a
     * service restart, but not reliably. Skipping this on a sticky restart
     * produces the worst possible state: a healthy-looking service, a visible
     * notification, and no fixes at all.
     */
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(ServiceState.intervalMs(this))
            .setPriority(priority(ServiceState.accuracy(this)))
            .setMinUpdateIntervalMillis(ServiceState.minIntervalMs(this))
            .setMinUpdateDistanceMeters(ServiceState.minDistance(this))
            .build()

        try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, LocationPendingIntent.of(this))
        } catch (e: SecurityException) {
            // Permission was revoked while we were in the background.
            Log.w(TAG, "location permission missing", e)
            ServiceState.setLastStartError(this, "locationPermissionMissing", e.toString())
            shutDown()
        }
    }

    private fun priority(accuracy: String): Int = when (accuracy) {
        "balanced" -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        "low" -> Priority.PRIORITY_LOW_POWER
        "passive" -> Priority.PRIORITY_PASSIVE
        else -> Priority.PRIORITY_HIGH_ACCURACY
    }

    private fun shutDown() {
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .removeLocationUpdates(LocationPendingIntent.of(this))
        } catch (e: Exception) {
            Log.w(TAG, "could not remove location updates", e)
        }
        ServiceState.setRunActive(this, false)
        releaseWakeLock()
        isForegroundActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val TAG = "RBL.Service"
        private const val NOTIFICATION_ID = 0x5242_4C01
        private const val WAKE_LOCK_TAG = "reliable_background_location:session"

        internal const val ACTION_STOP = "dev.masumalreza.rbl.STOP"

        /**
         * Whether the service reached foreground state.
         *
         * Read by Dart through `isRunning()`. A static is adequate because the
         * only case where it is stale — the process having been restarted — is
         * also a case where no service is running, and false is then correct.
         */
        @Volatile
        internal var isForegroundActive: Boolean = false
            private set

        internal fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        internal fun stop(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (e: Exception) {
                // Already dead, which is the state we wanted.
                Log.w(TAG, "stop dispatch failed", e)
            }
        }
    }
}

/** The single `PendingIntent` Play Services delivers fixes to. */
internal object LocationPendingIntent {
    private const val REQUEST_CODE = 0x5242_4C02

    /**
     * Returns a stable `PendingIntent`.
     *
     * The request code and intent must match exactly for `removeLocationUpdates`
     * to cancel the right registration — a mismatch leaves Play Services
     * feeding a receiver nobody is listening to, draining the battery of a user
     * who thinks they stopped tracking.
     */
    fun of(context: Context): PendingIntent {
        val intent = Intent(context, LocationUpdatesReceiver::class.java)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
