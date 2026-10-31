package ru.topoar

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import android.graphics.Bitmap

enum class Mode { START, VIEWER, TABLE, FIELD }

/** Снимок состояния кадра для интерфейса. */
class FrameState(
    val tracking: Boolean,
    val message: String,
    val camPos: FloatArray,
    val camFwd: FloatArray,          // горизонтальное направление взгляда (x, z), нормировано
    val aim: FloatArray?,            // точка земли/стола под центром экрана (сглаженная)
    val aimOnPlane: Boolean,         // попали в найденную ARCore плоскость (а не в «примерную» землю)
    val groundY: Float,              // высота земли в мире AR
    val groundKnown: Boolean,        // земля найдена (есть горизонтальная плоскость)
    val camRight: FloatArray = floatArrayOf(1f, 0f)
)

/** Камера для 3D-просмотра без AR: вращение вокруг центра участка. */
class OrbitCam {
    @Volatile var tx = 0f; @Volatile var ty = 0f; @Volatile var tz = 0f
    @Volatile var dist = 200f
    @Volatile var yaw = 0.6f
    @Volatile var pitch = 0.7f
    /** Размер участка (м) — для дальней плоскости отсечения. */
    var extent = 500f
    val fovDeg = 50f

    fun eye(): FloatArray {
        val p = pitch.coerceIn(-0.3f, 1.55f)
        return floatArrayOf(tx + dist * cos(p) * sin(yaw), ty + dist * sin(p), tz + dist * cos(p) * cos(yaw))
    }

    fun matrices(w: Int, h: Int, view: FloatArray, proj: FloatArray) {
        pitch = pitch.coerceIn(-0.3f, 1.55f)
        val c = eye()
        Matrix.setLookAtM(view, 0, c[0], c[1], c[2], tx, ty, tz, 0f, 1f, 0f)
        val near = max(0.005f * dist, 0.002f)
        Matrix.perspectiveM(proj, 0, fovDeg, w.toFloat() / max(1, h), near, dist * 20f + extent * 3f)
    }

    /** Луч из камеры через пиксель (px, py): [ox, oy, oz, dx, dy, dz]. */
    fun ray(px: Float, py: Float, w: Int, h: Int): FloatArray {
        val c = eye()
        var fx = tx - c[0]; var fy = ty - c[1]; var fz = tz - c[2]
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        // right = f × up(0,1,0) = (-fz, 0, fx)
        var rx = -fz; var rz = fx
        val rl = max(1e-6f, sqrt(rx * rx + rz * rz)); rx /= rl; rz /= rl
        // up' = right × f
        val ux = -rz * fy; val uy = rz * fx - rx * fz; val uz = rx * fy
        val th = tan(Math.toRadians(fovDeg / 2.0)).toFloat()
        val aspect = w.toFloat() / max(1, h)
        val nx = (2f * px / max(1, w) - 1f) * th * aspect
        val ny = (1f - 2f * py / max(1, h)) * th
        var dx = fx + rx * nx + ux * ny; var dy = fy + uy * ny; var dz = fz + rz * nx + uz * ny
        val dl = sqrt(dx * dx + dy * dy + dz * dz); dx /= dl; dy /= dl; dz /= dl
        return floatArrayOf(c[0], c[1], c[2], dx, dy, dz)
    }

    /** Направление взгляда (единичное). */
    fun forward(): FloatArray {
        val c = eye()
        val fx = tx - c[0]; val fy = ty - c[1]; val fz = tz - c[2]
        val l = sqrt(fx * fx + fy * fy + fz * fz)
        return floatArrayOf(fx / l, fy / l, fz / l)
    }

