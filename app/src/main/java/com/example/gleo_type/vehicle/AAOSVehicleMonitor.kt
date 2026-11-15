
package com.example.gleo_type.vehicle

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.lang.reflect.Proxy

class AAOSVehicleMonitor(
    private val context: Context,
    private val onSpeedSample: (Float, Long, Long?) -> Unit = { _, _, _ -> },
    private val onConnectionState: (CarConnectionSnapshot) -> Unit = {},
    private val onEvent: (VehicleEventDto) -> Unit
) {
    companion object {
        private const val TAG = "AAOSVehicle"

        private const val SPEED = 291504647
        private const val SPEED_DISPLAY = 291504648
        private const val ACCELERATOR = 291504911
        private const val BRAKE_PERCENT = 291504912

        private val names = mapOf(
            SPEED to "PERF_VEHICLE_SPEED",
            SPEED_DISPLAY to "PERF_VEHICLE_SPEED_DISPLAY",
            ACCELERATOR to
                    "ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE",
            BRAKE_PERCENT to
                    "BRAKE_PEDAL_COMPRESSION_PERCENTAGE"
        )
    }

    private val thread = HandlerThread(
        "AAOSVehicleWorker"
    ).apply {
        start()
    }

    private val worker = Handler(thread.looper)

    @Volatile
    private var active = false

    private var car: Any? = null
    private var manager: Any? = null
    private var listener: Any? = null

    private val lastValues = mutableMapOf<String, Any?>()

    private var released = false
    @Volatile private var requestGeneration = 0L
    @Volatile private var liveToken = -1L
    private var connectionRequestGeneration = 0L
    private val supervisor = CarConnectionSupervisor(
        CarConnectionSupervisor.Scheduler { delay, action ->
            val task = Runnable { action() }
            worker.postDelayed(task, delay)
            CarConnectionSupervisor.Cancellation { worker.removeCallbacks(task) }
        },
        object : CarConnectionSupervisor.Connection {
            override fun open(token: Long, ready: (Boolean) -> Unit) {
                val generation = requestGeneration
                connectionRequestGeneration = generation
                val carClass = try { Class.forName("android.car.Car") }
                    catch (_: ClassNotFoundException) { throw CarConnectionSupervisor.Unavailable("AAOS_API_unavailable") }
                val lifecycle = try { Class.forName("android.car.Car\$CarServiceLifecycleListener") }
                    catch (_: ClassNotFoundException) { throw CarConnectionSupervisor.Unavailable("lifecycle_API_unavailable") }
                val callback = Proxy.newProxyInstance(lifecycle.classLoader, arrayOf(lifecycle)) { proxy, method, args ->
                    when (method.name) {
                        "onLifecycleChanged" -> {
                            val available = args?.getOrNull(1) == true
                            if (!available && liveToken == token) liveToken = -1L
                            worker.post { if (active && generation == requestGeneration) ready(available) }
                            null
                        }
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "toString" -> "GleoCarLifecycle($token)"
                        else -> null
                    }
                }
                val create = try { carClass.getMethod("createCar", Context::class.java, Handler::class.java,
                    Long::class.javaPrimitiveType, lifecycle) }
                    catch (_: NoSuchMethodException) { throw CarConnectionSupervisor.Unavailable("lifecycle_factory_unavailable") }
                // Official CAR_WAIT_TIMEOUT_DO_NOT_WAIT. Lifecycle callback, not return value, establishes readiness.
                car = create.invoke(null, context.applicationContext, worker, 0L, callback)
                    ?: throw IllegalStateException("createCar_returned_null")
            }
            override fun subscribe(token: Long): Int {
                val current = car ?: error("Car_missing")
                val pm = current.javaClass.getMethod("getCarManager", String::class.java).invoke(current, "property")
                    ?: error("CarPropertyManager_missing")
                manager = pm
                inspectSupportedProperties(pm)
                val count = subscribeProperties(pm, token)
                liveToken = token
                return count
            }
            override fun close() = disconnectResources()
        },
        changed = { value ->
            if (value.state != CarConnectionState.CONNECTED) liveToken = -1L
            Log.i(TAG, "[CONNECTION] state=${value.state} token=${value.token} attempt=${value.attempt} " +
                "subscriptions=${value.subscriptions} delayMs=${value.nextDelayMs} reason=${value.reason}")
            onConnectionState(value)
        }
    )

    /** Transport readiness only. Actual usable features still require fresh received speed coverage. */
    fun connectionState(): CarConnectionSnapshot = supervisor.snapshot

    @Synchronized fun startMonitoring() {
        if (active || released) return
        active = true
        val generation = ++requestGeneration
        worker.post { if (active && generation == requestGeneration) supervisor.start() }
    }

    private fun inspectSupportedProperties(
        propertyManager: Any
    ) {
        try {
            val getPropertyList =
                propertyManager.javaClass.getMethod(
                    "getPropertyList"
                )

            val configs =
                getPropertyList.invoke(propertyManager)
                        as? List<*> ?: emptyList<Any>()

            Log.i(
                TAG,
                "[AAOS] 접근 가능한 속성 ${configs.size}개"
            )

            for (config in configs) {
                if (config == null) {
                    continue
                }

                val getPropertyId =
                    config.javaClass.getMethod("getPropertyId")

                val id = getPropertyId.invoke(config) as Int

                val name = names[id] ?: "PROPERTY_$id"

                Log.d(
                    TAG,
                    "[속성목록] $name ($id)"
                )
            }

        } catch (e: Exception) {
            Log.e(
                TAG,
                "[AAOS] 속성 목록 조회 실패",
                e
            )
        }
    }

    private fun subscribeProperties(propertyManager: Any, token: Long): Int {
        val generation = connectionRequestGeneration
        val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
        val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { proxy, method, args ->
            when (method.name) {
                "onChangeEvent" -> {
                    if (active && generation == requestGeneration && liveToken == token && supervisor.accepts(token))
                        args?.firstOrNull()?.let(::handleChange)
                    null
                }
                "onErrorEvent" -> { Log.w(TAG, "[PROPERTY_UNAVAILABLE] ${args?.contentToString()}"); null }
                "toString" -> "AAOSVehicleCallback($token)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        listener = callback // Also owned on partial registration failure.
        val register = propertyManager.javaClass.getMethod("registerCallback", callbackClass,
            Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
        var count = 0
        var speed = false
        for ((id, name) in names) {
            if (!active || generation != requestGeneration) error("collection_stopped")
            try {
                if (register.invoke(propertyManager, callback, id, 1.0f) == true) {
                    count++; if (id == SPEED) speed = true
                    Log.i(TAG, "[구독성공] $name token=$token")
                } else Log.w(TAG, "[구독불가] $name : 미지원 또는 권한 없음")
            } catch (e: Exception) {
                if (id == SPEED && e.cause is SecurityException)
                    throw CarConnectionSupervisor.Unavailable("CAR_SPEED_permission_denied")
                Log.w(TAG, "[구독실패] $name : ${e.cause ?: e}")
                if (id == SPEED) throw e
            }
        }
        if (!speed) throw CarConnectionSupervisor.Unavailable("SPEED_subscription_unavailable")
        return count
    }

    private fun handleChange(event: Any) {
        if (!active) {
            return
        }

        try {
            val eventClass = event.javaClass

            val propertyId =
                eventClass.getMethod("getPropertyId")
                    .invoke(event) as Int

            val areaId =
                eventClass.getMethod("getAreaId")
                    .invoke(event) as Int

            val value =
                eventClass.getMethod("getValue")
                    .invoke(event)

            // Unavailable/error values are not measurements.
            val status = eventClass.getMethod("getStatus").invoke(event) as Int
            if (status != 0) return
            val name = names[propertyId] ?: return
            if (propertyId == SPEED && value is Number) {
                val timestamp = runCatching {
                    eventClass.getMethod("getTimestamp").invoke(event) as Long
                }.getOrNull()
                onSpeedSample(value.toFloat(), android.os.SystemClock.elapsedRealtime(), timestamp)
            }
            val key = "$propertyId:$areaId"

            val normalizedValue = when (value) {
                is Float -> {
                    if (value == 0f) 0.0 else value.toDouble()
                }
                is Double -> {
                    if (value == 0.0) 0.0 else value
                }
                else -> value
            }

            synchronized(lastValues) {
                if (
                    lastValues.containsKey(key) &&
                    lastValues[key] == normalizedValue
                ) {
                    return
                }

                lastValues[key] = normalizedValue
            }

            val dto = VehicleEventDto(
                domain = "AAOS",
                apiName = name,
                eventName = "onChangeEvent",
                eventType = "REALTIME",
                values = listOf(
                    VehicleValueConverter.convert(areaId),
                    VehicleValueConverter.convert(
                        normalizedValue
                    )
                )
            )

            Log.i(TAG, "[값변경] $name = $normalizedValue")
            Log.i(TAG, "[DTO전달] ${dto.toJson()}")

            onEvent(dto)

        } catch (e: Exception) {
            Log.e(TAG, "[AAOS] 이벤트 처리 실패", e)
        }
    }

    private fun disconnectResources() {
        liveToken = -1L
        val pm = manager; val cb = listener; val oldCar = car
        try {
            if (pm != null && cb != null) {
                val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
                pm.javaClass.getMethod("unregisterCallback", callbackClass).invoke(pm, cb)
            }
        } catch (e: Exception) { Log.w(TAG, "[UNREGISTER_FAILED] ${e.cause ?: e}") }
        finally {
            try {
                oldCar?.javaClass?.getMethod("disconnect")?.invoke(oldCar)
                listener = null; manager = null; car = null
            } catch (e: Exception) {
                Log.w(TAG, "[DISCONNECT_FAILED] ${e.cause ?: e}")
                throw IllegalStateException("client_cleanup_failed", e)
            }
        }
    }

    @Synchronized fun requestReconnect() {
        if (!active || released) return
        val generation = requestGeneration
        worker.post { if (active && generation == requestGeneration) supervisor.reconnect() }
    }

    @Synchronized fun stopMonitoring() {
        active = false; requestGeneration++; liveToken = -1L
        if (!released) worker.post {
            supervisor.stop()
            synchronized(lastValues) { lastValues.clear() }
        }
    }

    @Synchronized fun release() {
        if (released) return
        released = true; active = false; requestGeneration++; liveToken = -1L
        worker.post { supervisor.release(); thread.quitSafely() }
    }
}
