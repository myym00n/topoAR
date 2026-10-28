package ru.topoar

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

object Gl {
    private const val TAG = "TopoAR-GL"

    fun program(vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vs)
        val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw RuntimeException("Шейдер не собрался: $log")
        }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw RuntimeException("Ошибка шейдера: $log")
        }
        return s
    }

    fun floatBuffer(a: FloatArray): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(a)
        fb.position(0)
        return fb
    }

    fun check(where: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) Log.w(TAG, "GL error 0x${Integer.toHexString(e)} at $where")
    }

    fun maxTextureSize(): Int {
        val v = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, v, 0)
        return if (v[0] > 0) v[0] else 2048
    }

    fun uploadTexture(bmp: Bitmap): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)     // битмап предумножен на альфу
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        check("uploadTexture")
        return t[0]
    }
}

/** Фон: изображение камеры ARCore (внешняя OES-текстура). */
class BackgroundRenderer {
    var textureId = 0; private set
    private var prog = 0
    private var uTex = 0
    /** Углы экрана в NDC; texCoords заполняет ARCore (transformCoordinates2d). */
    val quadBuffer = Gl.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    val texCoords = Gl.floatBuffer(FloatArray(8))

    fun init() {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        textureId = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        prog = Gl.program(
            """#version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec2 aTex;
            out vec2 vTex;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTex = aTex; }
            """.trimIndent(),
            """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            in vec2 vTex;
            out vec4 oColor;
            void main() { oColor = texture(uTex, vTex); }
            """.trimIndent())
        uTex = GLES30.glGetUniformLocation(prog, "uTex")
    }

    fun draw() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(prog)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        quadBuffer.position(0)
        texCoords.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadBuffer)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDepthMask(true)
    }
}

/** Освещённые одноцветные меши (слои, маркеры). */
class MeshShader {
    private var prog = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uLight = 0

    fun init() {
        prog = Gl.program(
            """#version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNrm;
            uniform mat4 uMvp;
            uniform mat4 uModel;
            out vec3 vN;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); vN = mat3(uModel) * aNrm; }
            """.trimIndent(),
            """#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            uniform vec3 uLight;
            in vec3 vN;
            out vec4 oColor;
            void main() {
                vec3 n = normalize(vN);
                float d = max(dot(n, uLight), 0.0);
                float up = 0.5 + 0.5 * n.y;
                vec3 c = uColor.rgb * (0.42 + 0.18 * up + 0.48 * d);
                oColor = vec4(c * uColor.a, uColor.a);
            }
            """.trimIndent())
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp")
        uModel = GLES30.glGetUniformLocation(prog, "uModel")
        uColor = GLES30.glGetUniformLocation(prog, "uColor")
        uLight = GLES30.glGetUniformLocation(prog, "uLight")
    }

    fun begin(model: FloatArray, mvp: FloatArray) {
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES30.glUniform3f(uLight, 0.35f, 0.85f, 0.4f)
    }

    fun color(c: FloatArray, alphaMul: Float = 1f) = GLES30.glUniform4f(uColor, c[0], c[1], c[2], c[3] * alphaMul)
}

/** Слой на GPU: по VBO на каждую часть одного цвета. */
class GpuLayer(val data: LayerData) {
    private val vbos = IntArray(data.parts.size)
    private var uploaded = false

    fun upload() {
        if (uploaded || data.parts.isEmpty()) return
        GLES30.glGenBuffers(vbos.size, vbos, 0)
        data.parts.forEachIndexed { i, p ->
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[i])
            p.data.position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p.vertexCount * 24, p.data, GLES30.GL_STATIC_DRAW)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        uploaded = true
        Gl.check("GpuLayer.upload ${data.id}")
    }

    /** [translucent]: рисовать только полупрозрачные (true) или только непрозрачные (false) части. */
    fun draw(sh: MeshShader, translucent: Boolean, alphaMul: Float) {
        if (!uploaded) return
        data.parts.forEachIndexed { i, p ->
            val tr = p.color[3] * alphaMul < 0.999f
            if (tr != translucent) return@forEachIndexed
            sh.color(p.color, alphaMul)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[i])
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, p.vertexCount)
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (uploaded) GLES30.glDeleteBuffers(vbos.size, vbos, 0)
        uploaded = false
    }
}

/** Подложка: топоплан, натянутый на рельеф. */
class PlanRenderer {
    private var prog = 0
    private var uMvp = 0
    private var uTex = 0
    private var uAlpha = 0
    private var uPaper = 0
    private var vbo = 0
    private var ibo = 0
    private var tex = 0
    private var count = 0

    fun init() {
        prog = Gl.program(
            """#version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            uniform mat4 uMvp;
            out vec2 vUv;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); vUv = aUv; }
            """.trimIndent(),
            """#version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            uniform vec4 uPaper;      // предумноженный цвет «бумаги» под линиями (a = 0 — без бумаги)
            in vec2 vUv;
            out vec4 oColor;
            void main() {
                vec4 t = texture(uTex, vUv) * uAlpha;
                oColor = t + uPaper * (1.0 - t.a);
            }
            """.trimIndent())
        uMvp = GLES30.glGetUniformLocation(prog, "uMvp")
        uTex = GLES30.glGetUniformLocation(prog, "uTex")
        uAlpha = GLES30.glGetUniformLocation(prog, "uAlpha")
        uPaper = GLES30.glGetUniformLocation(prog, "uPaper")
    }

