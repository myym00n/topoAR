package ru.topoar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Мини-карта: план, опорные точки, «я здесь» и направление взгляда.
 *  • тап по точке — выбрать опорную точку;
 *  • долгое нажатие — «Я стою здесь» (с прилипанием к ближайшей точке);
 *  • режим «Подвинуть»: тащишь план под маркером «я», двумя пальцами — поворот.
 */
class MapView(ctx: Context) : View(ctx) {
    interface Listener {
        fun onPick(cp: ControlPoint)
        fun onStandHere(lx: Float, lz: Float, cp: ControlPoint?)
        fun onShiftDevice(dxLocal: Float, dzLocal: Float)
        fun onRotate(deltaRad: Double)
    }

    var listener: Listener? = null
    var pkg: TopoPackage? = null
        set(v) { field = v; fitted = false; invalidate() }
    var selected: ControlPoint? = null
        set(v) { field = v; invalidate() }
    var usedIds: Set<Int> = emptySet()
        set(v) { field = v; invalidate() }
    /** Положение телефона на плане (локальные x, z) и направление взгляда (x, z), или null. */
    var device: FloatArray? = null
    var heading: FloatArray? = null
    var adjustMode = false
        set(v) { field = v; invalidate() }

    private val m = Matrix()
    private val inv = Matrix()
    private var fitted = false
    private val dp = resources.displayMetrics.density
    private val pPt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = Color.WHITE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 13 * dp }
    private val pTextBg = Paint().apply { color = Color.argb(220, 255, 255, 255) }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (adjustMode) return true
            m.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val gest = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            nearest(e.x, e.y, 28 * dp)?.let { listener?.onPick(it) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val p = pkg ?: return
            val cp = nearest(e.x, e.y, 22 * dp)
            if (cp != null) listener?.onStandHere(cp.local[0], cp.local[2], cp)
            else {
                val img = toImage(e.x, e.y)
                val l = p.previewToLocal(img[0], img[1])
                listener?.onStandHere(l[0], l[1], null)
            }
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1) return true
            if (adjustMode) {
                val p = pkg ?: return true
                // план уезжает за пальцем → телефон на плане сдвигается в обратную сторону
                val s = currentScale()
                val mPerPx = (p.width / p.preview.width).toFloat()
                listener?.onShiftDevice(dx / s * mPerPx, dy / s * mPerPx)
            }
            m.postTranslate(-dx, -dy)
            invalidate()
            return true
        }
    })

    private var lastAngle = Double.NaN

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (adjustMode && e.pointerCount == 2) {
            val a = atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())
            if (e.actionMasked == MotionEvent.ACTION_MOVE && !lastAngle.isNaN()) {
                var d = a - lastAngle
                if (d > Math.PI) d -= 2 * Math.PI
                if (d < -Math.PI) d += 2 * Math.PI
                // поворот карты по часовой = поворот модели против часовой в мире
                listener?.onRotate(-d)
            }
            lastAngle = a
            if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) lastAngle = Double.NaN
            return true
        }
        lastAngle = Double.NaN
        scaleDet.onTouchEvent(e)
        gest.onTouchEvent(e)
        return true
    }

    private fun currentScale(): Float {
        val v = FloatArray(9)
        m.getValues(v)
        return hypot(v[0].toDouble(), v[3].toDouble()).toFloat()
    }

    private fun toImage(sx: Float, sy: Float): FloatArray {
        m.invert(inv)
        val pt = floatArrayOf(sx, sy)
        inv.mapPoints(pt)
        return pt
    }

    private fun toScreen(ix: Float, iy: Float): FloatArray {
        val pt = floatArrayOf(ix, iy)
        m.mapPoints(pt)
        return pt
    }

    private fun nearest(sx: Float, sy: Float, maxPx: Float): ControlPoint? {
        val p = pkg ?: return null
        var best: ControlPoint? = null
        var bd = maxPx
        for (c in p.controlPoints) {
            val s = toScreen(c.px, c.py)
            val d = hypot((s[0] - sx).toDouble(), (s[1] - sy).toDouble()).toFloat()
            if (d < bd) { bd = d; best = c }
        }
        return best
    }

    fun centerOn(lx: Float, lz: Float) {
        val p = pkg ?: return
        val img = p.localToPreview(lx, lz)
        val s = toScreen(img[0], img[1])
        m.postTranslate(width / 2f - s[0], height / 2f - s[1])
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val p = pkg
        c.drawColor(Color.rgb(246, 247, 243))
        if (p == null) return
        if (!fitted && width > 0) {
            val s = minOf(width.toFloat() / p.preview.width, height.toFloat() / p.preview.height)
            m.reset()
            m.postScale(s, s)
            m.postTranslate((width - p.preview.width * s) / 2, (height - p.preview.height * s) / 2)
            fitted = true
        }
        c.drawBitmap(p.preview, m, pBmp)
        val r = 7 * dp
        for (cp in p.controlPoints) {
            val s = toScreen(cp.px, cp.py)
            if (s[0] < -r || s[1] < -r || s[0] > width + r || s[1] > height + r) continue
            pPt.color = when {
                cp.id in usedIds -> Color.BLACK
                cp.type == "corner" -> Color.rgb(221, 90, 18)
                cp.type == "manhole" -> Color.rgb(31, 99, 196)
                else -> Color.rgb(107, 111, 120)
            }
            c.drawCircle(s[0], s[1], r * 0.8f, pPt)
            c.drawCircle(s[0], s[1], r * 0.8f, pStroke)
        }
        selected?.let { cp ->
            val s = toScreen(cp.px, cp.py)
            pPt.color = Color.argb(90, 255, 123, 51)
            c.drawCircle(s[0], s[1], r * 2.2f, pPt)
            pStroke.color = Color.rgb(255, 123, 51)
            c.drawCircle(s[0], s[1], r * 2.2f, pStroke)
            pStroke.color = Color.WHITE
            val label = cp.name
            val tw = pText.measureText(label)
            c.drawRect(s[0] + r * 2.4f, s[1] - 14 * dp, s[0] + r * 2.4f + tw + 8 * dp, s[1] + 6 * dp, pTextBg)
            c.drawText(label, s[0] + r * 2.4f + 4 * dp, s[1], pText)
        }
        val dev = device
        if (dev != null) {
            val img = p.localToPreview(dev[0], dev[1])
            val s = toScreen(img[0], img[1])
            heading?.let { h ->
                // направление взгляда: на превью вниз = на юг (= +z), вправо = восток (= +x)
                val ang = atan2(h[1].toDouble(), h[0].toDouble())
                val len = 46 * dp
                path.reset()
                path.moveTo(s[0], s[1])
                path.lineTo((s[0] + len * Math.cos(ang - 0.35)).toFloat(), (s[1] + len * Math.sin(ang - 0.35)).toFloat())
                path.lineTo((s[0] + len * Math.cos(ang + 0.35)).toFloat(), (s[1] + len * Math.sin(ang + 0.35)).toFloat())
                path.close()
                pPt.color = Color.argb(110, 33, 120, 255)
                c.drawPath(path, pPt)
            }
            pPt.color = Color.rgb(33, 120, 255)
            c.drawCircle(s[0], s[1], 9 * dp, pPt)
            c.drawCircle(s[0], s[1], 9 * dp, pStroke)
        }
        if (adjustMode) {
            pText.color = Color.rgb(221, 90, 18)
            c.drawText("Подвинуть: тащи план под синей точкой, двумя пальцами — поворот", 10 * dp, height - 12 * dp, pText)
            pText.color = Color.BLACK
        }
    }
}
