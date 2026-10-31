package ru.topoar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MathTest {
    @Test
    fun alignByTwoPoints() {
        val th = 1.1; val t = doubleArrayOf(4.0, -1.3, -7.0)
        fun tr(q: FloatArray) = floatArrayOf((cos(th) * q[0] + sin(th) * q[2] + t[0]).toFloat(), (q[1] + t[1]).toFloat(),
            (-sin(th) * q[0] + cos(th) * q[2] + t[2]).toFloat())
        val a = Aligner()
        listOf(floatArrayOf(10f, 2f, -30f), floatArrayOf(60f, 3f, -35f)).forEachIndexed { i, q -> a.addPair(Aligner.Obs("p$i", q, tr(q), "aim")) }
        assertEquals(th, a.theta, 1e-4)
        assertEquals(t[0], a.tx, 1e-3)
    }

    @Test
    fun chunksKeepAllTriangles() {
        val tris = 5000; val arr = FloatArray(tris * 18)
        val r = java.util.Random(1)
        for (k in 0 until tris) { val x = r.nextFloat() * 500; for (v in 0 until 3) arr[k * 18 + v * 6] = x }
        val ch = TopoPackage.splitChunks(arr, tris * 3)
        assertEquals(tris * 3, ch.sumOf { it.vertexCount })
        assertTrue(ch.size > 1)
    }
}
