package ru.topoar

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Азимут направления задней камеры (куда «смотрит» телефон), градусы от истинного севера.
 * Грубая оценка (±5–15°) — только для первой прикидки, дальше уточняем визиром или 2-й точкой.
 */
class Compass(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    @Volatile var azimuth: Double = Double.NaN; private set
    @Volatile var reliable = false; private set

    /** Магнитное склонение; по умолчанию — для Нижегородской области. */
    var declination: Double = GeomagneticField(56.3f, 44.0f, 100f, System.currentTimeMillis()).declination.toDouble()

    val available get() = sensor != null

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) } }
    fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // ось камеры (−Z устройства) в мировых координатах (x — восток, y — север, z — вверх)
        val east = -rot[2]
        val north = -rot[5]
        if (hypot(east.toDouble(), north.toDouble()) < 0.35) { reliable = false; return }   // телефон смотрит в землю/небо
        var a = Math.toDegrees(atan2(east.toDouble(), north.toDouble())) + declination
        a = (a + 360.0) % 360.0
        azimuth = a
        reliable = true
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
}
