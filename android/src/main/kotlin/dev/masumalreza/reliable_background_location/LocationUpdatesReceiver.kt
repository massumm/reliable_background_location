package dev.masumalreza.reliable_background_location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.LocationResult

/**
 * Receives fixes that Play Services delivers to the registered `PendingIntent`.
 *
 * Android will start this app's process solely to deliver such a broadcast, so
 * this receiver can run when the Flutter engine does not exist. It therefore
 * decides between two destinations on every fix:
 *
 *  - a live [LocationBridge] event sink, when an isolate is listening;
 *  - [LocationBuffer] on disk, when one is not.
 *
 * Getting that fallback right is what turns a track with a hole in it into a
 * continuous one. A receiver that assumed the engine existed would drop every
 * fix collected after the app was killed.
 */
class LocationUpdatesReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LocationResult.hasResult(intent)) return

        val result = LocationResult.extractResult(intent) ?: return
        val maxSamples = ServiceState.maxBufferedSamples(context)

        for (location in result.locations) {
            try {
                // The sink is only non-null while an isolate is attached AND
                // listening. Checking it per fix rather than once per broadcast
                // matters: a batch delivered during Doze can straddle the
                // moment the app is resumed.
                val sink = LocationBridge.activeSink
                if (sink != null) {
                    sink(LocationBuffer.toEventMap(location))
                } else {
                    LocationBuffer.append(context, location, maxSamples)
                }
            } catch (e: Exception) {
                // Never let one bad fix kill the receiver — an exception here
                // would cost every later fix too.
                Log.w(TAG, "could not handle fix", e)
            }
        }
    }

    private companion object {
        const val TAG = "RBL.Receiver"
    }
}
