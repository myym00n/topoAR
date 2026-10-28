package ru.topoar

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

/** Снимок состояния кадра для интерфейса. */
class FrameState(
    val tracking: Boolean,
    val message: String,
    val camPos: FloatArray,
    val camFwd: FloatArray,          // горизонтальное направление взгляда (x, z), нормировано
    val hit: FloatArray?,            // точка под крестиком, мир AR
    val hitDist: Float
)

class ArRenderer(private val onError: (String) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile var displayRotation = 0
    @Volatile var aligner: Aligner = Aligner()
    @Volatile var frameState = FrameState(false, "Запуск камеры…", FloatArray(3), floatArrayOf(0f, -1f), null, 0f)
    @Volatile var underground = false
    @Volatile var planAlpha = 0.85f
    @Volatile var showPlan = true
    @Volatile var selectedPoint: FloatArray? = null       // локальные координаты выбранной опорной точки
    @Volatile var maxTexture = 4096; private set
    val layerVisible = ConcurrentHashMap<String, Boolean>()

    private val bg = BackgroundRenderer()
    private val meshShader = MeshShader()
    private val planR = PlanRenderer()
    private val shapes = Shapes()
    private var gpuLayers: List<GpuLayer> = emptyList()
    @Volatile private var pending: TopoPackage? = null
    @Volatile var loaded: TopoPackage? = null; private set

    private var camTexSet = false
    private var geometryChanged = true
    private var w = 1
    private var h = 1
    private var glReady = false

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val hitMat = FloatArray(16)

    fun setPackage(p: TopoPackage) {
        layerVisible.clear()
        for (l in p.layers) layerVisible[l.id] = l.visible
        pending = p
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
        // после потери контекста всё загружаем заново
        loaded?.let { pending = it }
        gpuLayers = emptyList()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        w = width; h = height
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (!glReady) return
        val s = session ?: return
        try {
            if (!camTexSet) { s.setCameraTextureName(bg.textureId); camTexSet = true }
            if (geometryChanged) { s.setDisplayGeometry(displayRotation, w, h); geometryChanged = false }
            pending?.let { uploadPackage(it) }

            val frame = s.update()
            val camera = frame.camera
            if (frame.hasDisplayGeometryChanged()) {
                frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, bg.quadBuffer,
                    Coordinates2d.TEXTURE_NORMALIZED, bg.texCoords)
            }
            if (frame.timestamp == 0L) return
            bg.draw()

            if (camera.trackingState != TrackingState.TRACKING) {
                frameState = FrameState(false, reason(camera.trackingFailureReason), FloatArray(3), floatArrayOf(0f, -1f), null, 0f)
                return
            }
            camera.getProjectionMatrix(proj, 0, 0.05f, 3000f)
            camera.getViewMatrix(view, 0)
            Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

            val pose = camera.pose
            val cam = floatArrayOf(pose.tx(), pose.ty(), pose.tz())
            val z = pose.zAxis
            var fx = -z[0]; var fz = -z[2]
            val n = hypot(fx.toDouble(), fz.toDouble()).toFloat()
            if (n > 1e-3f) { fx /= n; fz /= n } else { fx = 0f; fz = -1f }

            var hit: HitResult? = null
            for (hr in frame.hitTest(w / 2f, h / 2f)) {
                val t = hr.trackable
                val ok = when (t) {
                    is Plane -> t.trackingState == TrackingState.TRACKING && t.isPoseInPolygon(hr.hitPose)
                    is DepthPoint -> true
                    is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                    else -> false
                }
                if (ok) { hit = hr; break }
            }
            val hp = hit?.hitPose
            val hitPos = hp?.let { floatArrayOf(it.tx(), it.ty(), it.tz()) }
            frameState = FrameState(true, "", cam, floatArrayOf(fx, fz), hitPos, hit?.distance ?: 0f)

            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(true)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            val al = aligner
            if (al.aligned && (gpuLayers.isNotEmpty() || loaded?.plan != null)) {
                al.modelMatrix(model)
                Matrix.multiplyMM(mvp, 0, viewProj, 0, model, 0)
                meshShader.begin(model, mvp)
                val under = underground
                for (gLayer in gpuLayers) if (visible(gLayer, under)) gLayer.draw(meshShader, false, 1f)
                GLES30.glDepthMask(false)
                for (gLayer in gpuLayers) if (visible(gLayer, under)) gLayer.draw(meshShader, true, 1f)
                if (showPlan) planR.draw(mvp, if (under) minOf(planAlpha, 0.4f) else planAlpha)
                GLES30.glDepthMask(true)

                selectedPoint?.let { q ->
                    val p = al.toAr(q)
                    shapes.drawPin(meshShader, p[0], p[1], p[2], al.scale.toFloat().coerceAtLeast(0.05f), viewProj,
                        floatArrayOf(1f, 0.48f, 0.2f, 1f))
                }
            }
            for (o in al.snapshot()) {
                shapes.drawPin(meshShader, o.p[0], o.p[1], o.p[2], 0.6f, viewProj, floatArrayOf(0.13f, 0.47f, 1f, 1f))
            }
            if (hp != null) {
                hp.toMatrix(hitMat, 0)
                shapes.drawReticle(meshShader, hitMat, viewProj)
            }
        } catch (t: Throwable) {
            Log.e("TopoAR", "frame", t)
            frameState = FrameState(false, "Ошибка кадра: ${t.message}", FloatArray(3), floatArrayOf(0f, -1f), null, 0f)
        }
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
        TrackingFailureReason.NONE -> "Поводите телефоном, ищу поверхность…"
        TrackingFailureReason.BAD_STATE -> "ARCore: внутренняя ошибка, перезапустите"
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Мало света"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Слишком быстрое движение"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Не за что зацепиться: наведите на текстурную поверхность"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Камера занята другим приложением"
        else -> "Трекинг потерян"
    }
}
