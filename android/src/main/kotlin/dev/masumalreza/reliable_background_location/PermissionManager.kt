package dev.masumalreza.reliable_background_location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.PluginRegistry

/**
 * Requests exactly the runtime permissions this service needs.
 *
 * The plugin owns this rather than leaving it to a general permission package
 * because the ordering is not obvious and getting it wrong fails quietly:
 * from Android 11, asking for background location in the same request as
 * foreground location returns denied without ever showing the user a prompt.
 */
internal class PermissionManager : PluginRegistry.RequestPermissionsResultListener {

    private var pending: ((PermissionStatus) -> Unit)? = null
    private var activity: Activity? = null
    private var stage = Stage.IDLE

    private enum class Stage { IDLE, FOREGROUND, BACKGROUND }

    fun attach(activity: Activity?) {
        this.activity = activity
    }

    /** Current state, without prompting for anything. */
    fun check(context: Context): PermissionStatus = PermissionStatus(
        fineLocation = granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
        coarseLocation = granted(context, Manifest.permission.ACCESS_COARSE_LOCATION),
        backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            // Before API 29 there was no separate background permission:
            // foreground location covered both.
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        },
        notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // POST_NOTIFICATIONS did not exist and was never denied.
            true
        },
    )

    /**
     * Requests foreground location and notifications, then background location
     * as a second, separate prompt.
     *
     * [onResult] fires once, after the sequence finishes or is denied.
     */
    fun request(context: Context, onResult: (PermissionStatus) -> Unit) {
        val current = check(context)
        if (current.isComplete) {
            onResult(current)
            return
        }

        val host = activity
        if (host == null) {
            // No Activity means no prompt is possible — a background isolate,
            // typically. Report the truth instead of hanging.
            onResult(current)
            return
        }

        pending = onResult

        val first = buildList {
            if (!current.fineLocation) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !current.notifications
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (first.isNotEmpty()) {
            stage = Stage.FOREGROUND
            ActivityCompat.requestPermissions(host, first.toTypedArray(), REQUEST_CODE)
        } else {
            requestBackground(context, host)
        }
    }

    private fun requestBackground(context: Context, host: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            finish(context)
            return
        }
        if (granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            finish(context)
            return
        }
        stage = Stage.BACKGROUND
        ActivityCompat.requestPermissions(
            host,
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            REQUEST_CODE,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean {
        if (requestCode != REQUEST_CODE) return false
        val host = activity ?: return true
        val context: Context = host

        when (stage) {
            Stage.FOREGROUND -> {
                // Only escalate to background location once foreground has
                // actually been granted; asking otherwise is refused outright.
                if (granted(context, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    requestBackground(context, host)
                } else {
                    finish(context)
                }
            }
            else -> finish(context)
        }
        return true
    }

    private fun finish(context: Context) {
        stage = Stage.IDLE
        pending?.invoke(check(context))
        pending = null
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUEST_CODE = 0x5242_4C03
    }
}

/** What the service is currently allowed to do. */
internal data class PermissionStatus(
    val fineLocation: Boolean,
    val coarseLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
) {
    /** Whether tracking can start at all. */
    val canStart: Boolean get() = fineLocation && notifications

    /** Whether tracking will also survive the app leaving the screen. */
    val isComplete: Boolean get() = canStart && backgroundLocation

    fun toMap(): Map<String, Any?> = mapOf(
        "fineLocation" to fineLocation,
        "coarseLocation" to coarseLocation,
        "backgroundLocation" to backgroundLocation,
        "notifications" to notifications,
        "canStart" to canStart,
        "isComplete" to isComplete,
    )
}
