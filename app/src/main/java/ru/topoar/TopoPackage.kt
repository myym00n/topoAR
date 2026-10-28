package ru.topoar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.zip.ZipFile

/** Опорная точка с плана: угол здания, колодец, опора. */
class ControlPoint(
    val id: Int,
    val name: String,
    val type: String,
    val x: Double, val y: Double, val h: Double,      // МСК: восток, север, высота
    val local: FloatArray,                          // локальные: x восток, y вверх, z = −север
    val px: Float, val py: Float                    // пиксели на превью плана
)

/** Часть слоя одного цвета: вершины (позиция + нормаль). */
class MeshPart(val color: FloatArray, val vertexCount: Int, val data: FloatBuffer)

class LayerData(
    val id: String,
    val title: String,
    val underground: Boolean,
    var visible: Boolean,
    val parts: List<MeshPart>
)

class PlanMesh(val vertexCount: Int, val indexCount: Int, val vertices: FloatBuffer, val indices: IntBuffer)

/** Распакованный пакет .topoar из конвертера topo3d.py. */
class TopoPackage(
    val name: String,
    val originX: Double, val originY: Double, val originZ: Double,
    val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
    val gridNx: Int, val gridNy: Int, val gridLift: Float,
    val preview: Bitmap,
    val texture: Bitmap?,
    val plan: PlanMesh?,
    val layers: List<LayerData>,
    val controlPoints: List<ControlPoint>,
    val stats: String
) {
    val width get() = maxX - minX
    val height get() = maxY - minY

    /** Пиксель превью → локальные координаты (x, z). */
    fun previewToLocal(px: Float, py: Float): FloatArray {
        val e = minX + px / preview.width * width
        val n = maxY - py / preview.height * height
        return floatArrayOf((e - originX).toFloat(), (-(n - originY)).toFloat())
    }

    /** Локальные (x, z) → пиксель превью. */
    fun localToPreview(lx: Float, lz: Float): FloatArray {
        val e = lx + originX
        val n = originY - lz
        return floatArrayOf(((e - minX) / width * preview.width).toFloat(), ((maxY - n) / height * preview.height).toFloat())
    }

    /** Высота земли (локальная) в точке, по сетке подложки. */
    fun groundAt(lx: Float, lz: Float): Float {
        val p = plan ?: return 0f
        if (gridNx < 2 || gridNy < 2) return 0f
        val e = lx + originX
        val n = originY - lz
        val fx = ((e - minX) / width * (gridNx - 1)).coerceIn(0.0, (gridNx - 1).toDouble())
        val fy = ((n - minY) / height * (gridNy - 1)).coerceIn(0.0, (gridNy - 1).toDouble())
        val i0 = fx.toInt().coerceAtMost(gridNx - 2)
        val j0 = fy.toInt().coerceAtMost(gridNy - 2)
        val tx = (fx - i0).toFloat()
        val ty = (fy - j0).toFloat()
        fun yAt(i: Int, j: Int): Float = p.vertices.get((j * gridNx + i) * 5 + 1)
        val a = yAt(i0, j0) * (1 - tx) + yAt(i0 + 1, j0) * tx
        val b = yAt(i0, j0 + 1) * (1 - tx) + yAt(i0 + 1, j0 + 1) * tx
        return a * (1 - ty) + b * ty - gridLift
    }

    fun mskOf(lx: Float, lz: Float): DoubleArray = doubleArrayOf(lx + originX, originY - lz)

    companion object {
        fun load(file: File, maxTexture: Int): TopoPackage {
            ZipFile(file).use { zip ->
                fun bytes(name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                val meta = JSONObject(String(bytes("scene.json") ?: error("В пакете нет scene.json"), Charsets.UTF_8))
                val origin = meta.getJSONObject("origin")
                val b = meta.getJSONObject("bounds")
                val grid = meta.optJSONObject("grid")

                val prevBytes = bytes(meta.getJSONObject("preview").getString("file")) ?: error("Нет превью плана")
                val preview = BitmapFactory.decodeByteArray(prevBytes, 0, prevBytes.size) ?: error("Превью не читается")

                val tex = meta.optJSONObject("texture")?.let { t -> bytes(t.getString("file")) }?.let { decodeLimited(it, maxTexture) }

                var plan: PlanMesh? = null
                val layers = ArrayList<LayerData>()
                val la = meta.getJSONArray("layers")
                for (i in 0 until la.length()) {
                    val l = la.getJSONObject(i)
                    val meshName = l.optString("mesh", "")
                    if (meshName.isEmpty()) continue
                    val data = bytes(meshName) ?: continue
                    if (l.getString("id") == "plan") {
                        plan = readPlan(data)
                    } else {
                        layers.add(LayerData(l.getString("id"), l.optString("title", l.getString("id")),
                            l.optBoolean("underground", false), l.optBoolean("visible", true), readMesh(data)))
                    }
                }

                val cps = ArrayList<ControlPoint>()
                val ca = meta.optJSONArray("control_points")
                if (ca != null) for (i in 0 until ca.length()) {
                    val c = ca.getJSONObject(i)
                    val loc = c.getJSONArray("local")
                    val px = c.getJSONArray("px")
                    cps.add(ControlPoint(c.getInt("id"), c.optString("name", "точка"), c.optString("type", ""),
                        c.getDouble("x"), c.getDouble("y"), c.getDouble("h"),
                        floatArrayOf(loc.getDouble(0).toFloat(), loc.getDouble(1).toFloat(), loc.getDouble(2).toFloat()),
                        px.getDouble(0).toFloat(), px.getDouble(1).toFloat()))
                }
                val st = meta.optJSONObject("stats")
                val stats = StringBuilder()
                if (st != null) {
                    val keys = st.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        stats.append(k).append(": ").append(st.get(k)).append('\n')
                    }
                }
                return TopoPackage(
                    meta.optString("source", file.name),
                    origin.getDouble("x"), origin.getDouble("y"), origin.getDouble("z"),
                    b.getDouble("minx"), b.getDouble("miny"), b.getDouble("maxx"), b.getDouble("maxy"),
                    grid?.optInt("nx", 0) ?: 0, grid?.optInt("ny", 0) ?: 0, (grid?.optDouble("lift", 0.03) ?: 0.03).toFloat(),
                    preview, tex, plan, layers, cps, stats.toString()
                )
            }
        }

        private fun decodeLimited(data: ByteArray, maxSize: Int): Bitmap? {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
            var sample = 1
            while (o.outWidth / sample > maxSize || o.outHeight / sample > maxSize) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888
            return BitmapFactory.decodeByteArray(data, 0, data.size, o2)
        }

        private fun le(data: ByteArray): ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        private fun readMesh(data: ByteArray): List<MeshPart> {
            val bb = le(data)
            val magic = ByteArray(4)
            bb.get(magic)
            require(String(magic, Charsets.US_ASCII) == "TAM1") { "Неверный формат слоя" }
            val n = bb.int
            val parts = ArrayList<MeshPart>(n)
            repeat(n) {
                val color = floatArrayOf(bb.float, bb.float, bb.float, bb.float)
                val nv = bb.int
                val floats = nv * 6
                val fb = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val slice = bb.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                slice.limit(floats)
                fb.put(slice)
                fb.position(0)
                bb.position(bb.position() + floats * 4)
                parts.add(MeshPart(color, nv, fb))
            }
            return parts
        }

        private fun readPlan(data: ByteArray): PlanMesh {
            val bb = le(data)
            val magic = ByteArray(4)
            bb.get(magic)
            require(String(magic, Charsets.US_ASCII) == "TAP1") { "Неверный формат подложки" }
            val nv = bb.int
            val ni = bb.int
            val vb = ByteBuffer.allocateDirect(nv * 5 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val vs = bb.slice().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            vs.limit(nv * 5)
            vb.put(vs)
            vb.position(0)
            bb.position(bb.position() + nv * 5 * 4)
            val ib = ByteBuffer.allocateDirect(ni * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            val isrc = bb.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            isrc.limit(ni)
            ib.put(isrc)
            ib.position(0)
            return PlanMesh(nv, ni, vb, ib)
        }

        fun copyToCache(input: InputStream, dst: File) {
            dst.parentFile?.mkdirs()
            dst.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
    }
}
