package com.example.gleo_type.ui.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow

/** No speed threshold/zero-speed assumption: only the platform restriction API grants full UI. */
data class DrivingUx(val known: Boolean = false, val requiresOptimization: Boolean = true,
    val activeRestrictions: Int = -1, val source: String = "unavailable", val observedElapsedMs: Long? = null) {
    val complexAllowed get() = known && !requiresOptimization && activeRestrictions == 0
    val reduced get() = !complexAllowed
    val label get() = if(source=="HANDHELD_LOCAL_UI_NOT_CAR_PERMISSION") "스마트폰 로컬 화면 · 차량 편집 권한 아님" else if (!known) "주행 규제 상태 미확인 · 기본 오디오만" else if (reduced) "주행 제한 · 기본 오디오만" else "플랫폼 UX 제한 없음"
}

/** Dedicated UI subscriber; does not change any Pleos/AAOS telemetry subscription. */
class CarDrivingUxMonitor(private val context: Context) : AutoCloseable {
    val state = MutableStateFlow(DrivingUx())
    private val thread = HandlerThread("GleoUxPolicy").apply { start() }
    private val handler = Handler(thread.looper)
    private var car: Any? = null
    private var manager: Any? = null
    private var callback: Any? = null
    @Volatile private var closed = false
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            if (manager == null) connect() else runCatching {
                read(manager!!.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(manager))
            }.onFailure { state.value = DrivingUx(); manager = null }
            if (!closed) handler.postDelayed(this, 2000)
        }
    }
    init { handler.post(poll) }
    private fun read(value: Any?) {
        if (value == null || closed) return
        runCatching {
            val type = value.javaClass
            state.value = DrivingUx(true, type.getMethod("isRequiresDistractionOptimization").invoke(value) as Boolean,
                type.getMethod("getActiveRestrictions").invoke(value) as Int, "CarUxRestrictionsManager", android.os.SystemClock.elapsedRealtime())
        }.onFailure { state.value = DrivingUx() }
    }
    private fun connect() {
        if (closed) return
        try {
            runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
            val type = Class.forName("android.car.Car")
            car = type.getMethod("createCar", Context::class.java).invoke(null, context)
            manager = type.getMethod("getCarManager", String::class.java).invoke(car, "uxrestriction")
            val m = manager ?: error("No UX manager")
            val listener = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
            callback = Proxy.newProxyInstance(listener.classLoader, arrayOf(listener)) { proxy, method, args ->
                when(method.name) {
                    "onUxRestrictionsChanged" -> { read(args?.firstOrNull()); null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "GleoUxListener"
                    else -> null
                }
            }
            m.javaClass.getMethod("registerListener", listener).invoke(m, callback)
            read(m.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(m))
        } catch (_: Exception) { state.value = DrivingUx() }
    }
    override fun close() {
        closed = true
        handler.removeCallbacks(poll)
        handler.post {
            runCatching { manager?.javaClass?.getMethod("unregisterListener")?.invoke(manager) }
            runCatching { car?.javaClass?.getMethod("disconnect")?.invoke(car) }
            callback = null; manager = null; car = null; thread.quitSafely()
        }
    }
}
