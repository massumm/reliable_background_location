package dev.masumalreza.reliable_background_location

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Plugin entry point. See [LocationBridge] for the channel handling. */
class ReliableBackgroundLocationPlugin : FlutterPlugin, ActivityAware {
    private var bridge: LocationBridge? = null
    private val permissions = PermissionManager()
    private var activityBinding: ActivityPluginBinding? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        bridge = LocationBridge(binding.applicationContext, permissions).also {
            it.attach(binding.binaryMessenger)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        bridge?.detach()
        bridge = null
    }

    // Permission prompts need an Activity, and the plugin outlives any single
    // one of them — so the reference is swapped rather than held.

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
        binding.addRequestPermissionsResultListener(permissions)
        permissions.attach(binding.activity)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) =
        onAttachedToActivity(binding)

    override fun onDetachedFromActivityForConfigChanges() = onDetachedFromActivity()

    override fun onDetachedFromActivity() {
        activityBinding?.removeRequestPermissionsResultListener(permissions)
        activityBinding = null
        permissions.attach(null)
    }
}

/**
 * Bridges the Dart API onto [TrackingService], [ServiceState] and
 * [LocationBuffer].
 *
 * This class exists only while a Flutter engine does. Anything that has to
 * outlive the engine belongs on disk in [ServiceState], and anything that has
 * to run without one belongs in [LocationUpdatesReceiver].
 */