    fun upload(plan: PlanMesh, bmp: Bitmap) {
        release()
        val b = IntArray(2)
        GLES30.glGenBuffers(2, b, 0)
        vbo = b[0]; ibo = b[1]
        plan.vertices.position(0)
        plan.indices.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, plan.vertexCount * 20, plan.vertices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, plan.indexCount * 4, plan.indices, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        tex = Gl.uploadTexture(bmp)
        count = plan.indexCount
        Gl.check("PlanRenderer.upload")
    }

    /** [paperAlpha] > 0 — подложить под план светлую «землю» (для просмотра без камеры). */
    fun draw(mvp: FloatArray, alpha: Float, paperAlpha: Float = 0f) {
        if (count == 0) return
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uAlpha, alpha)
        val pa = paperAlpha
        GLES30.glUniform4f(uPaper, 0.93f * pa, 0.92f * pa, 0.87f * pa, pa)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glUniform1i(uTex, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    fun release() {
        if (vbo != 0) GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
        vbo = 0; ibo = 0; tex = 0; count = 0
    }
}

/** Простые фигуры для маркеров: кольцо прицела и вешка. */
class Shapes {
    private class Shape(val vbo: Int, val n: Int)
    private lateinit var ring: Shape
    private lateinit var pin: Shape
    private lateinit var disc: Shape
    private val tmpModel = FloatArray(16)
    private val tmpMvp = FloatArray(16)

    fun init() {
        ring = make(ringTris(0.10f, 0.13f, 48))
        disc = make(ringTris(0.0f, 0.03f, 20))
        pin = make(pinTris(0.04f, 1.6f, 12))
    }

    private fun make(v: FloatArray): Shape {
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, b[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, Gl.floatBuffer(v), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return Shape(b[0], v.size / 6)
    }

    private fun ringTris(r0: Float, r1: Float, seg: Int): FloatArray {
        val out = ArrayList<Float>()
        fun v(x: Float, z: Float) { out.addAll(listOf(x, 0f, z, 0f, 1f, 0f)) }
        for (i in 0 until seg) {
            val a0 = (2 * Math.PI * i / seg).toFloat()
            val a1 = (2 * Math.PI * (i + 1) / seg).toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            v(r0 * c0, r0 * s0); v(r1 * c0, r1 * s0); v(r1 * c1, r1 * s1)
            v(r0 * c0, r0 * s0); v(r1 * c1, r1 * s1); v(r0 * c1, r0 * s1)
        }
        return out.toFloatArray()
    }

    private fun pinTris(r: Float, h: Float, seg: Int): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until seg) {
            val a0 = (2 * Math.PI * i / seg).toFloat()
            val a1 = (2 * Math.PI * (i + 1) / seg).toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            fun v(x: Float, y: Float, z: Float, nx: Float, nz: Float) { out.addAll(listOf(x, y, z, nx, 0f, nz)) }
            v(r * c0, 0f, r * s0, c0, s0); v(r * c1, 0f, r * s1, c1, s1); v(r * c1, h, r * s1, c1, s1)
            v(r * c0, 0f, r * s0, c0, s0); v(r * c1, h, r * s1, c1, s1); v(r * c0, h, r * s0, c0, s0)
            // шарик-флажок сверху
            val rr = r * 3
            out.addAll(listOf(0f, h + rr, 0f, 0f, 1f, 0f))
            out.addAll(listOf(rr * c1, h, rr * s1, c1, 0f, s1))
            out.addAll(listOf(rr * c0, h, rr * s0, c0, 0f, s0))
        }
        return out.toFloatArray()
    }

    private fun drawShape(sh: MeshShader, s: Shape, model: FloatArray, viewProj: FloatArray, color: FloatArray) {
        Matrix.multiplyMM(tmpMvp, 0, viewProj, 0, model, 0)
        sh.begin(model, tmpMvp)
        sh.color(color)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, s.vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, s.n)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    fun drawReticle(sh: MeshShader, poseMatrix: FloatArray, viewProj: FloatArray) {
        drawShape(sh, ring, poseMatrix, viewProj, floatArrayOf(1f, 0.48f, 0.2f, 1f))
        drawShape(sh, disc, poseMatrix, viewProj, floatArrayOf(1f, 1f, 1f, 1f))
    }

    fun drawReticleAt(sh: MeshShader, x: Float, y: Float, z: Float, viewProj: FloatArray, ok: Boolean) {
        Matrix.setIdentityM(tmpModel, 0)
        Matrix.translateM(tmpModel, 0, x, y + 0.01f, z)
        drawShape(sh, ring, tmpModel, viewProj, if (ok) floatArrayOf(1f, 0.48f, 0.2f, 1f) else floatArrayOf(1f, 1f, 1f, 0.8f))
        drawShape(sh, disc, tmpModel, viewProj, floatArrayOf(1f, 1f, 1f, 1f))
    }

    fun drawPin(sh: MeshShader, x: Float, y: Float, z: Float, scale: Float, viewProj: FloatArray, color: FloatArray) {
        Matrix.setIdentityM(tmpModel, 0)
        Matrix.translateM(tmpModel, 0, x, y, z)
        Matrix.scaleM(tmpModel, 0, scale, scale, scale)
        drawShape(sh, pin, tmpModel, viewProj, color)
    }
}
