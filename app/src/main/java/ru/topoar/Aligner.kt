package ru.topoar

import android.opengl.Matrix
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Привязка плана к миру ARCore.
 *
 * План (локальные метры: x восток, y вверх, z = −север) переводится в мир AR
 * поворотом вокруг вертикали на θ и сдвигом t:   p = R(θ)·q + t
 *   R(θ): x' = cosθ·x + sinθ·z,  z' = −sinθ·x + cosθ·z   (как Matrix.rotateM вокруг Y)
 *
 * Источники привязки:
 *  • пары «точка плана ↔ точка в AR» (крестик на точку или «стою на точке»);
 *  • направление: «визир» на дальнюю точку или компас;
 *  • ручная подвижка на мини-карте.
 */
class Aligner {
    class Obs(val name: String, val q: FloatArray, val p: FloatArray, val kind: String)

    val pairs = ArrayList<Obs>()
    var theta = 0.0; private set
    var tx = 0.0; private set
    var ty = 0.0; private set
    var tz = 0.0; private set
    var scale = 1.0; private set
    var aligned = false; private set
    var source = ""; private set

    /** θ из визирования или компаса (если пар меньше двух). */
    private var bearingTheta: Double? = null
    private var bearingIsCompass = false
    private var manual = false

    @Synchronized fun reset() {
        pairs.clear(); bearingTheta = null; aligned = false; manual = false; scale = 1.0; source = ""
    }

    @Synchronized fun addPair(o: Obs) { pairs.add(o); manual = false; solve() }

    @Synchronized fun undo() {
        if (pairs.isNotEmpty()) pairs.removeAt(pairs.size - 1)
        if (pairs.isEmpty()) bearingTheta = null
        manual = false
        solve()
    }

    /**
     * Визир: стоим в точке плана [station] (локальные), крестик наведён на точку [target].
     * [fwdX], [fwdZ] — горизонтальное направление взгляда камеры в мире AR.
     */
    @Synchronized fun setSight(station: FloatArray, target: FloatArray, fwdX: Float, fwdZ: Float) {
        val px = (target[0] - station[0]).toDouble()
        val pz = (target[2] - station[2]).toDouble()
        if (hypot(px, pz) < 1.0) return
        bearingTheta = atan2(pz, px) - atan2(fwdZ.toDouble(), fwdX.toDouble())
        bearingIsCompass = false
        manual = false
        solve()
    }

    /** Компас: азимут взгляда камеры (истинный, градусы от севера по часовой). */
    @Synchronized fun setCompass(azimuthDeg: Double, fwdX: Float, fwdZ: Float) {
        if (bearingTheta != null && !bearingIsCompass) return       // визир точнее компаса
        val b = Math.toRadians(azimuthDeg)
        val px = sin(b)            // восток
        val pz = -cos(b)           // −север
        bearingTheta = atan2(pz, px) - atan2(fwdZ.toDouble(), fwdX.toDouble())
        bearingIsCompass = true
        if (!manual) solve()
    }

    @Synchronized fun hasBearing() = bearingTheta != null

    @Synchronized fun snapshot(): List<Obs> = ArrayList(pairs)

    @Synchronized fun count() = pairs.size

    private fun solve() {
        val n = pairs.size
        if (n >= 2) {
            var qx = 0.0; var qz = 0.0; var px = 0.0; var pz = 0.0
            for (o in pairs) { qx += o.q[0]; qz += o.q[2]; px += o.p[0]; pz += o.p[2] }
            qx /= n; qz /= n; px /= n; pz /= n
            var a = 0.0; var b = 0.0
            for (o in pairs) {
                val ax = o.q[0] - qx; val az = o.q[2] - qz
                val bx = o.p[0] - px; val bz = o.p[2] - pz
                a += bx * ax + bz * az
                b += bx * az - bz * ax
            }
            theta = atan2(b, a)
            val c = cos(theta); val s = sin(theta)
            tx = px - (c * qx + s * qz)
            tz = pz - (-s * qx + c * qz)
            source = "по ${n} точкам"
        } else if (n == 1 && bearingTheta != null) {
            theta = bearingTheta!!
            val o = pairs[0]
            val c = cos(theta); val s = sin(theta)
            tx = o.p[0] - (c * o.q[0] + s * o.q[2])
            tz = o.p[2] - (-s * o.q[0] + c * o.q[2])
            source = if (bearingIsCompass) "точка + компас" else "точка + визир"
        } else {
            aligned = false
            source = ""
            return
        }
        var dy = 0.0
        for (o in pairs) dy += o.p[1] - o.q[1]
        ty = dy / n
        scale = 1.0
        aligned = true
    }