internal class LocationBridge(
    private val context: Context,
    private val permissions: PermissionManager,
) : MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler {

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null

    fun attach(messenger: io.flutter.plugin.common.BinaryMessenger) {
        methodChannel = MethodChannel(messenger, METHOD_CHANNEL).also {
            it.setMethodCallHandler(this)
        }
        eventChannel = EventChannel(messenger, EVENT_CHANNEL).also {
            it.setStreamHandler(this)
        }
    }

    fun detach() {
        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        methodChannel = null
        eventChannel = null
        activeSink = null
    }

    // ------------------------------------------------------------ event channel

    override fun onListen(arguments: Any?, sink: EventChannel.EventSink?) {
        if (sink == null) return
        val main = Handler(Looper.getMainLooper())
        // The receiver runs on a binder thread; EventSink must be touched from
        // the main thread, so the hop happens here rather than at every call
        // site in the receiver.
        activeSink = { event -> main.post { sink.success(event) } }
    }

    override fun onCancel(arguments: Any?) {
        // Dropping the sink is what makes the receiver start buffering to disk.
        activeSink = null
    }

    // ----------------------------------------------------------- method channel

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "start" -> start(call, result)
                "updateNotification" -> updateNotification(call, result)
                "stop" -> stop(result)
                "isRunning" -> result.success(TrackingService.isForegroundActive)
                "lastStartError" -> result.success(ServiceState.lastStartError(context))
                "drainBuffered" -> result.success(LocationBuffer.drain(context))
                "clearBuffered" -> {
                    LocationBuffer.clear(context)
                    result.success(null)
                }
                "checkPermissions" -> result.success(permissions.check(context).toMap())
                "requestPermissions" -> permissions.request(context) {
                    result.success(it.toMap())
                }
                "isBatterySaverOn" -> result.success(isBatterySaverOn())
                "openBatteryOptimisationSettings" -> {
                    openBatteryOptimisationSettings()
                    result.success(null)
                }
                "openAutoStartSettings" -> {
                    openAutoStartSettings()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: Exception) {
            Log.e(TAG, "call ${call.method} failed", e)
            result.error("rbl_error", e.message, null)
        }
    }

    /**
     * Persists the configuration, starts the service, then reports whether it
     * genuinely reached the foreground.
     *
     * The delay before answering is the awkward part: `startForegroundService`
     * returns immediately, well before `startForeground` has been attempted, so
     * replying straight away would always claim success. The service is polled
     * briefly instead, and a still-unknown outcome is reported as a failure
     * rather than an optimistic success.
     */
    private fun start(call: MethodCall, result: MethodChannel.Result) {
        @Suppress("UNCHECKED_CAST")
        val notification = call.argument<Map<String, Any?>>("notification").orEmpty()

        @Suppress("UNCHECKED_CAST")
        val location = call.argument<Map<String, Any?>>("location").orEmpty()

        @Suppress("UNCHECKED_CAST")
        val session = call.argument<Map<String, String>>("sessionState")

        // Read any state left behind by an interrupted session before this
        // start overwrites it.
        val recovered =
            if (ServiceState.isRunActive(context)) ServiceState.sessionState(context) else null

        ServiceState.saveNotification(context, notification)
        ServiceState.saveLocationConfig(context, location)
        ServiceState.saveSessionState(context, session)
        ServiceState.setLastStartError(context, null, null)

        val alreadyRunning = TrackingService.isForegroundActive
        TrackingService.start(context)

        if (alreadyRunning) {
            // A reconfigure, not a fresh start: the service is up already and
            // has just re-read its settings.
            result.success(mapOf("started" to true, "recoveredSessionState" to recovered))
            return
        }

        pollForOutcome(result, recovered, attempt = 0)
    }

    private fun pollForOutcome(
        result: MethodChannel.Result,
        recovered: Map<String, String>?,
        attempt: Int,
    ) {
        if (TrackingService.isForegroundActive) {
            result.success(mapOf("started" to true, "recoveredSessionState" to recovered))
            return
        }

        val failure = ServiceState.lastStartFailure(context)
        if (failure != null) {
            result.success(
                mapOf(
                    "started" to false,
                    "failure" to failure,
                    "message" to ServiceState.lastStartError(context),
                ),
            )
            return
        }

        if (attempt >= MAX_START_POLLS) {
            // Neither foreground nor a recorded failure. Something swallowed
            // the start; call it a failure so the caller does not show a
            // recording UI over nothing.
            result.success(
                mapOf(
                    "started" to false,
                    "failure" to "unknown",
                    "message" to "service did not reach foreground within " +
                        "${MAX_START_POLLS * START_POLL_MS} ms",
                ),
            )
            return
        }

        Handler(Looper.getMainLooper()).postDelayed(
            { pollForOutcome(result, recovered, attempt + 1) },
            START_POLL_MS,
        )
    }

    private fun updateNotification(call: MethodCall, result: MethodChannel.Result) {
        val update = buildMap<String, Any?> {
            call.argument<String>("title")?.let { put("title", it) }
            call.argument<String>("text")?.let { put("text", it) }
        }
        ServiceState.saveNotification(context, update)
        // Re-entering the service rebuilds the notification from the state we
        // just wrote; it does not restart tracking.
        if (TrackingService.isForegroundActive) TrackingService.start(context)
        result.success(null)
    }

    private fun stop(result: MethodChannel.Result) {
        ServiceState.setRunActive(context, false)
        ServiceState.clearSessionState(context)
        TrackingService.stop(context)
        result.success(null)
    }

    private fun isBatterySaverOn(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isPowerSaveMode ?: false
    }

    private fun openBatteryOptimisationSettings() {
        // There is no API to grant the exemption; only the user can. Land them
        // on the app's own screen where possible, the global list otherwise.
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        if (!launch(direct)) {
            launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /**
     * Opens the vendor auto-start screen where one exists.
     *
     * These OEMs kill background services whatever AOSP says, and each hides
     * the toggle somewhere different. The component names are undocumented and
     * change between ROM versions, so every candidate is tried in turn and a
     * total miss is not an error.
     */
    private fun openAutoStartSettings() {
        val candidates = listOf(
            "com.miui.securitycenter" to
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to
                "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager" to
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.huawei.systemmanager" to
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.samsung.android.lool" to
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        )

        for ((pkg, cls) in candidates) {
            val intent = Intent().setClassName(pkg, cls)
            if (launch(intent)) return
        }

        // No vendor screen: the AOSP app-details page is the honest fallback.
        launch(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }

    private fun launch(intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) == null) return false
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    internal companion object {
        private const val TAG = "RBL.Bridge"
        private const val METHOD_CHANNEL = "reliable_background_location/service"
        private const val EVENT_CHANNEL = "reliable_background_location/locations"

        private const val START_POLL_MS = 100L
        private const val MAX_START_POLLS = 50 // 5 seconds

        /**
         * Where live fixes go, or null when no isolate is listening.
         *
         * [LocationUpdatesReceiver] reads this to decide between the event
         * channel and the disk buffer, and it runs in processes where this
         * plugin was never attached — so null is the normal case, not an error.
         */
        @Volatile
        internal var activeSink: ((Map<String, Any?>) -> Unit)? = null
    }
}
