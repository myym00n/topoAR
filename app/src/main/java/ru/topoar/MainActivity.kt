package ru.topoar

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : Activity(), MapView.Listener {
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var status: TextView
    private lateinit var mapBox: FrameLayout
    private lateinit var map: MapView
    private lateinit var compass: Compass
    private val ui = Handler(Looper.getMainLooper())

    private var session: Session? = null
    private var installRequested = false

    private val fieldAl = Aligner()          // привязка на местности
    private val table = Aligner()          // «макет на столе»
    private var tableMode = false
    private var tableRot = 0.0
    private var pkg: TopoPackage? = null
    private var selected: ControlPoint? = null
    private var hint = ""

    private lateinit var bMap: Button
    private lateinit var bAdjust: Button
    private lateinit var bUnder: Button
    private lateinit var bTable: Button
    private lateinit var bTake: Button
    private lateinit var adjustRow: LinearLayout

    private val cacheFile by lazy { File(filesDir, "current.topoar") }

    // ─────────────────────────── жизненный цикл ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        compass = Compass(this)
        renderer = ArRenderer { msg -> ui.post { toast(msg) } }
        renderer.aligner = fieldAl
        buildUi()
        handleIntent(intent)
        if (pkg == null && cacheFile.exists()) loadPackage(cacheFile, "последний пакет")
        ui.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
                    else -> {}
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                    return
                }
                val s = Session(this)
                val c = Config(s)
                c.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                c.focusMode = Config.FocusMode.AUTO
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) c.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(c)
                session = s
            } catch (e: Exception) {
                status.text = "AR недоступен: ${e.javaClass.simpleName}\n${e.message ?: ""}\n" +
                    "Нужен телефон с поддержкой ARCore («Сервисы Google Play для AR»)."
                return
            }
        }
        try {
            session?.resume()
        } catch (e: Exception) {
            status.text = "Камера недоступна: ${e.message}"
            session = null
            return
        }
        @Suppress("DEPRECATION")
        renderer.displayRotation = windowManager.defaultDisplay.rotation
        renderer.session = session
        glView.onResume()
        compass.start()
    }

    override fun onPause() {
        super.onPause()
        compass.stop()
        glView.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        session?.close()
        session = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            status.text = "Без камеры AR не работает. Разрешите доступ к камере в настройках."
        }
    }

    // ─────────────────────────── интерфейс ───────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, accent: Boolean = false, onClick: (Button) -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 14f
        b.setTextColor(if (accent) Color.rgb(26, 13, 4) else Color.WHITE)
        b.background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (accent) Color.rgb(255, 123, 51) else Color.argb(200, 24, 30, 26))
            setStroke(dp(1), Color.argb(90, 255, 255, 255))
        }
        b.setPadding(dp(12), 0, dp(12), 0)
        b.minHeight = dp(44)
        b.minimumHeight = dp(44)
        b.setOnClickListener { onClick(b) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
        b.layoutParams = lp
        return b
    }

    private fun row(vararg views: View): HorizontalScrollView {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        views.forEach { l.addView(it) }
        val sv = HorizontalScrollView(this)
        sv.isHorizontalScrollBarEnabled = false
        sv.addView(l)
        return sv
    }

    private fun setActive(b: Button, on: Boolean) {
        (b.background as? GradientDrawable)?.setStroke(dp(if (on) 3 else 1), if (on) Color.rgb(255, 179, 122) else Color.argb(90, 255, 255, 255))
    }

    private fun buildUi() {
        val root = FrameLayout(this)

        glView = GLSurfaceView(this)
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))

        root.addView(Crosshair(this), FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))

        // верх: статус + карта
        val top = LinearLayout(this)
        top.orientation = LinearLayout.VERTICAL
        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 13.5f
        status.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        status.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        status.setPadding(dp(14), dp(30), dp(14), dp(8))
        status.setBackgroundColor(Color.argb(120, 0, 0, 0))
        top.addView(status, LinearLayout.LayoutParams(-1, -2))

        mapBox = FrameLayout(this)
        map = MapView(this)
        map.listener = this
        mapBox.addView(map, FrameLayout.LayoutParams(-1, -1))
        adjustRow = LinearLayout(this)
        adjustRow.orientation = LinearLayout.HORIZONTAL
        adjustRow.addView(btn("⟲ 1°") { rotate(Math.toRadians(1.0)) })
        adjustRow.addView(btn("⟳ 1°") { rotate(Math.toRadians(-1.0)) })
        adjustRow.addView(btn("▲ 5 см") { fieldAl.lift(0.05) })
        adjustRow.addView(btn("▼ 5 см") { fieldAl.lift(-0.05) })
        adjustRow.visibility = View.GONE
        mapBox.addView(adjustRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        mapBox.visibility = View.GONE
        val h = resources.displayMetrics.heightPixels
        top.addView(mapBox, LinearLayout.LayoutParams(-1, (h * 0.42).toInt()))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // низ: кнопки
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(dp(4), dp(6), dp(4), dp(16))
        bottom.setBackgroundColor(Color.argb(110, 0, 0, 0))

        bMap = btn("Карта") { toggleMap() }
        bTake = btn("Взять точку", accent = true) { takePoint() }
        val bStand = btn("Стою на точке") { standOnSelected() }
        val bSight = btn("Визир") { sight() }
        val bUndo = btn("Отменить") { undo() }
        val bReset = btn("Сброс") { confirmReset() }
        bottom.addView(row(bMap, bTake, bStand, bSight, bUndo, bReset))

        bAdjust = btn("Подвинуть") { toggleAdjust() }
        bUnder = btn("Подземка") { toggleUnder() }
        val bLayers = btn("Слои") { layersDialog() }
        bTable = btn("Макет") { toggleTable() }
        val bOpen = btn("Открыть") { pickFile() }
        val bInfo = btn("Инфо") { infoDialog() }
        bottom.addView(row(bAdjust, bUnder, bLayers, bTable, bOpen, bInfo))
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
    }

    /** Крестик по центру экрана. */
    private class Crosshair(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 4f }
        private val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0); strokeWidth = 8f }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = width / 2f
            for (pp in listOf(sh, p)) {
                c.drawLine(cx - r, cy, cx - r * 0.25f, cy, pp); c.drawLine(cx + r * 0.25f, cy, cx + r, cy, pp)
                c.drawLine(cx, cy - r, cx, cy - r * 0.25f, pp); c.drawLine(cx, cy + r * 0.25f, cx, cy + r, pp)
            }
        }
    }

    // ─────────────────────────── статус и карта ───────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 250)
        }
    }

    private fun f2(v: Float) = String.format(Locale.US, "%.2f", v)

    private fun updateStatus() {
        val fs = renderer.frameState
        val sb = StringBuilder()
        val p = pkg
        if (p == null) sb.append("Откройте пакет .topoar (кнопка «Открыть»)\n")
        else sb.append(p.name).append('\n')
        if (!fs.tracking) {
            sb.append(fs.message)
        } else if (tableMode) {
            sb.append(if (fs.hit == null) "Наведите крестик на стол" else "Нажмите «Поставить» — макет встанет в крестик")
        } else {
            val n = fieldAl.count()
            if (fieldAl.aligned) {
                sb.append("Привязка ").append(fieldAl.source)
                val res = fieldAl.residuals()
                if (res.isNotEmpty()) {
                    sb.append("\nневязки: ")
                    res.forEachIndexed { i, r -> sb.append("т").append(i + 1).append(" ").append(f2(r[0])).append(" м  ") }
                    val rms = fieldAl.rms()
                    if (rms >= 0) sb.append("\nСКО ").append(String.format(Locale.US, "%.2f", rms)).append(" м")
                }
                if (n == 1) sb.append("\nУточните: «Визир» на дальнюю точку или вторая точка")
            } else if (n == 0) {
                sb.append("Привязка: выберите точку на «Карте», наведите на неё крестик → «Взять точку».\n")
                sb.append("Или встаньте на точку → «Стою на точке». Долгий тап по карте = «я стою здесь».")
            } else {
                sb.append("1 точка есть. Нужна вторая точка или «Визир» на дальнюю точку.")
            }
            selected?.let { sb.append("\nВыбрано: ").append(it.name) }
            fs.hit?.let { sb.append("\nдо точки под крестиком: ").append(f2(fs.hitDist)).append(" м") }
        }
        if (hint.isNotEmpty()) sb.append('\n').append(hint)
        status.text = sb.toString()

        // мини-карта: где я и куда смотрю
        if (mapBox.visibility == View.VISIBLE) {
            if (fs.tracking && fieldAl.aligned && !tableMode) {
                val q = fieldAl.toPlan(fs.camPos)
                map.device = floatArrayOf(q[0], q[2])
                map.heading = fieldAl.dirToPlan(fs.camFwd[0], fs.camFwd[1])
            } else {
                map.device = null
                map.heading = null
            }
            map.usedIds = fieldAl.snapshot().mapNotNull { o -> pkg?.controlPoints?.firstOrNull { it.name == o.name && hypot((it.local[0] - o.q[0]).toDouble(), (it.local[2] - o.q[2]).toDouble()) < 0.01 }?.id }.toSet()
            map.invalidate()
        }
        bTake.text = if (tableMode) "Поставить" else "Взять точку"
    }

    private fun toggleMap() {
        val show = mapBox.visibility != View.VISIBLE
        mapBox.visibility = if (show) View.VISIBLE else View.GONE
        setActive(bMap, show)
    }

    private fun toggleAdjust() {
        if (!fieldAl.aligned) { toast("Сначала привяжите план хотя бы по одной точке"); return }
        map.adjustMode = !map.adjustMode
        adjustRow.visibility = if (map.adjustMode) View.VISIBLE else View.GONE
        setActive(bAdjust, map.adjustMode)
        if (map.adjustMode && mapBox.visibility != View.VISIBLE) toggleMap()
        if (map.adjustMode) {
            val fs = renderer.frameState
            if (fs.tracking) { val q = fieldAl.toPlan(fs.camPos); map.centerOn(q[0], q[2]) }
        }
    }

    private fun toggleUnder() {
        renderer.underground = !renderer.underground
        setActive(bUnder, renderer.underground)
        hint = if (renderer.underground) "Подземка: план полупрозрачный, видны сети и колодцы" else ""
    }

    // ─────────────────────────── привязка ───────────────────────────

    override fun onPick(cp: ControlPoint) {
        selected = cp
        map.selected = cp
        renderer.selectedPoint = cp.local
        hint = ""
    }

    /** Долгий тап по карте: «я стою здесь». */
    override fun onStandHere(lx: Float, lz: Float, cp: ControlPoint?) {
        val p = pkg ?: return
        val q = if (cp != null) cp.local else floatArrayOf(lx, p.groundAt(lx, lz), lz)
        if (cp != null) onPick(cp)
        addStandPair(q, cp?.name ?: "точка на карте")
    }

    override fun onShiftDevice(dxLocal: Float, dzLocal: Float) {
        val fs = renderer.frameState
        if (!fs.tracking || !fieldAl.aligned) return
        val q = fieldAl.toPlan(fs.camPos)
        fieldAl.moveDeviceTo(fs.camPos, q[0] + dxLocal, q[2] + dzLocal)
    }

    override fun onRotate(deltaRad: Double) = rotate(deltaRad)

    private fun rotate(d: Double) {
        val fs = renderer.frameState
        if (!fs.tracking) return
        if (tableMode) {
            tableRot += d
            return
        }
        fieldAl.rotateAbout(fs.camPos, d)
    }

    private fun takePoint() {
        val fs = renderer.frameState
        if (!fs.tracking) { toast("Нет трекинга"); return }
        val hit = fs.hit ?: run { toast("Крестик не попал на поверхность — поводите телефоном"); return }
        if (tableMode) { placeTable(hit); return }
        val cp = selected ?: run { toast("Сначала выберите точку на карте"); if (mapBox.visibility != View.VISIBLE) toggleMap(); return }
        fieldAl.addPair(Aligner.Obs(cp.name, cp.local.copyOf(), hit.copyOf(), "aim"))
        afterPair(fs)
    }

    private fun standOnSelected() {
        val cp = selected ?: run { toast("Выберите на карте точку, на которой стоите (или долгий тап по карте)"); if (mapBox.visibility != View.VISIBLE) toggleMap(); return }
        addStandPair(cp.local.copyOf(), cp.name)
    }

    private fun addStandPair(q: FloatArray, name: String) {
        if (tableMode) { toast("Выйдите из режима «Макет»"); return }
        val fs = renderer.frameState
        if (!fs.tracking) { toast("Нет трекинга"); return }
        val cam = fs.camPos
        // высота земли: точка под крестиком, если она рядом (смотрим под ноги), иначе рост ~1,35 м
        val hit = fs.hit
        val groundY = if (hit != null && hypot((hit[0] - cam[0]).toDouble(), (hit[2] - cam[2]).toDouble()) < 1.5) hit[1] else cam[1] - 1.35f
        fieldAl.addPair(Aligner.Obs(name, q, floatArrayOf(cam[0], groundY, cam[2]), "stand"))
        afterPair(fs)
        toast("Стою: $name")
    }

    private fun afterPair(fs: FrameState) {
        // первая точка: прикидываем направление по компасу, чтобы модель сразу появилась
        if (fieldAl.count() == 1 && !fieldAl.hasBearing() && compass.reliable) {
            fieldAl.setCompass(compass.azimuth, fs.camFwd[0], fs.camFwd[1])
            hint = "Направление пока по компасу (грубо, ±10°)"
        } else hint = ""
        selected = null
        map.selected = null
        renderer.selectedPoint = null
    }

    private fun sight() {
        if (tableMode) return
        val fs = renderer.frameState
        if (!fs.tracking) { toast("Нет трекинга"); return }
        val target = selected ?: run { toast("Выберите на карте дальнюю точку и наведите на неё центр экрана"); if (mapBox.visibility != View.VISIBLE) toggleMap(); return }
        // станция — ближайшая взятая точка, рядом с которой стоим
        val st = fieldAl.snapshot().minByOrNull { hypot((it.p[0] - fs.camPos[0]).toDouble(), (it.p[2] - fs.camPos[2]).toDouble()) }
        if (st == null || hypot((st.p[0] - fs.camPos[0]).toDouble(), (st.p[2] - fs.camPos[2]).toDouble()) > 2.5) {
            toast("Визир: сначала встаньте на точку («Стою на точке»), потом наводите на дальнюю"); return
        }
        fieldAl.setSight(st.q, target.local, fs.camFwd[0], fs.camFwd[1])
        hint = "Направление по визиру на «${target.name}»"
        renderer.selectedPoint = target.local
    }

    private fun undo() {
        if (tableMode) { table.reset(); return }
        fieldAl.undo()
        hint = ""
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage("Сбросить привязку?")
            .setPositiveButton("Сбросить") { _, _ -> fieldAl.reset(); hint = "" }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─────────────────────────── макет на столе ───────────────────────────

    private fun toggleTable() {
        tableMode = !tableMode
        setActive(bTable, tableMode)
        renderer.aligner = if (tableMode) table else fieldAl
        if (tableMode) {
            table.reset()
            hint = "Макет: наведите крестик на стол → «Поставить». Повторное «Поставить» поворачивает на 15°"
        } else hint = ""
    }

    private fun placeTable(at: FloatArray) {
        val p = pkg ?: return
        val cx = ((p.minX + p.maxX) / 2 - p.originX).toFloat()
        val cz = (-((p.minY + p.maxY) / 2 - p.originY)).toFloat()
        val center = floatArrayOf(cx, p.groundAt(cx, cz), cz)
        val s = 0.8 / max(p.width, p.height)
        tableRot += Math.toRadians(15.0) * if (table.aligned) 1 else 0
        table.placeTable(at, center, s, tableRot)
    }

    // ─────────────────────────── слои, инфо ───────────────────────────

    private fun layersDialog() {
        val p = pkg ?: run { toast("Пакет не открыт"); return }
        val items = ArrayList<String>()
        val ids = ArrayList<String>()
        items.add("Топоплан (подложка)"); ids.add("plan")
        p.layers.forEach { items.add(it.title + if (it.underground) "  · подземка" else ""); ids.add(it.id) }
        val checked = BooleanArray(items.size) { i ->
            if (ids[i] == "plan") renderer.showPlan else renderer.layerVisible[ids[i]] ?: true
        }
        AlertDialog.Builder(this)
            .setTitle("Слои")
            .setMultiChoiceItems(items.toTypedArray(), checked) { _, which, on ->
                if (ids[which] == "plan") renderer.showPlan = on else renderer.layerVisible[ids[which]] = on
            }
            .setPositiveButton("Готово", null)
            .show()
    }

    private fun infoDialog() {
        val p = pkg
        val msg = StringBuilder()
        if (p != null) {
            msg.append("Начало СК: X ").append(p.originX).append("  Y ").append(p.originY).append("  H ").append(p.originZ).append('\n')
            msg.append("Опорных точек: ").append(p.controlPoints.size).append("\n\n").append(p.stats)
            val fs = renderer.frameState
            if (fs.tracking && fieldAl.aligned) {
                val q = fieldAl.toPlan(fs.camPos)
                val msk = p.mskOf(q[0], q[2])
                msg.append("\nТелефон сейчас (по привязке):\nE ").append(String.format(Locale.US, "%.2f", msk[0]))
                    .append("  N ").append(String.format(Locale.US, "%.2f", msk[1]))
            }
        } else msg.append("Пакет не открыт")
        msg.append("\n\nКомпас: ").append(if (compass.reliable) String.format(Locale.US, "%.0f°", compass.azimuth) else "нет данных")
            .append(" (склонение ").append(String.format(Locale.US, "%.1f°", compass.declination)).append(")")
        AlertDialog.Builder(this).setTitle("ТопоAR").setMessage(msg.toString()).setPositiveButton("OK", null).show()
    }

    // ─────────────────────────── файлы ───────────────────────────

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        @Suppress("DEPRECATION")
        startActivityForResult(i, 42)
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == RESULT_OK) data?.data?.let { importUri(it) }
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        uri?.let { importUri(it) }
    }

    private fun importUri(uri: Uri) {
        status.text = "Копирую пакет…"
        thread {
            try {
                contentResolver.openInputStream(uri)?.use { TopoPackage.copyToCache(it, cacheFile) }
                    ?: throw IllegalStateException("файл не открывается")
                ui.post { loadPackage(cacheFile, uri.lastPathSegment ?: "пакет") }
            } catch (e: Exception) {
                ui.post { toast("Не удалось открыть: ${e.message}") }
            }
        }
    }

    private fun loadPackage(f: File, label: String) {
        status.text = "Загружаю $label…"
        thread {
            try {
                val p = TopoPackage.load(f, renderer.maxTexture)
                ui.post {
                    pkg = p
                    map.pkg = p
                    fieldAl.reset(); table.reset()
                    selected = null
                    renderer.selectedPoint = null
                    renderer.setPackage(p)
                    toast("Открыт: ${p.name}, слоёв ${p.layers.size}, опорных точек ${p.controlPoints.size}")
                }
            } catch (e: Throwable) {
                ui.post { toast("Пакет не читается: ${e.message}") }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
