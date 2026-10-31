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
import kotlin.math.max
import kotlin.math.min

/** Опорная точка с плана: угол здания / опорная марка, съёмочная точка, колодец, опора. */
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

/** Облако точек: n записей по 16 байт (x,y,z float32 + r,g,b,a uint8). */
class PointCloud(val count: Int, val data: ByteBuffer, val pointSize: Float)

class LayerData(
    val id: String,
    val title: String,
    val underground: Boolean,
    val defaultVisible: Boolean,
    val parts: List<MeshPart>,                 // TAM1 (пусто, если есть текстура или точки)
    val texMesh: PlanMesh? = null,             // tex_mesh (TAP1) — модель с настоящей текстурой
    val texture: Bitmap? = null,               // tex_file
    val points: PointCloud? = null             // points (.pts)
) {
    /** «Земля» — полупрозрачна в режиме «Подземка». */
    val isGround get() = id == "model" || id == "pg_model"
}

class PlanMesh(val vertexCount: Int, val indexCount: Int, val vertices: FloatBuffer, val indices: IntBuffer)

/** Плитка подложки: область (u0,t0)–(u1,t1) текстуры плана, t = 0 — северный край. */
class Tile(val row: Int, val col: Int, val file: String, val u0: Float, val t0: Float, val u1: Float, val t1: Float) {
    var indexOffset = 0          // в общем буфере индексов плитки
    var indexCount = 0
    val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
}

class TileSet(val nx: Int, val ny: Int, val tiles: List<Tile>, val indices: IntBuffer, val indexCount: Int)

