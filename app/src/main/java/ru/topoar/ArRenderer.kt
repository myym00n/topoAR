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
    var tx = 0f; var ty = 0f; var tz = 0f
    var dist = 200f
    var yaw = 0.6f
    var pitch = 0.7f

    fun matrices(w: Int, h: Int, view: FloatArray, proj: FloatArray) {
        pitch = pitch.coerceIn(0.08f, 1.5f)
        val cx = tx + dist * cos(pitch) * sin(yaw)
        val cy = ty + dist * sin(pitch)
        val cz = tz + dist * cos(pitch) * cos(yaw)
        Matrix.setLookAtM(view, 0, cx, cy, cz, tx, ty, tz, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 50f, w.toFloat() / max(1, h), max(0.1f, dist / 200f), dist * 20f)
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
    @Volatile var underground = false
    @Volatile var planAlpha = 0.85f
    @Volatile var showPlan = true
    @Volatile var showReticle = false
    @Volatile var selectedPoint: FloatArray? = null       // локальные координаты выбранной точки плана
    @Volatile var maxTexture = 4096; private set
    val layerVisible = ConcurrentHashMap<String, Boolean>()
    val orbit = OrbitCam()

    private val bg = BackgroundRenderer()
    private val meshShader = MeshShader()
    private val planR = PlanRenderer()
    private val shapes = Shapes()
    private var gpuLayers: List<GpuLayer> = emptyList()
    @Volatile private var pending: TopoPackage? = null
    @Volatile var loaded: TopoPackage? = null; private set

    private var camTexSet = false
    private var geometryChanged = true
    var w = 1; private set
    var h = 1; private set
    private var glReady = false

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private var smoothAim: FloatArray? = null

    fun setPackage(p: TopoPackage) {
        layerVisible.clear()
        for (l in p.layers) layerVisible[l.id] = l.visible
        resetOrbit(p)
        pending = p
    }

    /** Камера просмотра — на центр участка, вид наискосок. */
    fun resetOrbit(p: TopoPackage) {
        orbit.tx = ((p.minX + p.maxX) / 2 - p.originX).toFloat()
        orbit.tz = (-((p.minY + p.maxY) / 2 - p.originY)).toFloat()
        orbit.ty = p.groundAt(orbit.tx, orbit.tz)
        orbit.dist = (max(p.width, p.height) * 0.9).toFloat()
        orbit.yaw = 0.5f; orbit.pitch = 0.75f
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
            bg.init(); meshShader.init(); planR.init(); shapes.init()
            maxTexture = minOf(Gl.maxTextureSize(), 8192)
            glReady = true
        } catch (e: Exception) {
            onError("Ошибка графики: ${e.message}")
        }
        camTexSet = false
        loaded?.let { pending = it }
        gpuLayers = emptyList()
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
        drawScene(identity, paper = if (underground) 0.35f else 1f)
        selectedPoint?.let { q -> shapes.drawPin(meshShader, q[0], q[1], q[2], max(1f, orbit.dist / 60f), viewProj, ORANGE) }
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
        camera.getProjectionMatrix(proj, 0, 0.05f, 3000f)
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
            drawScene(model, paper = 0f)
            selectedPoint?.let { q ->
                val p = al.toAr(q)
                shapes.drawPin(meshShader, p[0], p[1], p[2], (al.scale.toFloat() * 2f).coerceIn(0.08f, 1.2f), viewProj, ORANGE)
            }
        }
        for (o in al.snapshot()) shapes.drawPin(meshShader, o.p[0], o.p[1], o.p[2], 0.6f, viewProj, BLUE)
        if (showReticle && aim != null) shapes.drawReticleAt(meshShader, aim[0], aim[1], aim[2], viewProj, onPlane)
    }

    private fun drawScene(modelM: FloatArray, paper: Float) {
        if (gpuLayers.isEmpty() && loaded?.plan == null) return
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        Matrix.multiplyMM(mvp, 0, viewProj, 0, modelM, 0)
        meshShader.begin(modelM, mvp)
        val under = underground
        for (g in gpuLayers) if (visible(g, under)) g.draw(meshShader, false, 1f)
        GLES30.glDepthMask(false)
        for (g in gpuLayers) if (visible(g, under)) g.draw(meshShader, true, 1f)
        if (showPlan) planR.draw(mvp, if (under) minOf(planAlpha, 0.45f) else planAlpha, paper)
        GLES30.glDepthMask(true)
    }

    private fun visible(g: GpuLayer, under: Boolean): Boolean {
        val v = layerVisible[g.data.id] ?: g.data.visible
        return if (g.data.underground) under && v else v
    }

    private fun uploadPackage(p: TopoPackage) {
        pending = null
        gpuLayers.forEach { it.release() }
        planR.release()
        val list = p.layers.map { GpuLayer(it) }
        list.forEach { it.upload() }
        gpuLayers = list
        if (p.plan != null && p.texture != null) planR.upload(p.plan, p.texture)
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
    }
}