    /**
     * Приближение к точке [p] (мир) в [s] раз. Сначала «фокус» переносится на глубину точки
     * (вид не меняется), потом гомотетия с центром в точке — она остаётся под пальцем.
     */
    fun zoomTo(p: FloatArray?, s: Float) {
        if (p != null) {
            val c = eye(); val f = forward()
            val d = (p[0] - c[0]) * f[0] + (p[1] - c[1]) * f[1] + (p[2] - c[2]) * f[2]
            if (d > 0.01f) {
                tx = c[0] + f[0] * d; ty = c[1] + f[1] * d; tz = c[2] + f[2] * d
                dist = d
            }
            tx = p[0] + (tx - p[0]) / s; ty = p[1] + (ty - p[1]) / s; tz = p[2] + (tz - p[2]) / s
        }
        dist = (dist / s).coerceIn(0.05f, 50000f)
    }

    /** «Показать всё»: рамка в кадре, направление взгляда прежнее. */
    fun fit(box: FloatArray, w: Int, h: Int) {
        tx = (box[0] + box[3]) / 2; ty = (box[1] + box[4]) / 2; tz = (box[2] + box[5]) / 2
        val dx = box[3] - box[0]; val dy = box[4] - box[1]; val dz = box[5] - box[2]
        val diag = sqrt(dx * dx + dy * dy + dz * dz)
        val vh = Math.toRadians(fovDeg / 2.0)
        val aspect = w.toDouble() / max(1, h)
        val hh = atan(tan(vh) * aspect)
        val half = min(vh, hh)
        dist = ((diag / 2) / tan(half)).toFloat().coerceIn(0.5f, 50000f)
    }

    fun pan(dxPx: Float, dyPx: Float, hPx: Int) {
        val k = dist / max(1, hPx) * 1.2f
        val rx = cos(yaw); val rz = -sin(yaw)          // «вправо» на экране, в плане
        val fx = -sin(yaw); val fz = -cos(yaw)         // «от себя» на экране, в плане
        tx += (-rx * dxPx + fx * dyPx) * k
        tz += (-rz * dxPx + fz * dyPx) * k
    }
}

class ArRenderer(private val onError: (String) -> Unit) : GLSurfaceView.Renderer {
    val lock = Any()
    @Volatile var mode = Mode.START
    @Volatile var session: Session? = null
    @Volatile var displayRotation = 0
    @Volatile var aligner: Aligner = Aligner()
    @Volatile var frameState = FrameState(false, "Запуск камеры…", FloatArray(3), floatArrayOf(0f, -1f), null, false, 0f, false)
    /** «Подземка» (рентген): земля полупрозрачная, сети видны. Видимость слоёв меняет MainActivity. */
    @Volatile var underground = false
    @Volatile var planAlpha = 0.85f
    @Volatile var showPlan = true
    @Volatile var showReticle = false
    @Volatile var showPoints = true
    @Volatile var selectedPoint: FloatArray? = null       // локальные координаты выбранной точки плана
    @Volatile var maxTexture = 4096; private set
    /** Предел полноразмерной плитки подложки (2048 на слабых телефонах). */
    @Volatile var fullTileMax = 4096
    /** Сколько плиток держать в полном размере. */
    @Volatile var maxFullTiles = 6
    @Volatile var tileInfo = ""; private set
    val layerVisible = ConcurrentHashMap<String, Boolean>()
    val orbit = OrbitCam()

    private val bg = BackgroundRenderer()
    private val meshShader = MeshShader()
    private val planR = TexMeshRenderer()
    private val pointR = PointRenderer()
    private val shapes = Shapes()
    private var gpuLayers: List<GpuLayer> = emptyList()
    private val texLayers = HashMap<String, TexMeshRenderer>()
    private val pointLayers = HashMap<String, PointRenderer.Gpu>()
    @Volatile private var pending: TopoPackage? = null
    @Volatile var loaded: TopoPackage? = null; private set

    private var camTexSet = false
    private var geometryChanged = true
    var w = 1; private set
    var h = 1; private set
    private var glReady = false
    private var pxPerMeter = 1000f

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var smoothAim: FloatArray? = null

