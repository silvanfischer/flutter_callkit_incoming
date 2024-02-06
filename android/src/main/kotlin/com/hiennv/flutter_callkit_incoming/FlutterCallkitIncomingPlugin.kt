package com.hiennv.flutter_callkit_incoming

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import androidx.annotation.Nullable

import com.hiennv.flutter_callkit_incoming.telecom.TelecomUtilities
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.*
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result


/** FlutterCallkitIncomingPlugin */
class FlutterCallkitIncomingPlugin : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.RequestPermissionsResultListener {
    companion object {

        const val EXTRA_CALLKIT_CALL_DATA = "EXTRA_CALLKIT_CALL_DATA"

        @SuppressLint("StaticFieldLeak")
        lateinit var instance: FlutterCallkitIncomingPlugin

        @SuppressLint("StaticFieldLeak")
        private lateinit var telecomUtilities: TelecomUtilities

        private val eventHandler = EventCallbackHandler()

        fun sendEvent(event: String, body: Map<String, Any>) {
            eventHandler.send(event, body)
        }

        fun sharePluginWithRegister(
            @NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding,
            @Nullable handler: MethodCallHandler?
        ) {
            initSharedInstance(
                flutterPluginBinding.applicationContext,
                flutterPluginBinding.binaryMessenger,
                handler
            )
        }

        private fun initSharedInstance(
            @NonNull context: Context,
            @NonNull binaryMessenger: BinaryMessenger,
            @Nullable handler: MethodCallHandler?
        ) {
            if (instance == null) {
                instance = FlutterCallkitIncomingPlugin()
            }
            instance.context = context
            instance.callkitNotificationManager = CallkitNotificationManager(context)
            instance.channel = MethodChannel(binaryMessenger, "flutter_callkit_incoming")
            instance.channel?.setMethodCallHandler(handler ?: instance)
            instance.events = EventChannel(binaryMessenger, "flutter_callkit_incoming_events")
            instance.events?.setStreamHandler(eventHandler)
        }
    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var activity: Activity? = null
    private var context: Context? = null
    private var callkitNotificationManager: CallkitNotificationManager? = null
    private var channel: MethodChannel? = null
    private var events: EventChannel? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        context = flutterPluginBinding.applicationContext.apply {
            telecomUtilities = TelecomUtilities(this)
            TelecomUtilities.telecomUtilitiesSingleton = telecomUtilities
        }

        callkitNotificationManager = CallkitNotificationManager(flutterPluginBinding.applicationContext)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "flutter_callkit_incoming")
        channel?.setMethodCallHandler(this)
        events =
            EventChannel(flutterPluginBinding.binaryMessenger, "flutter_callkit_incoming_events")
        events?.setStreamHandler(eventHandler)
        sharePluginWithRegister(flutterPluginBinding, this)
    }

    public fun showIncomingNotification(data: Data) {
        data.from = "notification"
        callkitNotificationManager?.showIncomingNotification(data.toBundle())
        //send BroadcastReceiver
        context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentIncoming(
                        requireNotNull(context),
                        data.toBundle()
                )
        )
    }

    public fun showMissCallNotification(data: Data) {
        callkitNotificationManager?.showIncomingNotification(data.toBundle())
    }

    public fun startCall(data: Data) {
        context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentStart(
                        requireNotNull(context),
                        data.toBundle()
                )
        )
    }

    public fun endCall(data: Data) {
        context?.sendBroadcast(
                CallkitIncomingBroadcastReceiver.getIntentEnded(
                        requireNotNull(context),
                        data.toBundle()
                )
        )
    }

    public fun endAllCalls() {
        val calls = getDataActiveCalls(context)
        calls.forEach {
            context?.sendBroadcast(
                    CallkitIncomingBroadcastReceiver.getIntentEnded(
                            requireNotNull(context),
                            it.toBundle()
                    )
            )
        }
        removeAllCalls(context)
    }

    public fun sendEventCustom(event: String, body: Map<String, Any>) {
        eventHandler.send(event, body)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        try {
            when (call.method) {
                "showCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    //send BroadcastReceiver
                    context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentIncoming(
                                    requireNotNull(context),
                                    data.toBundle()
                            )
                    )

                    // only report to telecom if it's a voice call
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.reportIncomingCall(data)
                    }

                    result.success("OK")
                }

                "showCallkitIncomingSilently" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"

                    // we don't need to send a broadcast, we only need to report the data to telecom
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.reportIncomingCall(data)
                    }

                    result.success("OK")
                }

                "showMissCallNotification" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    data.from = "notification"
                    callkitNotificationManager?.showMissCallNotification(data.toBundle())
                    result.success("OK")
                }

                "startCall" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentStart(
                                    requireNotNull(context),
                                    data.toBundle()
                            )
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.startCall(data)
                    }

                    result.success("OK")
                }

                "muteCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_MUTE, map)

                    val data = Data(call.arguments() ?: HashMap())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.muteCall(data)
                    }

                    result.success("OK")
                }

                "holdCall" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    sendEvent(CallkitConstants.ACTION_CALL_TOGGLE_HOLD, map)

                    val data = Data(call.arguments() ?: HashMap())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (data.isOnHold) {
                            telecomUtilities.holdCall(data)
                        } else {
                            telecomUtilities.unHoldCall(data)
                        }
                    }

                    result.success("OK")
                }

                "isMuted" -> {
                    result.success(false)
                }

                "endCall" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    context?.sendBroadcast(
                            CallkitIncomingBroadcastReceiver.getIntentEnded(
                                    requireNotNull(context),
                                    data.toBundle()
                            )
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.endCall(data)
                    }

                    result.success("OK")
                }

                "callConnected" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.acceptCall(Data(call.arguments() ?: HashMap()))
                    }

                    result.success("OK")
                }

                "endAllCalls" -> {
                    val calls = getDataActiveCalls(context)
                    calls.forEach {
                        if (it.isAccepted) {
                            context?.sendBroadcast(
                                    CallkitIncomingBroadcastReceiver.getIntentEnded(
                                            requireNotNull(context),
                                            it.toBundle()
                                    )
                            )
                        } else {
                            context?.sendBroadcast(
                                    CallkitIncomingBroadcastReceiver.getIntentDecline(
                                            requireNotNull(context),
                                            it.toBundle()
                                    )
                            )
                        }
                    }
                    removeAllCalls(context)

                    //Additional safety net
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.endAllActiveCalls()
                    }

                    result.success("OK")
                }

                "activeCalls" -> {
                    result.success(getDataActiveCallsForFlutter(context))
                }

                "getDevicePushTokenVoIP" -> {
                    result.success("")
                }

                "silenceEvents" -> {
                    val silence = call.arguments as? Boolean ?: false
                    CallkitIncomingBroadcastReceiver.silenceEvents = silence
                    result.success("")
                }

                "requestNotificationPermission" -> {
                    val map = buildMap {
                        val args = call.arguments
                        if (args is Map<*, *>) {
                            putAll(args as Map<String, Any>)
                        }
                    }
                    callkitNotificationManager?.requestNotificationPermission(activity, map)
                }
                // EDIT - clear the incoming notification/ring (after accept/decline/timeout)
                "hideCallkitIncoming" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    context?.stopService(Intent(context, CallkitSoundPlayerService::class.java))
                    callkitNotificationManager?.clearIncomingNotification(data.toBundle(), false)
                }

                "endNativeSubsystemOnly" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        telecomUtilities.endCall(data)
                    }
                }

                "setAudioRoute" -> {
                    val data = Data(call.arguments() ?: HashMap())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        telecomUtilities.setAudioRoute(data)
                    }
                }
            }
        } catch (error: Exception) {
            result.error("error", error.message, "")
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        instance.context = binding.activity.applicationContext
        instance.activity = binding.activity
        binding.addRequestPermissionsResultListener(this)
    }

    override fun onDetachedFromActivity() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Log.d("FlutterCallkitPlugin", "onDetachedFromActivity: called -- activity destroyed? ${activity?.isDestroyed}")
            if (activity?.isDestroyed == true) telecomUtilities.endAllActiveCalls()
        }
    }

    class EventCallbackHandler : EventChannel.StreamHandler {

        private var lastEvent: Pair<String, Map<String, Any>>? = null

        private var eventSink: EventChannel.EventSink? = null

        override fun onListen(arguments: Any?, sink: EventChannel.EventSink) {
            eventSink = sink
            lastEvent?.let { sendEvent(it.first, it.second) }
        }

        fun send(event: String, body: Map<String, Any>) {
            lastEvent = Pair(event, body)
            val data = mapOf(
                    "event" to event,
                    "body" to body
            )
            Handler(Looper.getMainLooper()).post {
                eventSink?.let {
                    it.success(data)
                    lastEvent = null
                }
            }
        }

        override fun onCancel(arguments: Any?) {
            eventSink = null
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
        instance.callkitNotificationManager?.onRequestPermissionsResult(instance.activity, requestCode, grantResults)
        return true
    }


}