    /** Невязки по каждой паре: [план-место по горизонтали, по высоте]. */
    @Synchronized fun residuals(): List<FloatArray> {
        if (!aligned) return emptyList()
        return pairs.map { o ->
            val p = toAr(o.q)
            floatArrayOf(hypot((p[0] - o.p[0]).toDouble(), (p[2] - o.p[2]).toDouble()).toFloat(), p[1] - o.p[1])
        }
    }

    @Synchronized fun rms(): Double {
        val r = residuals()
        if (r.size < 3) return -1.0
        return sqrt(r.sumOf { (it[0] * it[0]).toDouble() } / r.size)
    }

    @Synchronized fun toAr(q: FloatArray): FloatArray {
        val c = cos(theta); val s = sin(theta)
        return floatArrayOf(
            ((c * q[0] + s * q[2]) * scale + tx).toFloat(),
            (q[1] * scale + ty).toFloat(),
            ((-s * q[0] + c * q[2]) * scale + tz).toFloat())
    }

    @Synchronized fun toPlan(p: FloatArray): FloatArray {
        val x = (p[0] - tx) / scale; val y = (p[1] - ty) / scale; val z = (p[2] - tz) / scale
        val c = cos(theta); val s = sin(theta)
        return floatArrayOf((c * x - s * z).toFloat(), y.toFloat(), (s * x + c * z).toFloat())
    }

    /** Направление (x, z) в мире AR → направление на плане. */
    @Synchronized fun dirToPlan(x: Float, z: Float): FloatArray {
        val c = cos(theta); val s = sin(theta)
        return floatArrayOf((c * x - s * z).toFloat(), (s * x + c * z).toFloat())
    }

    /** Ручная подвижка: телефон (точка AR [dev]) должен оказаться в точке плана [q] (x, z). */
    @Synchronized fun moveDeviceTo(dev: FloatArray, qx: Float, qz: Float) {
        if (!aligned) return
        val c = cos(theta); val s = sin(theta)
        tx = dev[0] - (c * qx + s * qz) * scale
        tz = dev[2] - (-s * qx + c * qz) * scale
        manual = true
        source = "$source, подвинуто"
    }

    /** Ручной поворот на [d] радиан вокруг точки AR [pivot]. */
    @Synchronized fun rotateAbout(pivot: FloatArray, d: Double) {
        if (!aligned) return
        val c = cos(d); val s = sin(d)
        val x = tx - pivot[0]; val z = tz - pivot[2]
        tx = c * x + s * z + pivot[0]
        tz = -s * x + c * z + pivot[2]
        theta += d
        manual = true
        if (!source.endsWith("подвинуто")) source = "$source, подвинуто"
    }

    /** Поднять/опустить модель (м). */
    @Synchronized fun lift(d: Double) { if (aligned) { ty += d; manual = true } }

    /** Режим «макет на столе»: модель целиком в точке [at], масштаб [s], поворот [rot]. */
    @Synchronized fun placeTable(at: FloatArray, center: FloatArray, s: Double, rot: Double) {
        theta = rot
        scale = s
        val c = cos(rot); val sn = sin(rot)
        tx = at[0] - (c * center[0] + sn * center[2]) * s
        ty = at[1] - center[1] * s
        tz = at[2] - (-sn * center[0] + c * center[2]) * s
        aligned = true
        source = "макет"
    }

    @Synchronized fun modelMatrix(out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        Matrix.translateM(out, 0, tx.toFloat(), ty.toFloat(), tz.toFloat())
        Matrix.rotateM(out, 0, Math.toDegrees(theta).toFloat(), 0f, 1f, 0f)
        Matrix.scaleM(out, 0, scale.toFloat(), scale.toFloat(), scale.toFloat())
    }
}