    // ── подгрузка плиток подложки ──
    private val decoder = Executors.newSingleThreadExecutor()
    private class Decoded(val pkg: TopoPackage, val index: Int, val bmp: Bitmap?)
    private val decoded = ConcurrentLinkedQueue<Decoded>()
    @Volatile private var decoding = -1
    private var lastLod = 0L

    fun setPackage(p: TopoPackage) {
        layerVisible.clear()
        for (l in p.layers) layerVisible[l.id] = l.defaultVisible
        resetOrbit(p)
        pending = p
    }

    /** Видимость слоёв как в scene.json. */
    fun resetVisibility() {
        val p = loaded ?: pending ?: return
        for (l in p.layers) layerVisible[l.id] = l.defaultVisible
    }

    /** Камера просмотра — весь участок в кадре, вид наискосок. */
    fun resetOrbit(p: TopoPackage) {
        orbit.extent = max(p.width, p.height).toFloat()
        orbit.yaw = 0.5f; orbit.pitch = 0.75f
        orbit.fit(p.fitBox, max(w, 1), max(h, 1))
    }

    /** Новая сессия ARCore (после смены режима) — заново выставить текстуру камеры и геометрию. */
    fun resetAr() {
        camTexSet = false
        geometryChanged = true
        smoothAim = null
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        try {
            Gl.detectAniso()
            bg.init(); meshShader.init(); planR.init(); pointR.init(); shapes.init()
            maxTexture = minOf(Gl.maxTextureSize(), 8192)
            glReady = true
        } catch (e: Exception) {
            onError("Ошибка графики: ${e.message}")
        }
        camTexSet = false
        loaded?.let { pending = it }
        gpuLayers = emptyList()
        texLayers.clear()
        pointLayers.clear()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        w = width; h = height
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(lock) {
            try {
                frame()
            } catch (t: Throwable) {
                Log.e("TopoAR", "frame", t)
                frameState = FrameState(false, "Ошибка кадра: ${t.message}", FloatArray(3), floatArrayOf(0f, -1f), null, false, 0f, false)
            }
        }
    }

    private fun frame() {
        if (!glReady) { GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT); return }
        pending?.let { uploadPackage(it) }
        acceptDecodedTile()
        when (mode) {
            Mode.START -> {
                GLES30.glClearColor(0.07f, 0.09f, 0.08f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            }
            Mode.VIEWER -> viewerFrame()
            Mode.TABLE, Mode.FIELD -> arFrame()
        }
    }

    // ───────────────────── 3D-просмотр без камеры ─────────────────────

    private fun viewerFrame() {
        GLES30.glClearColor(0.80f, 0.86f, 0.90f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        orbit.matrices(w, h, view, proj)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        pxPerMeter = h * proj[5] / 2f
        val ga = if (underground) 0.6f else 1f
        drawScene(identity, ga, paper = ga, modelScale = 1f)
        if (showPoints) drawControlPins(identity, orbit.dist / 120f)
        selectedPoint?.let { q -> shapes.drawPin(meshShader, q[0], q[1], q[2], max(0.05f, orbit.dist / 60f), viewProj, ORANGE) }
    }

    // ───────────────────── AR ─────────────────────

    private fun arFrame() {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (!camTexSet) { s.setCameraTextureName(bg.textureId); camTexSet = true }
        if (geometryChanged) { s.setDisplayGeometry(displayRotation, w, h); geometryChanged = false }

        val frame = s.update()
        val camera = frame.camera
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, bg.quadBuffer,
                Coordinates2d.TEXTURE_NORMALIZED, bg.texCoords)
        }
        if (frame.timestamp == 0L) return
        bg.draw()

