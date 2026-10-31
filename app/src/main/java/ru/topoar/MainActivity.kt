package ru.topoar

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : Activity(), MapView.Listener {

    /** Шаги привязки на местности. */
    private enum class Step { STAND, SIGHT, DONE, REFINE }

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: ArRenderer
    private lateinit var compass: Compass
    private val ui = Handler(Looper.getMainLooper())

    // экраны и панели
    private lateinit var startScreen: View
    private lateinit var startPkg: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var mapBox: FrameLayout
    private lateinit var map: MapView
    private lateinit var crosshair: View
    private lateinit var bottom: LinearLayout
    private lateinit var stepText: TextView
    private lateinit var primary: Button
    private lateinit var actions: LinearLayout
    private lateinit var adjustRow: LinearLayout

    private var session: Session? = null
    private var installRequested = false
    private var pendingMode: Mode? = null

    private val fieldAl = Aligner()
    private val tableAl = Aligner()
    private var step = Step.STAND
    private var pkg: TopoPackage? = null
    private var selected: ControlPoint? = null
    private var customPoint: FloatArray? = null       // точка «я тут», выбранная долгим нажатием
    private var target: ControlPoint? = null          // точка для визира
    private var bigMap = true

    private var tablePlaced = false
    private var tableAt = FloatArray(3)
    private var tableScale = 1.0
    private var tableRot = 0.0
    private var tableBaseScale = 1.0

    private val cacheFile by lazy { File(filesDir, "current.topoar") }

    // ─────────────────────────── жизненный цикл ───────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        compass = Compass(this)
        // память под чёткие плитки подложки: на слабых телефонах — 2048 px и меньше плиток
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        if (am.isLowRamDevice || am.largeMemoryClass < 384) {
            renderer.fullTileMax = 2048; renderer.maxFullTiles = 6
        } else {
            renderer.fullTileMax = 4096; renderer.maxFullTiles = if (am.largeMemoryClass >= 768) 8 else 6
        }
        renderer = ArRenderer { msg -> ui.post { toast(msg) } }
        buildUi()
        handleIntent(intent)
        if (pkg == null && cacheFile.exists()) loadPackage(cacheFile, "последний пакет")
        showStart()
        ui.post(tick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        @Suppress("DEPRECATION")
        renderer.displayRotation = windowManager.defaultDisplay.rotation
        val pm = pendingMode
        if (pm != null) { pendingMode = null; enterMode(pm) }
        else if (renderer.mode == Mode.TABLE || renderer.mode == Mode.FIELD) {
            if (!startAr()) enterMode(Mode.START)
        }
        glView.onResume()
    }

    override fun onPause() {
        super.onPause()
        stopAr()
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        session?.close()
        session = null
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (renderer.mode != Mode.START) enterMode(Mode.START)
        else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // режим включится в onResume через pendingMode
        } else {
            pendingMode = null
            toast("Без доступа к камере AR не работает. 3D-просмотр доступен.")
        }
    }

    // ─────────────────────────── ARCore ───────────────────────────

    /** Создаёт/возобновляет сессию ARCore. false — нужно подождать (установка, разрешение) или AR недоступен. */
    private fun startAr(): Boolean {
        try {
            if (session == null) {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return false
                }
                if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
                    return false
                }
                val s = Session(this)
                val c = Config(s)
                c.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                c.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                c.focusMode = Config.FocusMode.AUTO
                if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) c.depthMode = Config.DepthMode.AUTOMATIC
                s.configure(c)
                session = s
            }
            session!!.resume()
        } catch (e: Exception) {
            pendingMode = null
            AlertDialog.Builder(this).setTitle("AR недоступен")
                .setMessage("${e.javaClass.simpleName}: ${e.message ?: ""}\n\nНужен телефон с поддержкой ARCore и «Сервисы Google Play для AR». 3D-просмотр работает и без этого.")
                .setPositiveButton("OK", null).show()
            return false
        }
        synchronized(renderer.lock) {
            renderer.session = session
            renderer.resetAr()
        }
        compass.start()
        return true
    }

    private fun stopAr() {
        synchronized(renderer.lock) { renderer.session = null }
        session?.pause()
        compass.stop()
    }

    // ─────────────────────────── режимы ───────────────────────────

    private fun enterMode(m: Mode) {
        if ((m == Mode.TABLE || m == Mode.FIELD || m == Mode.VIEWER) && pkg == null) {
            toast("Сначала откройте пакет .topoar"); return
        }
        if (m == Mode.TABLE || m == Mode.FIELD) {
            pendingMode = m
            if (!startAr()) return              // вернёмся в onResume после установки/разрешения
            pendingMode = null
        } else stopAr()

        renderer.mode = m
        setUnder(false)
        renderer.selectedPoint = null
        when (m) {
            Mode.START -> showStart()
            Mode.VIEWER -> showViewer()
            Mode.TABLE -> showTable()
            Mode.FIELD -> showField()
        }
    }

    private fun showStart() {
        renderer.mode = Mode.START
        startScreen.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        bottom.visibility = View.GONE
        mapBox.visibility = View.GONE
        crosshair.visibility = View.GONE
        updateStartPkg()
    }

    private fun showChrome(t: String) {
        startScreen.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        bottom.visibility = View.VISIBLE
        title.text = t
        adjustRow.visibility = View.GONE
        map.adjustMode = false
    }

    private fun showViewer() {
        showChrome("3D-просмотр")
        mapBox.visibility = View.GONE
        crosshair.visibility = View.GONE
        renderer.showReticle = false
        stepText.text = "1 палец — вращать · 2 пальца — приблизить к точке и сдвинуть · двойной тап — подлететь"
        primary.visibility = View.GONE
        setActions(
            underBtn(),
            act("Показать всё") { fitAll() },
            pointsBtn(),
            act("Сверху") { renderer.orbit.pitch = 1.55f; renderer.orbit.yaw = 0f },
            act("Сбоку") { renderer.orbit.pitch = 0.12f },
            act("Слои") { layersDialog() },
        )
    }

    private fun showTable() {
        showChrome("Макет на столе")
        renderer.aligner = tableAl
        tableAl.reset()
        tablePlaced = false
        tableRot = 0.0
        mapBox.visibility = View.GONE
        crosshair.visibility = View.GONE
        renderer.showReticle = true
        primary.visibility = View.VISIBLE
        primary.text = "Поставить сюда"
        primary.setOnClickListener { placeTableAtAim() }
        setActions(
            underBtn(),
            act("Крупнее") { tableScale *= 1.5; applyTable() },
            act("Мельче") { tableScale /= 1.5; applyTable() },
            pointsBtn(),
            act("Слои") { layersDialog() },
        )
    }

    private fun showField() {
        showChrome("На местности")
        renderer.aligner = fieldAl
        map.pkg = pkg
        mapBox.visibility = View.VISIBLE
        renderer.showReticle = false
        primary.visibility = View.VISIBLE
        setStep(if (fieldAl.aligned) Step.DONE else Step.STAND)
    }

    private fun setStep(s: Step) {
        step = s
        selected = null; customPoint = null; target = null
        map.selected = null
        renderer.selectedPoint = null
        crosshair.visibility = if (s == Step.SIGHT) View.VISIBLE else View.GONE
        when (s) {
            Step.STAND, Step.REFINE -> {
                setMapBig(true)
                stepText.text = (if (s == Step.STAND) "Шаг 1 из 2 · Где вы стоите?\n" else "Уточнение · Встаньте на другую точку\n") +
                    "Встаньте точно на заметную точку и нажмите её на карте. Надёжнее всего — геодезический пункт (бордовый треугольник), потом угол здания, люк, столб.\n" +
                    "Нужной точки нет — долгое нажатие на карте отметит любое место."
                primary.text = "Я стою здесь"
                primary.setOnClickListener { standHere() }
                setActions(
                    act("К моему месту") { centerMapOnMe() },
                    act(if (s == Step.STAND) "Назад" else "Отмена") { if (s == Step.STAND) enterMode(Mode.START) else setStep(Step.DONE) },
                )
            }
            Step.SIGHT -> {
                setMapBig(false)
                stepText.text = "Шаг 2 из 2 · Куда смотрите?\n" +
                    "Нажмите на карте другую точку, которую видно отсюда (лучше 20–100 м). " +
                    "Наведите на неё крестик в центре экрана и нажмите «Навёл»."
                primary.text = "Навёл"
                primary.setOnClickListener { sightNow() }
                setActions(
                    act("Пропустить (2-я точка)") { setStep(Step.REFINE) },
                    act("Заново") { fieldAl.reset(); setStep(Step.STAND) },
                )
            }
            Step.DONE -> {
                setMapBig(false)
                stepText.text = "Готово — план на месте. Подвинуть вручную: «Подвинуть» и тащите карту."
                primary.text = "Подземка: показать сети"
                primary.setOnClickListener { toggleUnder(primary) }
                setActions(
                    pointsBtn(),
                    act("Слои") { layersDialog() },
                    act("Карта ↕") { setMapBig(!bigMap) },
                    act("Подвинуть") { toggleAdjust(it) },
                    act("Уточнить") { setStep(Step.REFINE) },
                    act("Заново") { fieldAl.reset(); setStep(Step.STAND) },
                    act("Инфо") { infoDialog() },
                )
            }
        }
        updatePrimary()
    }

    private fun setMapBig(big: Boolean) {
        bigMap = big
        val dm = resources.displayMetrics
        val lp = mapBox.layoutParams as LinearLayout.LayoutParams
        if (big) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = (dm.heightPixels * 0.48).toInt()
            lp.gravity = Gravity.NO_GRAVITY
        } else {
            lp.width = (dm.widthPixels * 0.46).toInt()
            lp.height = (dm.widthPixels * 0.46).toInt()
            lp.gravity = Gravity.END
        }
        mapBox.layoutParams = lp
    }

    // ─────────────────────────── интерфейс ───────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, stroke: Int = Color.argb(70, 255, 255, 255), r: Int = 12) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(color)
        setStroke(dp(1), stroke)
    }

    private fun act(text: String, onClick: (Button) -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.textSize = 15f
        b.setTextColor(Color.WHITE)
        b.background = rounded(Color.argb(215, 30, 36, 32))
        b.setPadding(dp(14), 0, dp(14), 0)
        b.minHeight = dp(46)
        b.minimumHeight = dp(46)
        b.setOnClickListener { onClick(b) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46))
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        b.layoutParams = lp
        return b
    }

    private fun setActive(b: Button, on: Boolean) {
        b.background = if (on) rounded(Color.rgb(255, 123, 51), Color.rgb(255, 179, 122)) else rounded(Color.argb(215, 30, 36, 32))
        b.setTextColor(if (on) Color.rgb(26, 13, 4) else Color.WHITE)
    }

    private fun setActions(vararg bs: Button) {
        actions.removeAllViews()
        bs.forEach { actions.addView(it) }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        return t
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        val root = FrameLayout(this)

        glView = GLSurfaceView(this)
        glView.preserveEGLContextOnPause = true
        glView.setEGLContextClientVersion(3)
        glView.setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView.setOnTouchListener { _, e -> onGlTouch(e); true }
        root.addView(glView, FrameLayout.LayoutParams(-1, -1))

        crosshair = Crosshair(this)
        root.addView(crosshair, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))

        // верх: заголовок, статус, карта
        topBar = LinearLayout(this)
        topBar.orientation = LinearLayout.VERTICAL
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(8), dp(28), dp(14), dp(2))
        val back = act("‹ Меню") { enterMode(Mode.START) }
        head.addView(back)
        title = text("", 18f, bold = true)
        title.setPadding(dp(10), 0, 0, 0)
        head.addView(title)
        topBar.addView(head)
        status = text("", 14f)
        status.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        status.setPadding(dp(16), dp(2), dp(16), dp(8))
        topBar.addView(status)
        topBar.setBackgroundColor(Color.argb(140, 0, 0, 0))

        mapBox = FrameLayout(this)
        map = MapView(this)
        map.listener = this
        mapBox.addView(map, FrameLayout.LayoutParams(-1, -1))
        mapBox.background = rounded(Color.WHITE, Color.argb(160, 0, 0, 0), 6)
        adjustRow = LinearLayout(this)
        adjustRow.orientation = LinearLayout.VERTICAL
        adjustRow.addView(act("⟲ 1°") { rotate(Math.toRadians(1.0)) })
        adjustRow.addView(act("⟳ 1°") { rotate(Math.toRadians(-1.0)) })
        adjustRow.addView(act("▲ 5 см") { fieldAl.lift(0.05) })
        adjustRow.addView(act("▼ 5 см") { fieldAl.lift(-0.05) })
        adjustRow.visibility = View.GONE
        mapBox.addView(adjustRow, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        val mlp = LinearLayout.LayoutParams(-1, dp(300))
        mlp.setMargins(dp(8), dp(6), dp(8), 0)
        topBar.addView(mapBox, mlp)
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // низ: текст шага, главная кнопка, действия
        bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(dp(12), dp(10), dp(12), dp(18))
        bottom.setBackgroundColor(Color.argb(170, 0, 0, 0))
        stepText = text("", 15f)
        stepText.setPadding(dp(4), 0, dp(4), dp(8))
        bottom.addView(stepText)
        primary = Button(this)
        primary.isAllCaps = false
        primary.textSize = 18f
        primary.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        primary.setTextColor(Color.rgb(26, 13, 4))
        primary.background = rounded(Color.rgb(255, 123, 51), Color.rgb(255, 179, 122), 14)
        bottom.addView(primary, LinearLayout.LayoutParams(-1, dp(58)))
        actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        val sv = HorizontalScrollView(this)
        sv.isHorizontalScrollBarEnabled = false
        sv.addView(actions)
        val slp = LinearLayout.LayoutParams(-1, -2)
        slp.setMargins(0, dp(6), 0, 0)
        bottom.addView(sv, slp)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        startScreen = buildStart()
        root.addView(startScreen, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun buildStart(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(20), dp(44), dp(20), dp(24))
        col.addView(text("ТопоAR", 30f, bold = true))
        col.addView(text("Топоплан в 3D и на местности", 15f, color = Color.rgb(170, 182, 175)))

        val pkgCard = LinearLayout(this)
        pkgCard.orientation = LinearLayout.VERTICAL
        pkgCard.background = rounded(Color.rgb(28, 34, 30))
        pkgCard.setPadding(dp(16), dp(14), dp(16), dp(14))
        startPkg = text("Пакет не открыт", 15f)
        pkgCard.addView(startPkg)
        val open = act("Открыть пакет .topoar") { pickFile() }
        pkgCard.addView(open)
        val plp = LinearLayout.LayoutParams(-1, -2)
        plp.setMargins(0, dp(20), 0, dp(14))
        col.addView(pkgCard, plp)

        fun card(t: String, sub: String, m: Mode) {
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.background = rounded(Color.rgb(40, 48, 43), Color.argb(90, 255, 179, 122), 16)
            c.setPadding(dp(18), dp(16), dp(18), dp(16))
            c.addView(text(t, 20f, bold = true, color = Color.rgb(255, 160, 100)))
            c.addView(text(sub, 14.5f, color = Color.rgb(205, 214, 208)))
            c.isClickable = true
            c.setOnClickListener { enterMode(m) }
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, dp(6), 0, dp(6))
            col.addView(c, lp)
        }
        card("3D-просмотр", "Покрутить модель пальцами. Камера не нужна — удобно дома.", Mode.VIEWER)
        card("Макет на столе", "Модель через камеру на столе или полу. Встаёт сама, крутится и масштабируется пальцами.", Mode.TABLE)
        card("На местности", "План в реальном размере на участке. Привязка в 2 шага: встать на точку и навести на вторую.", Mode.FIELD)
        col.addView(text("\nПакет .topoar готовится на компьютере из DWG/DXF (конвертер topo3d).", 13f, color = Color.rgb(140, 150, 144)))

        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.rgb(17, 22, 19))
        sv.addView(col)
        return sv
    }

    private fun updateStartPkg() {
        val p = pkg
        startPkg.text = if (p == null) "Пакет не открыт. Скиньте на телефон файл .topoar и откройте его."
        else {
            val kind = if (p.kind == "photogrammetry") "3D-модель по фото" else "топоплан"
            val nets = p.layers.count { it.underground }
            val extra = buildList {
                if (nets > 0) add("сетей: $nets")
                if (p.tiles != null) add("подложка плитками: ${p.tiles.tiles.size}")
                if (p.layers.any { it.texMesh != null }) add("модель с текстурой")
                if (p.layers.any { it.points != null }) add("облако точек")
            }
            "Открыт $kind: ${p.name}\n${p.width.toInt()} × ${p.height.toInt()} м · слоёв ${p.layers.size} · точек привязки ${p.controlPoints.size}" +
                if (extra.isNotEmpty()) "\n" + extra.joinToString(" · ") else ""
        }
    }

    /** Крестик по центру экрана. */
    private class Crosshair(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 5f }
        private val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 0, 0, 0); strokeWidth = 10f }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = width / 2f
            for (pp in listOf(sh, p)) {
                c.drawLine(cx - r, cy, cx - r * 0.2f, cy, pp); c.drawLine(cx + r * 0.2f, cy, cx + r, cy, pp)
                c.drawLine(cx, cy - r, cx, cy - r * 0.2f, pp); c.drawLine(cx, cy + r * 0.2f, cx, cy + r, pp)
            }
        }
    }

    // ─────────────────────────── жесты по 3D ───────────────────────────

    private var lx = 0f; private var ly = 0f; private var ld = 0f
    private var downX = 0f; private var downY = 0f; private var downT = 0L
    private var lastTapX = 0f; private var lastTapY = 0f; private var lastTapT = 0L

    /** Двойной тап: плавно подлететь к точке в 2,5 раза. */
    private fun animateZoom(px: Float, py: Float) {
        val target = pickGround(px, py) ?: return
        val steps = 14
        val k = Math.pow(2.5, 1.0 / steps).toFloat()
        for (i in 1..steps) ui.postDelayed({ renderer.orbit.zoomTo(target, k) }, (i * 16).toLong())
    }
    private var lmx = 0f; private var lmy = 0f

    private fun baseline(e: MotionEvent) {
        if (e.pointerCount >= 2) {
            lmx = (e.getX(0) + e.getX(1)) / 2; lmy = (e.getY(0) + e.getY(1)) / 2
            ld = hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat()
        } else { lx = e.x; ly = e.y }
    }

    private fun onGlTouch(e: MotionEvent) {
        val m = renderer.mode
        if (m != Mode.VIEWER && m != Mode.TABLE) return
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                baseline(e)
                downX = e.x; downY = e.y; downT = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val now = System.currentTimeMillis()
                val tap = now - downT < 250 && hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < dp(12)
                if (tap && m == Mode.VIEWER) {
                    if (now - lastTapT < 350 && hypot((e.x - lastTapX).toDouble(), (e.y - lastTapY).toDouble()) < dp(40)) {
                        animateZoom(e.x, e.y); lastTapT = 0L
                    } else { lastTapT = now; lastTapX = e.x; lastTapY = e.y }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> baseline(e)
            MotionEvent.ACTION_POINTER_UP -> {
                // остаётся один палец — новая опора по нему
                val keep = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(keep); ly = e.getY(keep)
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    val d = hypot((e.getX(1) - e.getX(0)).toDouble(), (e.getY(1) - e.getY(0)).toDouble()).toFloat()
                    val scale = if (ld > 10f) d / ld else 1f
                    twoFingers(scale, mx - lmx, my - lmy, mx, my)
                    lmx = mx; lmy = my; ld = d
                } else {
                    oneFinger(e.x - lx, e.y - ly)
                    lx = e.x; ly = e.y
                }
            }
        }
    }

    private fun oneFinger(dx: Float, dy: Float) {
        if (renderer.mode == Mode.VIEWER) {
            renderer.orbit.yaw -= dx * 0.008f
            renderer.orbit.pitch += dy * 0.006f
        } else if (tablePlaced) {
            tableRot -= dx * 0.01
            applyTable()
        }
    }

    private fun twoFingers(scale: Float, dx: Float, dy: Float, fx: Float, fy: Float) {
        if (renderer.mode == Mode.VIEWER) {
            if (abs(scale - 1f) > 0.002f) renderer.orbit.zoomTo(pickGround(fx, fy), scale)
            renderer.orbit.pan(dx, dy, renderer.h)
        } else if (tablePlaced) {
            tableScale = (tableScale * scale).coerceIn(tableBaseScale * 0.05, tableBaseScale * 60)
            val fs = renderer.frameState
            if (fs.tracking) {
                val k = (hypot((tableAt[0] - fs.camPos[0]).toDouble(), (tableAt[2] - fs.camPos[2]).toDouble()) * 0.0018).toFloat()
                tableAt[0] += (fs.camRight[0] * dx - fs.camFwd[0] * dy) * k
                tableAt[2] += (fs.camRight[1] * dx - fs.camFwd[1] * dy) * k
            }
            applyTable()
        }
    }

    // ─────────────────────────── макет ───────────────────────────

    private fun placeTableAtAim() {
        val fs = renderer.frameState
        val aim = fs.aim ?: run { toast("Наведите крестик на стол или пол"); return }
        val p = pkg ?: return
        tableAt = aim.copyOf()
        if (!tablePlaced) {
            tableBaseScale = 0.8 / max(p.width, p.height)
            tableScale = tableBaseScale
        }
        tablePlaced = true
        renderer.showReticle = false
        applyTable()
    }

    private fun applyTable() {
        val p = pkg ?: return
        if (!tablePlaced) return
        val cx = ((p.minX + p.maxX) / 2 - p.originX).toFloat()
        val cz = (-((p.minY + p.maxY) / 2 - p.originY)).toFloat()
        tableAl.placeTable(tableAt, floatArrayOf(cx, p.groundAt(cx, cz), cz), tableScale, tableRot)
    }

    // ─────────────────────────── привязка ───────────────────────────

    override fun onPick(cp: ControlPoint) {
        when (step) {
            Step.SIGHT -> { target = cp; map.selected = cp; renderer.selectedPoint = cp.local }
            else -> { selected = cp; customPoint = null; map.selected = cp; renderer.selectedPoint = cp.local }
        }
        updatePrimary()
    }

    /** Долгое нажатие на карте: «я стою здесь» в любом месте. */
    override fun onStandHere(lx: Float, lz: Float, cp: ControlPoint?) {
        if (renderer.mode != Mode.FIELD || (step != Step.STAND && step != Step.REFINE)) return
        val p = pkg ?: return
        if (cp != null) { onPick(cp); return }
        selected = null
        customPoint = floatArrayOf(lx, p.groundAt(lx, lz), lz)
        map.selected = ControlPoint(0, "место на карте", "custom", 0.0, 0.0, 0.0, customPoint!!,
            p.localToPreview(lx, lz)[0], p.localToPreview(lx, lz)[1])
        renderer.selectedPoint = customPoint
        updatePrimary()
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
        if (fs.tracking) fieldAl.rotateAbout(fs.camPos, d)
    }

    private fun updatePrimary() {
        if (renderer.mode != Mode.FIELD) return
        val fs = renderer.frameState
        primary.isEnabled = when (step) {
            Step.STAND, Step.REFINE -> (selected != null || customPoint != null) && fs.tracking
            Step.SIGHT -> target != null && fs.tracking
            Step.DONE -> true
        }
        primary.alpha = if (primary.isEnabled) 1f else 0.45f
    }

    private fun standHere() {
        val fs = renderer.frameState
        if (!fs.tracking) { toast("Подождите, пока камера найдёт поверхность"); return }
        val q = selected?.local?.copyOf() ?: customPoint?.copyOf() ?: run { toast("Нажмите на карте точку, где стоите"); return }
        val name = selected?.name ?: "место на карте"
        val cam = fs.camPos
        // высота земли: найденная плоскость, иначе «рост» 1,4 м
        val p = floatArrayOf(cam[0], if (fs.groundKnown) fs.groundY else cam[1] - 1.4f, cam[2])
        fieldAl.addPair(Aligner.Obs(name, q, p, "stand"))
        if (step == Step.STAND) {
            // сразу показываем модель, повернув по компасу; точнее — на шаге 2
            if (compass.reliable) fieldAl.setCompass(compass.azimuth, fs.camFwd[0], fs.camFwd[1])
            setStep(Step.SIGHT)
            toast(if (compass.reliable) "Модель повёрнута по компасу — примерно. Теперь шаг 2." else "Теперь шаг 2: направление")
        } else {
            setStep(Step.DONE)
            toast("Привязка уточнена по ${fieldAl.count()} точкам")
        }
    }

    private fun sightNow() {
        val fs = renderer.frameState
        if (!fs.tracking) { toast("Нет трекинга"); return }
        val t = target ?: run { toast("Нажмите на карте точку, на которую наводите"); return }
        val st = fieldAl.snapshot().minByOrNull { hypot((it.p[0] - fs.camPos[0]).toDouble(), (it.p[2] - fs.camPos[2]).toDouble()) }
            ?: run { setStep(Step.STAND); return }
        val off = hypot((st.p[0] - fs.camPos[0]).toDouble(), (st.p[2] - fs.camPos[2]).toDouble())
        if (off > 3.0) { toast(String.format(Locale.US, "Вы отошли от точки на %.1f м. Вернитесь на неё или начните заново", off)); return }
        fieldAl.setSight(st.q, t.local, fs.camFwd[0], fs.camFwd[1])
        setStep(Step.DONE)
        toast("План привязан. Проверьте: оранжевая вешка должна стоять на выбранной точке")
        renderer.selectedPoint = t.local
    }

    private fun toggleAdjust(b: Button) {
        if (!fieldAl.aligned) return
        map.adjustMode = !map.adjustMode
        adjustRow.visibility = if (map.adjustMode) View.VISIBLE else View.GONE
        setActive(b, map.adjustMode)
        if (map.adjustMode) { setMapBig(true); centerMapOnMe() }
    }

    private fun centerMapOnMe() {
        val fs = renderer.frameState
        if (fs.tracking && fieldAl.aligned) { val q = fieldAl.toPlan(fs.camPos); map.centerOn(q[0], q[2]) }
    }

    /** «Подземка» (рентген): включить сети и трассы, землю — полупрозрачной; выключить — как в scene.json. */
    private fun setUnder(on: Boolean) {
        renderer.underground = on
        if (on) {
            pkg?.layers?.forEach { l -> if (l.underground || l.id == "underground_traces") renderer.layerVisible[l.id] = true }
        } else renderer.resetVisibility()
    }

    private fun toggleUnder(b: Button) {
        setUnder(!renderer.underground)
        if (b === primary) primary.text = if (renderer.underground) "Подземка: скрыть сети" else "Подземка: показать сети"
        else setActive(b, renderer.underground)
        val p = pkg
        if (renderer.underground && p != null && p.layers.none { it.underground })
            toast("В этом пакете нет подземных сетей")
    }

    private fun underBtn(): Button {
        val b = act("Подземка") { toggleUnder(it) }
        setActive(b, renderer.underground)
        return b
    }

    private fun pointsBtn(): Button {
        val b = act("Точки") { renderer.showPoints = !renderer.showPoints; setActive(it, renderer.showPoints) }
        setActive(b, renderer.showPoints)
        return b
    }

    private fun fitAll() {
        val p = pkg ?: return
        renderer.orbit.fit(p.fitBox, renderer.w, renderer.h)
    }

    /** Точка рельефа под пикселем экрана (просмотр без камеры), иначе — плоскость на высоте фокуса. */
    private fun pickGround(px: Float, py: Float): FloatArray? {
        val p = pkg ?: return null
        val o = renderer.orbit
        val r = o.ray(px, py, renderer.w, renderer.h)
        val maxT = o.dist * 6f + o.extent * 2f
        var step = max(0.01f, o.dist / 150f)
        var t = 0f
        var prevAbove = true
        var prevT = 0f
        while (t < maxT) {
            val x = r[0] + r[3] * t; val y = r[1] + r[4] * t; val z = r[2] + r[5] * t
            val inside = x >= p.fitBox[0] - 50 && x <= p.fitBox[3] + 50 && z >= p.fitBox[2] - 50 && z <= p.fitBox[5] + 50
            val above = !inside || y > p.groundAt(x, z)
            if (!above && prevAbove && t > 0f) {
                // уточнение делением пополам
                var a = prevT; var b = t
                repeat(16) {
                    val m = (a + b) / 2
                    val mx = r[0] + r[3] * m; val mz = r[2] + r[5] * m
                    if (r[1] + r[4] * m > p.groundAt(mx, mz)) a = m else b = m
                }
                val m = (a + b) / 2
                return floatArrayOf(r[0] + r[3] * m, r[1] + r[4] * m, r[2] + r[5] * m)
            }
            prevAbove = above; prevT = t
            t += step
            step *= 1.02f
        }
        if (abs(r[4]) > 1e-4f) {
            val tt = (o.ty - r[1]) / r[4]
            if (tt > 0) return floatArrayOf(r[0] + r[3] * tt, o.ty, r[2] + r[5] * tt)
        }
        return null
    }

    // ─────────────────────────── статус ───────────────────────────

    private val tick = object : Runnable {
        override fun run() {
            try { updateStatus() } catch (_: Exception) {}
            ui.postDelayed(this, 250)
        }
    }

    private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

    private fun updateStatus() {
        val m = renderer.mode
        if (m == Mode.START || m == Mode.VIEWER) {
            status.text = if (m == Mode.VIEWER) listOfNotNull(pkg?.name, renderer.perfInfo.ifEmpty { null }, renderer.tileInfo.ifEmpty { null },
                if (renderer.underground) "подземка: сети видны сквозь землю" else null).joinToString(" · ") else ""
            return
        }
        val fs = renderer.frameState
        val sb = StringBuilder()
        if (!fs.tracking) {
            sb.append(fs.message)
        } else if (m == Mode.TABLE) {
            if (!tablePlaced) {
                if (fs.aim != null && fs.aimOnPlane && fs.groundKnown) {
                    placeTableAtAim()              // нашли стол — ставим сами
                    toast("Макет поставлен. 1 палец — повернуть, 2 пальца — масштаб и сдвиг")
                } else sb.append("Медленно поводите телефоном над столом или полом — модель встанет сама")
            } else sb.append("1 палец — повернуть · 2 пальца — масштаб и сдвиг · «Поставить сюда» — в крестик")
        } else {
            if (fieldAl.aligned) {
                sb.append("Привязка: ").append(fieldAl.source)
                val res = fieldAl.residuals()
                if (res.size >= 2) {
                    sb.append(" · невязки ")
                    res.forEach { sb.append(f2(it[0].toDouble())).append(' ') }
                    sb.append("м")
                }
                val p = pkg
                if (p != null) {
                    val q = fieldAl.toPlan(fs.camPos)
                    val x0 = (p.minX - p.originX); val x1 = (p.maxX - p.originX)
                    val z0 = -(p.maxY - p.originY); val z1 = -(p.minY - p.originY)
                    val dx = max(0.0, max(x0 - q[0], q[0] - x1)); val dz = max(0.0, max(z0 - q[2], q[2] - z1))
                    val out = hypot(dx, dz)
                    if (out > 3) sb.append("\n⚠ Вы вне участка: до его края ").append(out.toInt()).append(" м. Проверьте, та ли точка выбрана.")
                }
                val dev = fieldAl.toPlan(fs.camPos)
                map.device = floatArrayOf(dev[0], dev[2])
                map.heading = fieldAl.dirToPlan(fs.camFwd[0], fs.camFwd[1])
            } else {
                sb.append(if (fs.groundKnown) "Земля найдена ✓" else "Ищу землю — наведите камеру под ноги на пару секунд")
                map.device = null; map.heading = null
            }
            map.invalidate()
        }
        if (fs.tracking && renderer.perfInfo.isNotEmpty() && (m == Mode.TABLE && tablePlaced || m == Mode.FIELD && fieldAl.aligned))
            sb.append("\n").append(renderer.perfInfo).append(if (renderer.tileInfo.isNotEmpty()) " · " + renderer.tileInfo else "")
        status.text = sb.toString()
        updatePrimary()
    }

    // ─────────────────────────── слои, инфо ───────────────────────────

    private fun layersDialog() {
        val p = pkg ?: return
        val items = ArrayList<String>()
        val ids = ArrayList<String>()
        items.add(if (p.kind == "photogrammetry") "Ортофото на рельефе" else "Топоплан (подложка)"); ids.add("plan")
        // сначала наземные, потом сети
        p.layers.sortedBy { if (it.underground || it.id == "underground_traces") 1 else 0 }.forEach {
            val tag = when {
                it.underground -> "  · сеть"
                it.texMesh != null -> "  · текстура"
                it.points != null -> "  · точки"
                else -> ""
            }
            items.add(it.title + tag); ids.add(it.id)
        }
        val checked = BooleanArray(items.size) { i ->
            if (ids[i] == "plan") renderer.showPlan else renderer.layerVisible[ids[i]] ?: false
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
            val fs = renderer.frameState
            if (fs.tracking && fieldAl.aligned) {
                val q = fieldAl.toPlan(fs.camPos)
                val msk = p.mskOf(q[0], q[2])
                msg.append("Телефон сейчас (по привязке):\nE ").append(f2(msk[0])).append("   N ").append(f2(msk[1])).append("\n\n")
            }
            msg.append("Привязка: ").append(fieldAl.source.ifEmpty { "нет" }).append('\n')
            fieldAl.snapshot().forEachIndexed { i, o -> msg.append("  ").append(i + 1).append(". ").append(o.name).append('\n') }
            msg.append("\n").append(p.stats)
        }
        msg.append("\nКомпас: ").append(if (compass.reliable) String.format(Locale.US, "%.0f°", compass.azimuth) else "нет данных")
        AlertDialog.Builder(this).setTitle("Инфо").setMessage(msg.toString()).setPositiveButton("OK", null).show()
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
        startPkg.text = "Копирую пакет…"
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
        startPkg.text = "Загружаю $label…"
        thread {
            try {
                val p = TopoPackage.load(f, renderer.maxTexture) { what -> ui.post { startPkg.text = "Загружаю $label: $what…" } }
                ui.post {
                    pkg = p
                    map.pkg = p
                    fieldAl.reset(); tableAl.reset()
                    renderer.setPackage(p)
                    updateStartPkg()
                    if (renderer.mode != Mode.START) enterMode(Mode.START)
                }
            } catch (e: Throwable) {
                ui.post { toast("Пакет не читается: ${e.message}"); updateStartPkg() }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
