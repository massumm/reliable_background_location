package dev.masumalreza.reliable_background_location

import android.content.Context
import android.location.Location
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Append-only disk store for fixes that arrive with no Dart isolate to receive
 * them.
 *
 * This class is deliberately free of Flutter and Play Services types. It runs
 * from [LocationUpdatesReceiver] in a process Android spun up purely to deliver
 * a broadcast, where the Flutter engine does not exist and `Application`
 * may not have finished initialising. A `Context` and a file is all it may
 * assume.
 *
 * The file holds one JSON object per line, so appending a fix costs one short
 * write rather than a re-serialisation of everything collected so far.
 */
internal object LocationBuffer {
    private const val TAG = "RBL.Buffer"
    private const val FILE_NAME = "rbl_buffered_locations.jsonl"
    private const val PREFS = "rbl_buffer"
    private const val KEY_COUNT = "count"

    /** Guards against two broadcasts being delivered concurrently. */
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /**
     * Appends [location], dropping the oldest entries once [maxSamples] is
     * exceeded.
     *
     * Never throws: losing a fix is bad, but crashing the receiver would stop
     * every later fix as well.
     */
    fun append(context: Context, location: Location, maxSamples: Int) {
        synchronized(lock) {
            try {
                file(context).appendText(toJson(location).toString() + "\n")

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val count = prefs.getInt(KEY_COUNT, 0) + 1
                prefs.edit().putInt(KEY_COUNT, count).apply()

                // Trimming rewrites the file, so only do it when the cap is
                // actually breached rather than on every append.
                if (count > maxSamples) trimLocked(context, maxSamples)
            } catch (e: Exception) {
                Log.w(TAG, "could not buffer fix", e)
            }
        }
    }

    /**
     * Returns every buffered fix, oldest first, and empties the store.
     *
     * Reading and clearing are one operation on purpose: a partial read that
     * left the file in place would hand the same fixes to Dart twice.
     */
    fun drain(context: Context): List<Map<String, Any?>> {
        synchronized(lock) {
            val f = file(context)
            if (!f.exists()) return emptyList()

            val samples = mutableListOf<Map<String, Any?>>()
            try {
                f.forEachLine { line ->
                    if (line.isNotBlank()) {
                        try {
                            samples.add(fromJson(JSONObject(line)))
                        } catch (e: Exception) {
                            // One corrupt line — a write interrupted by a kill,
                            // most likely — must not discard the rest.
                            Log.w(TAG, "skipping malformed buffered fix", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not read buffer", e)
            }

            clearLocked(context)
            return samples
        }
    }

    /** Discards buffered fixes without returning them. */
    fun clear(context: Context) {
        synchronized(lock) { clearLocked(context) }
    }

    /** How many fixes are waiting to be drained. */
    fun count(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_COUNT, 0)

    private fun clearLocked(context: Context) {
        try {
            file(context).delete()
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_COUNT, 0).apply()
        } catch (e: Exception) {
            Log.w(TAG, "could not clear buffer", e)
        }
    }

    private fun trimLocked(context: Context, maxSamples: Int) {
        val f = file(context)
        val kept = f.readLines().filter { it.isNotBlank() }.takeLast(maxSamples)
        f.writeText(kept.joinToString("\n", postfix = "\n"))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COUNT, kept.size).apply()
    }

    /**
     * Serialises a fix into the shape `LocationSample.fromMap` expects.
     *
     * Optional fields are written only when the provider actually reported
     * them, so Dart can tell "no reading" from "a reading of zero" — the
     * difference between an unknown speed and standing still.
     */
    private fun toJson(location: Location): JSONObject = JSONObject().apply {
        put("latitude", location.latitude)
        put("longitude", location.longitude)
        put("timestamp", location.time)
        if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
        if (location.hasAltitude()) put("altitude", location.altitude)
        if (location.hasSpeed()) put("speed", location.speed.toDouble())
        if (location.hasBearing()) put("heading", location.bearing.toDouble())
        put("isMocked", isMocked(location))
    }

    private fun fromJson(json: JSONObject): Map<String, Any?> = buildMap {
        put("latitude", json.getDouble("latitude"))
        put("longitude", json.getDouble("longitude"))
        put("timestamp", json.getLong("timestamp"))
        if (json.has("accuracy")) put("accuracy", json.getDouble("accuracy"))
        if (json.has("altitude")) put("altitude", json.getDouble("altitude"))
        if (json.has("speed")) put("speed", json.getDouble("speed"))
        if (json.has("heading")) put("heading", json.getDouble("heading"))
        put("isMocked", json.optBoolean("isMocked", false))
    }

    /** [Location.isFromMockProvider] was renamed in API 31. */
    @Suppress("DEPRECATION")
    fun isMocked(location: Location): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            location.isMock
        } else {
            location.isFromMockProvider
        }

    /** Serialises live fixes for the event channel, reusing [toJson]'s shape. */
    fun toEventMap(location: Location): Map<String, Any?> = fromJson(toJson(location))

    /** Test seam: turns a JSON array of fixes into channel maps. */
    fun parseAll(array: JSONArray): List<Map<String, Any?>> =
        (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
}