        if (camera.trackingState != TrackingState.TRACKING) {
            frameState = FrameState(false, reason(camera.trackingFailureReason), FloatArray(3), floatArrayOf(0f, -1f), null, false, 0f, false)
            return
        }
        camera.getProjectionMatrix(proj, 0, 0.03f, 3000f)
        pxPerMeter = h * proj[5] / 2f
        camera.getViewMatrix(view, 0)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        val pose = camera.pose
        val cam = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
        val z = pose.zAxis
        val dirX = -z[0]; val dirY = -z[1]; val dirZ = -z[2]
        var fx = dirX; var fz = dirZ
        val n = hypot(fx.toDouble(), fz.toDouble()).toFloat()
        if (n > 1e-3f) { fx /= n; fz /= n } else { fx = 0f; fz = -1f }

        // Земля: самая низкая горизонтальная плоскость (пол/асфальт), иначе — «рост» 1,4 м
        var groundY = cam[1] - 1.4f
        var groundKnown = false
        for (p in s.getAllTrackables(Plane::class.java)) {
            if (p.trackingState != TrackingState.TRACKING || p.subsumedBy != null) continue
            if (p.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            val py = p.centerPose.ty()
            if (py > cam[1] - 0.3f) continue                     // выше уровня глаз — это не земля
            if (!groundKnown || (mode == Mode.FIELD && py < groundY) || (mode == Mode.TABLE && py > groundY)) {
                groundY = py; groundKnown = true
            }
        }

        // Прицел: сначала точное попадание в горизонтальную плоскость, иначе луч до уровня земли
        var aim: FloatArray? = null
        var onPlane = false
        for (hr in frame.hitTest(w / 2f, h / 2f)) {
            val t = hr.trackable
            if (t is Plane && t.trackingState == TrackingState.TRACKING && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                t.isPoseInPolygon(hr.hitPose)) {
                val hp = hr.hitPose
                aim = floatArrayOf(hp.tx(), hp.ty(), hp.tz()); onPlane = true
                break
            }
        }
        if (aim == null && dirY < -0.05f) {
            val tt = (groundY - cam[1]) / dirY
            if (tt in 0f..80f) aim = floatArrayOf(cam[0] + dirX * tt, groundY, cam[2] + dirZ * tt)
        }
        // сглаживание: прицел не дёргается, но и не «тянется» при резком переводе
        val prev = smoothAim
        if (aim != null && prev != null &&
            hypot((aim[0] - prev[0]).toDouble(), (aim[2] - prev[2]).toDouble()) < 1.0 && abs(aim[1] - prev[1]) < 0.3f) {
            val a = 0.25f
            aim = floatArrayOf(prev[0] + (aim[0] - prev[0]) * a, prev[1] + (aim[1] - prev[1]) * a, prev[2] + (aim[2] - prev[2]) * a)
        }
        smoothAim = aim

        frameState = FrameState(true, "", cam, floatArrayOf(fx, fz), aim, onPlane, groundY, groundKnown, floatArrayOf(-fz, fx))

        val al = aligner
        if (al.aligned) {
            al.modelMatrix(model)
            // поверх камеры на участке землю в «Подземке» не рисуем: видны трассы и трубы
            val groundA = if (underground) (if (mode == Mode.FIELD) 0f else 0.6f) else planAlpha
            drawScene(model, groundA, paper = 0f, modelScale = al.scale.toFloat())
            if (showPoints) drawControlPins(model, (if (mode == Mode.TABLE) 0.02f else 0.5f))
            selectedPoint?.let { q ->
                val p = al.toAr(q)
                shapes.drawPin(meshShader, p[0], p[1], p[2], (al.scale.toFloat() * 2f).coerceIn(0.03f, 1.2f), viewProj, ORANGE)
            }
        }
        for (o in al.snapshot()) shapes.drawPin(meshShader, o.p[0], o.p[1], o.p[2], 0.6f, viewProj, BLUE)
        if (showReticle && aim != null) shapes.drawReticleAt(meshShader, aim[0], aim[1], aim[2], viewProj, onPlane)
    }

