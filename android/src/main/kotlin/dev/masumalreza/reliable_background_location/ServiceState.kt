package dev.masumalreza.reliable_background_location

import android.content.Context
import org.json.JSONObject

/**
 * Everything the service needs to rebuild itself without Dart's help.
 *
 * Android can restart a `START_STICKY` service long after it killed the
 * process, handing `onStartCommand` a null intent. At that moment there is no
 * isolate to ask what the notification said, how often to sample, or which
 * session was in progress — so all of it lives here, on disk, written whenever
 * Dart tells us something new.
 */
internal object ServiceState {
    private const val PREFS = "rbl_state"

    private const val KEY_RUN_ACTIVE = "runActive"
    private const val KEY_SESSION = "sessionState"
    private const val KEY_LAST_ERROR = "lastStartError"
    private const val KEY_LAST_FAILURE = "lastStartFailure"

    private const val KEY_TITLE = "notifTitle"
    private const val KEY_TEXT = "notifText"
    private const val KEY_CHANNEL_ID = "notifChannelId"
    private const val KEY_CHANNEL_NAME = "notifChannelName"
    private const val KEY_CHANNEL_DESC = "notifChannelDesc"
    private const val KEY_SMALL_ICON = "notifSmallIcon"

    private const val KEY_ACCURACY = "accuracy"
    private const val KEY_INTERVAL = "intervalMs"
    private const val KEY_MIN_INTERVAL = "minIntervalMs"
    private const val KEY_MIN_DISTANCE = "minDistance"
    private const val KEY_BUFFER = "bufferWhileDetached"
    private const val KEY_MAX_BUFFERED = "maxBufferedSamples"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether a session was in progress when we last heard from Dart.
     *
     * A sticky restart consults this to tell "the OS is resurrecting an
     * interrupted session" from "the OS is restarting a service the user had
     * already stopped" — only the first should start recording again.
     */
    fun isRunActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RUN_ACTIVE, false)

    fun setRunActive(context: Context, active: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUN_ACTIVE, active).apply()
    }

    /** Persists the caller's own bookkeeping, verbatim. */
    fun saveSessionState(context: Context, state: Map<String, String>?) {
        val editor = prefs(context).edit()
        if (state.isNullOrEmpty()) {
            editor.remove(KEY_SESSION)
        } else {
            editor.putString(KEY_SESSION, JSONObject(state as Map<*, *>).toString())
        }
        editor.apply()
    }

    /** Returns the persisted session state, or null when there is none. */
    fun sessionState(context: Context): Map<String, String>? {
        val raw = prefs(context).getString(KEY_SESSION, null) ?: return null
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun clearSessionState(context: Context) {
        prefs(context).edit().remove(KEY_SESSION).apply()
    }

    /** Records why the most recent start attempt failed, for Dart to read. */
    fun setLastStartError(context: Context, failure: String?, message: String?) {
        prefs(context).edit()
            .putString(KEY_LAST_FAILURE, failure)
            .putString(KEY_LAST_ERROR, message)
            .apply()
    }

    fun lastStartError(context: Context): String? =
        prefs(context).getString(KEY_LAST_ERROR, null)

    fun lastStartFailure(context: Context): String? =
        prefs(context).getString(KEY_LAST_FAILURE, null)

    /**
     * Stores the notification copy.
     *
     * Only overwrites a field when Dart actually supplied one, so an
     * `updateNotification(text: ...)` call does not blank out the title.
     */
    fun saveNotification(context: Context, config: Map<String, Any?>) {
        val editor = prefs(context).edit()
        (config["title"] as? String)?.let { editor.putString(KEY_TITLE, it) }
        (config["text"] as? String)?.let { editor.putString(KEY_TEXT, it) }
        (config["channelId"] as? String)?.let { editor.putString(KEY_CHANNEL_ID, it) }
        (config["channelName"] as? String)?.let { editor.putString(KEY_CHANNEL_NAME, it) }
        (config["channelDescription"] as? String)?.let {
            editor.putString(KEY_CHANNEL_DESC, it)
        }
        if (config.containsKey("smallIconResource")) {
            editor.putString(KEY_SMALL_ICON, config["smallIconResource"] as? String)
        }
        editor.apply()
    }

    fun notificationTitle(context: Context): String =
        prefs(context).getString(KEY_TITLE, "Location tracking") ?: "Location tracking"

    fun notificationText(context: Context): String =
        prefs(context).getString(KEY_TEXT, "Recording your location") ?: ""

    fun channelId(context: Context): String =
        prefs(context).getString(KEY_CHANNEL_ID, "reliable_background_location")
            ?: "reliable_background_location"

    fun channelName(context: Context): String =
        prefs(context).getString(KEY_CHANNEL_NAME, "Location tracking")
            ?: "Location tracking"

    fun channelDescription(context: Context): String =
        prefs(context).getString(KEY_CHANNEL_DESC, "") ?: ""

    fun smallIconResource(context: Context): String? =
        prefs(context).getString(KEY_SMALL_ICON, null)

    /** Stores the sampling configuration. */
    fun saveLocationConfig(context: Context, config: Map<String, Any?>) {
        prefs(context).edit()
            .putString(KEY_ACCURACY, config["accuracy"] as? String ?: "high")
            .putLong(KEY_INTERVAL, (config["intervalMs"] as? Number)?.toLong() ?: 5000L)
            .putLong(
                KEY_MIN_INTERVAL,
                (config["minIntervalMs"] as? Number)?.toLong() ?: 2000L,
            )
            .putFloat(
                KEY_MIN_DISTANCE,
                (config["minDistance"] as? Number)?.toFloat() ?: 0f,
            )
            .putBoolean(KEY_BUFFER, config["bufferWhileDetached"] as? Boolean ?: true)
            .putInt(
                KEY_MAX_BUFFERED,
                (config["maxBufferedSamples"] as? Number)?.toInt() ?: 5000,
            )
            .apply()
    }

    fun accuracy(context: Context): String =
        prefs(context).getString(KEY_ACCURACY, "high") ?: "high"

    fun intervalMs(context: Context): Long = prefs(context).getLong(KEY_INTERVAL, 5000L)

    fun minIntervalMs(context: Context): Long =
        prefs(context).getLong(KEY_MIN_INTERVAL, 2000L)

    fun minDistance(context: Context): Float = prefs(context).getFloat(KEY_MIN_DISTANCE, 0f)

    fun bufferWhileDetached(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUFFER, true)

    fun maxBufferedSamples(context: Context): Int =
        prefs(context).getInt(KEY_MAX_BUFFERED, 5000)
}