/** Распакованный пакет .topoar (из mooncloud / topo3d). */
class TopoPackage(
    val file: File,
    val name: String,
    val kind: String,
    val originX: Double, val originY: Double, val originZ: Double,
    val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
    val gridNx: Int, val gridNy: Int, val gridLift: Float,
    val preview: Bitmap,
    val texture: Bitmap?,
    val plan: PlanMesh?,
    val tiles: TileSet?,
    val layers: List<LayerData>,
    val controlPoints: List<ControlPoint>,
    val stats: String,
    /** Рамка «Показать всё» (локальные x,y,z): 4…96 % вершин. */
    val fitBox: FloatArray
) {
    val width get() = maxX - minX
    val height get() = maxY - minY

    fun previewToLocal(px: Float, py: Float): FloatArray {
        val e = minX + px / preview.width * width
        val n = maxY - py / preview.height * height
        return floatArrayOf((e - originX).toFloat(), (-(n - originY)).toFloat())
    }

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

    /** Полноразмерная плитка (декод в фоне). [maxSize] — ограничение по длинной стороне. */
    fun decodeTile(t: Tile, maxSize: Int): Bitmap? = try {
        ZipFile(file).use { z -> z.getEntry(t.file)?.let { e -> z.getInputStream(e).use { decodeLimited(it.readBytes(), maxSize) } } }
    } catch (e: Throwable) { null }

    companion object {
        /** [maxTexture] — предел подложки/текстур моделей; [progress] — строка для экрана загрузки. */
        fun load(file: File, maxTexture: Int, progress: (String) -> Unit = {}): TopoPackage {
            ZipFile(file).use { zip ->
                fun bytes(name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                val meta = JSONObject(String(bytes("scene.json") ?: error("В пакете нет scene.json"), Charsets.UTF_8))
                val origin = meta.getJSONObject("origin")
                val b = meta.getJSONObject("bounds")
                val grid = meta.optJSONObject("grid")

                val prevBytes = bytes(meta.getJSONObject("preview").getString("file")) ?: error("Нет превью плана")
                val preview = BitmapFactory.decodeByteArray(prevBytes, 0, prevBytes.size) ?: error("Превью не читается")

                progress("подложка")
                val tex = meta.optJSONObject("texture")?.let { t -> bytes(t.getString("file")) }?.let { decodeLimited(it, maxTexture) }

                var plan: PlanMesh? = null
                val layers = ArrayList<LayerData>()
                val la = meta.getJSONArray("layers")
                for (i in 0 until la.length()) {
                    val l = la.getJSONObject(i)
                    val id = l.getString("id")
                    val title = l.optString("title", id)
                    progress(title)
                    if (id == "plan") {
                        val m = l.optString("mesh", "")
                        if (m.isNotEmpty()) bytes(m)?.let { plan = readPlan(it) }
                        continue
                    }
                    val under = l.optBoolean("underground", false)
                    val vis = l.optBoolean("visible", true)
                    // 1) модель с текстурой — TAM1 тогда не грузим
                    val texMeshName = l.optString("tex_mesh", "")
                    val texFile = l.optString("tex_file", "")
                    if (texMeshName.isNotEmpty() && texFile.isNotEmpty()) {
                        val tm = bytes(texMeshName)?.let { runCatching { readPlan(it) }.getOrNull() }
                        val tb = bytes(texFile)?.let { decodeLimited(it, min(maxTexture, 4096)) }
                        if (tm != null && tb != null) {
                            layers.add(LayerData(id, title, under, vis, emptyList(), texMesh = tm, texture = tb))
                            continue
                        }
                    }
                    // 2) облако точек — TAM1 тогда не грузим
                    val ptsName = l.optString("points", "")
                    if (ptsName.isNotEmpty()) {
                        val pc = bytes(ptsName)?.let { runCatching { readPoints(it, l.optDouble("point_size", 0.03).toFloat()) }.getOrNull() }
                        if (pc != null) {
                            layers.add(LayerData(id, title, under, vis, emptyList(), points = pc))
                            continue
                        }
                    }
                    // 3) обычный слой TAM1
                    val meshName = l.optString("mesh", "")
                    if (meshName.isEmpty()) continue
                    val data = bytes(meshName) ?: continue
                    layers.add(LayerData(id, title, under, vis, readMesh(data)))
                }

                // плитки подложки
                var tileSet: TileSet? = null
                val tt = meta.optJSONObject("texture_tiles")
                val pl = plan
                if (tt != null && pl != null) {
                    val nx = tt.optInt("nx", 0); val ny = tt.optInt("ny", 0)
                    val files = tt.optJSONArray("files")
                    if (nx > 0 && ny > 0 && files != null && files.length() >= nx * ny) {
                        val list = ArrayList<Tile>()
                        for (r in 0 until ny) for (c in 0 until nx) {
                            val f = files.getString(r * nx + c)
                            if (zip.getEntry(f) == null) continue
                            list.add(Tile(r, c, f, c.toFloat() / nx, r.toFloat() / ny, (c + 1).toFloat() / nx, (r + 1).toFloat() / ny))
                        }
                        if (list.isNotEmpty()) tileSet = partitionTiles(pl, nx, ny, list)
                    }
                }

                val cps = ArrayList<ControlPoint>()
                val ca = meta.optJSONArray("control_points")
                if (ca != null) for (i in 0 until ca.length()) {
                    val c = ca.getJSONObject(i)
                    val loc = c.getJSONArray("local")
                    val px = c.getJSONArray("px")
                    cps.add(ControlPoint(c.optInt("id", i + 1), c.optString("name", "точка"), c.optString("type", ""),
                        c.optDouble("x", 0.0), c.optDouble("y", 0.0), c.optDouble("h", 0.0),
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
                    file,
                    meta.optString("source", file.name),
                    meta.optString("kind", "topoplan"),
                    origin.getDouble("x"), origin.getDouble("y"), origin.getDouble("z"),
                    b.getDouble("minx"), b.getDouble("miny"), b.getDouble("maxx"), b.getDouble("maxy"),
                    grid?.optInt("nx", 0) ?: 0, grid?.optInt("ny", 0) ?: 0, (grid?.optDouble("lift", 0.03) ?: 0.03).toFloat(),
                    preview, tex, plan, tileSet, layers, cps, stats.toString(),
                    fitBox(plan, layers)
                )
            }
        }

        fun decodeLimited(data: ByteArray, maxSize: Int): Bitmap? {
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

        private fun magic(bb: ByteBuffer): String {
            val m = ByteArray(4)
            bb.get(m)
            return String(m, Charsets.US_ASCII)
        }

        fun readMesh(data: ByteArray): List<MeshPart> {
            val bb = le(data)
            require(magic(bb) == "TAM1") { "Неверный формат слоя" }
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

        /** TAP1 — и подложка (plan.mesh), и текстурированные модели (*.tap). */
        fun readPlan(data: ByteArray): PlanMesh {
            val bb = le(data)
            require(magic(bb) == "TAP1") { "Неверный формат сетки" }
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

        /** TAPT: n записей по 16 байт. Данные копируются как есть — x,y,z float32 LE и rgba. */
        fun readPoints(data: ByteArray, pointSize: Float): PointCloud {
            val bb = le(data)
            require(magic(bb) == "TAPT") { "Неверный формат облака" }
            val n = bb.int
            require(n >= 0 && data.size >= 8 + n * 16) { "Облако обрезано" }
            val out = ByteBuffer.allocateDirect(n * 16).order(ByteOrder.nativeOrder())
            if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                out.put(data, 8, n * 16)
            } else {
                for (i in 0 until n) {
                    val o = 8 + i * 16
                    out.putFloat(bb.getFloat(o)); out.putFloat(bb.getFloat(o + 4)); out.putFloat(bb.getFloat(o + 8))
                    out.put(data, o + 12, 4)
                }
            }
            out.position(0)
            return PointCloud(n, out, max(0.002f, pointSize))
        }

        /**
         * Делит треугольники подложки по плиткам: у каждой плитки свой кусок индексов
         * (треугольник на стыке попадает в обе — лишнее отрежет discard в шейдере)
         * и рамка в локальных координатах — для выбора детализации.
         */
        fun partitionTiles(plan: PlanMesh, nx: Int, ny: Int, tiles: List<Tile>): TileSet {
            val byRc = HashMap<Int, Tile>()
            tiles.forEach { byRc[it.row * nx + it.col] = it }
            val v = plan.vertices
            val idx = plan.indices
            val lists = HashMap<Tile, IntArrayList>()
            tiles.forEach { lists[it] = IntArrayList() }
            val triCount = plan.indexCount / 3
            for (t in 0 until triCount) {
                val a = idx.get(t * 3); val b = idx.get(t * 3 + 1); val c = idx.get(t * 3 + 2)
                var umin = Float.MAX_VALUE; var umax = -Float.MAX_VALUE
                var tmin = Float.MAX_VALUE; var tmax = -Float.MAX_VALUE
                for (k in intArrayOf(a, b, c)) {
                    val u = v.get(k * 5 + 3); val tt = v.get(k * 5 + 4)
                    if (u < umin) umin = u; if (u > umax) umax = u
                    if (tt < tmin) tmin = tt; if (tt > tmax) tmax = tt
                }
                val c0 = (umin * nx).toInt().coerceIn(0, nx - 1); val c1 = (umax * nx).toInt().coerceIn(0, nx - 1)
                val r0 = (tmin * ny).toInt().coerceIn(0, ny - 1); val r1 = (tmax * ny).toInt().coerceIn(0, ny - 1)
                for (r in r0..r1) for (cc in c0..c1) {
                    val tile = byRc[r * nx + cc] ?: continue
                    val l = lists[tile]!!
                    l.add(a); l.add(b); l.add(c)
                    for (k in intArrayOf(a, b, c)) {
                        val x = v.get(k * 5); val y = v.get(k * 5 + 1); val z = v.get(k * 5 + 2)
                        val bx = tile.box
                        if (x < bx[0]) bx[0] = x; if (y < bx[1]) bx[1] = y; if (z < bx[2]) bx[2] = z
                        if (x > bx[3]) bx[3] = x; if (y > bx[4]) bx[4] = y; if (z > bx[5]) bx[5] = z
                    }
                }
            }
            val total = lists.values.sumOf { it.size }
            val ib = ByteBuffer.allocateDirect(max(1, total) * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            var off = 0
            for (tile in tiles) {
                val l = lists[tile]!!
                tile.indexOffset = off
                tile.indexCount = l.size
                ib.put(l.data, 0, l.size)
                off += l.size
            }
            ib.position(0)
            return TileSet(nx, ny, tiles.filter { it.indexCount > 0 }, ib, total)
        }

        /** Рамка «Показать всё»: 4…96 % вершин всех слоёв и подложки + 8 % запаса. */
        fun fitBox(plan: PlanMesh?, layers: List<LayerData>): FloatArray {
            val xs = FloatSamples(); val ys = FloatSamples(); val zs = FloatSamples()
            fun add(x: Float, y: Float, z: Float) { xs.add(x); ys.add(y); zs.add(z) }
            plan?.let { p -> val st = max(1, p.vertexCount / 20000); var i = 0
                while (i < p.vertexCount) { add(p.vertices.get(i * 5), p.vertices.get(i * 5 + 1), p.vertices.get(i * 5 + 2)); i += st } }
            for (l in layers) {
                for (part in l.parts) { val st = max(1, part.vertexCount / 4000); var i = 0
                    while (i < part.vertexCount) { add(part.data.get(i * 6), part.data.get(i * 6 + 1), part.data.get(i * 6 + 2)); i += st } }
                l.texMesh?.let { p -> val st = max(1, p.vertexCount / 8000); var i = 0
                    while (i < p.vertexCount) { add(p.vertices.get(i * 5), p.vertices.get(i * 5 + 1), p.vertices.get(i * 5 + 2)); i += st } }
                l.points?.let { pc -> val st = max(1, pc.count / 8000); var i = 0
                    while (i < pc.count) { add(pc.data.getFloat(i * 16), pc.data.getFloat(i * 16 + 4), pc.data.getFloat(i * 16 + 8)); i += st } }
            }
            if (xs.size == 0) return floatArrayOf(-50f, -5f, -50f, 50f, 5f, 50f)
            val x = xs.range(); val y = ys.range(); val z = zs.range()
            fun pad(r: FloatArray): FloatArray { val d = max(0.5f, (r[1] - r[0]) * 0.08f); return floatArrayOf(r[0] - d, r[1] + d) }
            val px = pad(x); val py = pad(y); val pz = pad(z)
            return floatArrayOf(px[0], py[0], pz[0], px[1], py[1], pz[1])
        }

        fun copyToCache(input: InputStream, dst: File) {
            dst.parentFile?.mkdirs()
            dst.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
        }
    }
}

/** Растущий массив int без упаковки. */
class IntArrayList {
    var data = IntArray(1024); private set
    var size = 0; private set
    fun add(v: Int) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
}

/** Выборка для перцентилей 4…96 %. */
class FloatSamples {
    private var a = FloatArray(4096)
    var size = 0; private set
    fun add(v: Float) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
    fun range(): FloatArray {
        val s = a.copyOf(size)
        s.sort()
        val lo = s[((size - 1) * 0.04).toInt()]
        val hi = s[((size - 1) * 0.96).toInt()]
        return floatArrayOf(lo, hi)
    }
}