    /**
     * Порядок: непрозрачное (запись глубины) → полупрозрачное без записи глубины.
     * [groundAlpha] — прозрачность «земли» (подложка, model, pg_model): 1 обычно, 0.6 в «Подземке», 0 поверх камеры.
     */
    private fun drawScene(modelM: FloatArray, groundAlpha: Float, paper: Float, modelScale: Float) {
        val p = loaded ?: return
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)   // цвета предумножены
        Matrix.multiplyMM(mvp, 0, viewProj, 0, modelM, 0)
        val groundOpaque = groundAlpha >= 0.999f

        // 1. непрозрачное
        meshShader.begin(modelM, mvp)
        for (g in gpuLayers) if (vis(g.data) && (!g.data.isGround || groundOpaque)) g.draw(meshShader, false, 1f)
        for (l in p.layers) {
            if (!vis(l)) continue
            texLayers[l.id]?.let { if (!l.isGround || groundOpaque) it.draw(mvp, 1f) }
            pointLayers[l.id]?.let { if (!l.isGround || groundOpaque) pointR.draw(it, mvp, pxPerMeter, modelScale, 1f) }
        }

        // 2. полупрозрачное
        GLES30.glDepthMask(false)
        meshShader.begin(modelM, mvp)
        for (g in gpuLayers) if (vis(g.data) && (!g.data.isGround || groundOpaque)) g.draw(meshShader, true, 1f)
        if (showPlan && planR.ready) {
            val a = if (groundOpaque) planAlpha else groundAlpha
            planR.draw(mvp, a, paper * if (groundOpaque) 1f else groundAlpha)
            updateTileLod(p, mvp)
        }
        if (!groundOpaque && groundAlpha > 0.001f) {
            meshShader.begin(modelM, mvp)
            for (g in gpuLayers) if (vis(g.data) && g.data.isGround) {
                g.draw(meshShader, false, groundAlpha); g.draw(meshShader, true, groundAlpha)
            }
            for (l in p.layers) {
                if (!vis(l) || !l.isGround) continue
                texLayers[l.id]?.draw(mvp, groundAlpha)
                pointLayers[l.id]?.let { pointR.draw(it, mvp, pxPerMeter, modelScale, groundAlpha) }
            }
        }
        GLES30.glDepthMask(true)
    }

    private fun vis(l: LayerData): Boolean = layerVisible[l.id] ?: l.defaultVisible

    /** Значки точек привязки: красные — опорные, жёлтые — съёмочные, синие — колодцы. */
    private fun drawControlPins(modelM: FloatArray, size: Float) {
        val p = loaded ?: return
        val tmp = FloatArray(4)
        for (c in p.controlPoints) {
            val col = when (c.type) {
                "corner" -> RED; "point" -> YELLOW; "manhole" -> BLUE; else -> GREY
            }
            Matrix.multiplyMV(tmp, 0, modelM, 0, floatArrayOf(c.local[0], c.local[1], c.local[2], 1f), 0)
            shapes.drawPin(meshShader, tmp[0], tmp[1], tmp[2], size, viewProj, col)
        }
    }

    // ───────────────────── детализация подложки ─────────────────────

    /** Раз в 0,3 с: какие плитки крупно на экране — тем полное разрешение (по одной за раз). */
    private fun updateTileLod(p: TopoPackage, mvpM: FloatArray) {
        val ts = planR.tiles ?: return
        val now = System.currentTimeMillis()
        if (now - lastLod < 300) return
        lastLod = now
        val ov = p.texture
        val ovPx = if (ov != null) ov.width.toFloat() / ts.nx else 512f
        val threshold = min(800f, ovPx * 1.2f)
        val sizes = FloatArray(ts.tiles.size)
        val v = FloatArray(4)
        ts.tiles.forEachIndexed { i, t ->
            val b = t.box
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var behind = 0
            for (k in 0 until 8) {
                val x = if (k and 1 == 0) b[0] else b[3]
                val y = if (k and 2 == 0) b[1] else b[4]
                val z = if (k and 4 == 0) b[2] else b[5]
                Matrix.multiplyMV(v, 0, mvpM, 0, floatArrayOf(x, y, z, 1f), 0)
                if (v[3] <= 0.001f) { behind++; continue }
                val nx = v[0] / v[3]; val ny = v[1] / v[3]
                minX = min(minX, nx); maxX = max(maxX, nx); minY = min(minY, ny); maxY = max(maxY, ny)
            }
            sizes[i] = when {
                behind == 8 -> 0f
                behind > 0 -> 1e6f                                  // камера внутри/над плиткой — самая важная
                maxX < -1f || minX > 1f || maxY < -1f || minY > 1f -> 0f
                else -> max((min(maxX, 1f) - max(minX, -1f)) * w / 2f, (min(maxY, 1f) - max(minY, -1f)) * h / 2f)
            }
        }
        val order = sizes.indices.filter { sizes[it] > threshold }.sortedByDescending { sizes[it] }
        val want = order.take(maxFullTiles).toSet()
        // лишние полноразмерные — обратно к обзорной (с запасом, чтобы не мигали)
        val keep = order.take(maxFullTiles + 2).toSet()
        for (i in planR.fullTex.keys.toList()) if (i !in keep) planR.dropFullTile(i)
        // следующую — в фоне
        if (decoding < 0) {
            val next = order.firstOrNull { it in want && !planR.fullTex.containsKey(it) }
            if (next != null) {
                decoding = next
                val tile = ts.tiles[next]
                val limit = min(fullTileMax, maxTexture)
                decoder.execute { decoded.add(Decoded(p, next, p.decodeTile(tile, limit))) }
            }
        }
        tileInfo = "плитки: ${planR.fullTex.size}/${ts.tiles.size} чётких"
    }

    private fun acceptDecodedTile() {
        val d = decoded.poll() ?: return
        decoding = -1
        val bmp = d.bmp ?: return
        if (d.pkg === loaded && planR.tiles != null) planR.setFullTile(d.index, bmp)
        bmp.recycle()
    }

    private fun uploadPackage(p: TopoPackage) {
        pending = null
        gpuLayers.forEach { it.release() }
        texLayers.values.forEach { it.release() }
        texLayers.clear()
        pointLayers.values.forEach { pointR.release(it) }
        pointLayers.clear()
        planR.release()
        decoded.clear()
        decoding = -1
        val list = p.layers.filter { it.parts.isNotEmpty() }.map { GpuLayer(it) }
        list.forEach { it.upload() }
        gpuLayers = list
        for (l in p.layers) {
            if (l.texMesh != null && l.texture != null) {
                val r = TexMeshRenderer(); r.init(); r.upload(l.texMesh, l.texture); texLayers[l.id] = r
            }
            l.points?.let { pointLayers[l.id] = pointR.upload(it) }
        }
        if (p.plan != null && p.texture != null) planR.upload(p.plan, p.texture, p.tiles)
        loaded = p
    }

    private fun reason(r: TrackingFailureReason): String = when (r) {
        TrackingFailureReason.NONE -> "Медленно поводите телефоном вокруг — ищу поверхность"
        TrackingFailureReason.BAD_STATE -> "ARCore: внутренняя ошибка, вернитесь назад и зайдите снова"
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Мало света"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Слишком быстро — двигайте телефон плавнее"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Не за что зацепиться — наведите на землю/асфальт с текстурой"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Камера занята другим приложением"
        else -> "Трекинг потерян"
    }

    companion object {
        val ORANGE = floatArrayOf(1f, 0.48f, 0.2f, 1f)
        val BLUE = floatArrayOf(0.13f, 0.47f, 1f, 1f)
        val RED = floatArrayOf(0.86f, 0.16f, 0.16f, 1f)
        val YELLOW = floatArrayOf(0.98f, 0.80f, 0.10f, 1f)
        val GREY = floatArrayOf(0.55f, 0.57f, 0.6f, 1f)
    }
}
