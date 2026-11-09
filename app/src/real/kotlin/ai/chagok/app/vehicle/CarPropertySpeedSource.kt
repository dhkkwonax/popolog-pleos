package ai.chagok.app.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 차속 읽기 (CarPropertyManager PERF_VEHICLE_SPEED). 권한 값·3rd-party 허용이 아직 미확인이라
 * android.car 를 컴파일 의존으로 두지 않고 리플렉션으로 시도한다. 어떤 단계든 실패하면 null(측정 불가)을 보낸다.
 * 속성은 m/s 단위 float 이며 km/h 로 바꿔 전달한다.
 */
class CarPropertySpeedSource(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var onSpeed: ((Double?) -> Unit)? = null
    private var manager: Any? = null
    private var getFloat: java.lang.reflect.Method? = null

    private val poll = object : Runnable {
        override fun run() {
            onSpeed?.invoke(read())
            handler.postDelayed(this, POLL_MS)
        }
    }

    fun start(onSpeed: (Double?) -> Unit) {
        this.onSpeed = onSpeed
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val car = carClass.getMethod("createCar", Context::class.java).invoke(null, context.applicationContext)
            val svc = carClass.getField("PROPERTY_SERVICE").get(null) as String
            val mgr = carClass.getMethod("getCarManager", String::class.java).invoke(car, svc)
            manager = mgr
            getFloat = mgr?.javaClass?.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    fun stop() {
        handler.removeCallbacks(poll)
        onSpeed = null
        manager = null
        getFloat = null
    }

    private fun read(): Double? = runCatching {
        val ms = getFloat?.invoke(manager, PERF_VEHICLE_SPEED, 0) as? Float ?: return null
        if (ms.isNaN() || ms < 0f) null else ms * 3.6
    }.getOrNull()

    companion object {
        /** VehiclePropertyIds.PERF_VEHICLE_SPEED */
        const val PERF_VEHICLE_SPEED = 0x11600207
        const val POLL_MS = 1_000L
    }
}
