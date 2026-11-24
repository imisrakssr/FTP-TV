package com.israk.ftptv.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.load
import com.israk.ftptv.FtpTvApp
import com.israk.ftptv.data.Channel
import com.israk.ftptv.data.Lists
import com.israk.ftptv.data.Updater
import com.israk.ftptv.player.LiveEngine
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The TV itself: full-screen live video, always playing.
 * Up/Down zap · Left/Right volume (hold Left = mute) · OK = list overlay (hold OK = favorites) · digits = channel number.
 */
class PlayerActivity : ComponentActivity(), OverlayPanel.Host {

    companion object {
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_LIST = "list"
        fun open(ctx: Context, channelId: String?, listKey: String?) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra(EXTRA_CHANNEL, channelId).putExtra(EXTRA_LIST, listKey))
        }
    }

    private val app = FtpTvApp.instance
    private val prefs get() = app.prefs
    private val ui = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var isTv = true

    private lateinit var engine: LiveEngine
    private lateinit var playerView: PlayerView
    private lateinit var overlay: OverlayPanel
    private lateinit var clock: TextView
    private lateinit var banner: LinearLayout
    private lateinit var bannerNum: TextView
    private lateinit var bannerLogo: ImageView
    private lateinit var bannerName: TextView
    private lateinit var bannerSub: TextView
    private lateinit var volumeHud: TextView
    private lateinit var numberView: TextView
    private lateinit var centerMsg: TextView

    private var all: List<Channel> = emptyList()
    private var current: Channel? = null
    private var listKey = "all"
    private var pendingZap: Channel? = null
    private var wantChannel: String? = null
    private var longLeft = false
    private var longOk = false
    private var digits = ""
    private var lastBack = 0L
    private var stopped = false
    private var softVolume = 1f
    private var softMuted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = FtpTvApp.isTv(this)
        if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            isFocusable = false
            isFocusableInTouchMode = false
        }
        root.addView(playerView, flp(MATCH, MATCH))

        centerMsg = label("", 18f, C.TEXT).apply { gravity = Gravity.CENTER; setShadowLayer(8f, 0f, 2f, Color.BLACK) }
        root.addView(centerMsg, flp(WRAP, WRAP, Gravity.CENTER))

        clock = label("", 22f, Color.WHITE, bold = true).apply { setShadowLayer(10f, 0f, 2f, Color.BLACK) }
        root.addView(clock, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { topMargin = dp(22); rightMargin = dp(30) })

        numberView = label("", 44f, Color.WHITE, bold = true).apply {
            setShadowLayer(12f, 0f, 2f, Color.BLACK); visibility = View.GONE
            background = rounded(0x99000000.toInt(), dpf(14f)); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        root.addView(numberView, flp(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { topMargin = dp(22); leftMargin = dp(30) })

        volumeHud = label("", 18f, Color.WHITE, bold = true).apply {
            visibility = View.GONE; gravity = Gravity.CENTER
            background = rounded(0xCC000000.toInt(), dpf(24f)); setPadding(dp(22), dp(12), dp(22), dp(12))
        }
        root.addView(volumeHud, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(26) })

        banner = buildBanner()
        root.addView(banner, flp(MATCH, WRAP, Gravity.BOTTOM).apply { leftMargin = dp(30); rightMargin = dp(30); bottomMargin = dp(26) })

        overlay = OverlayPanel(this, this)
        root.addView(overlay, flp(MATCH, MATCH))
        setContentView(root)

        engine = LiveEngine(this, lifecycleScope) { s -> runOnUiThread { onEngine(s) } }
        playerView.player = engine.player

        wantChannel = intent.getStringExtra(EXTRA_CHANNEL)
        listKey = intent.getStringExtra(EXTRA_LIST) ?: prefs.lastList

        if (!isTv) setupTouch(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.repo.channels.combine(app.repo.loading) { c, l -> c to l }.collect { (list, loading) -> onChannels(list, loading) }
                }
                launch { prefs.version.collect { applyLook() } }
                launch { prefs.favorites.collect { overlay.refresh() } }
                launch {
                    Updater.available.collect { r ->
                        if (r != null) Toast.makeText(this@PlayerActivity, "FTP TV ${r.version} is available — Settings → Check for updates", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        Updater.checkIfStale()
        ui.post(clockTick)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_LIST)?.let { listKey = it }
        intent.getStringExtra(EXTRA_CHANNEL)?.let { id -> all.firstOrNull { it.id == id }?.let { playChannel(it) } ?: run { wantChannel = id } }
    }

    // ── channels / playback ──

    private fun onChannels(list: List<Channel>, loading: Boolean) {
        all = list
        if (list.isEmpty()) {
            centerMsg.text = if (loading) "Loading channels…" else "No channels found.\nCheck the network, or press OK → Settings → Servers."
            centerMsg.visibility = View.VISIBLE
            return
        }
        centerMsg.visibility = View.GONE
        val cur = current
        if (cur == null) {
            val start = wantChannel?.let { id -> list.firstOrNull { it.id == id } }
                ?: (if (prefs.startWithLast) prefs.lastChannel?.let { id -> list.firstOrNull { it.id == id } } else null)
                ?: Lists.channels(listKey, list, prefs.favorites.value).firstOrNull()
                ?: list.first()
            wantChannel = null
            playChannel(start)
        } else {
            list.firstOrNull { it.id == cur.id }?.let { current = it }
            wantChannel?.let { id -> list.firstOrNull { it.id == id }?.let { wantChannel = null; playChannel(it) } }
        }
        overlay.refresh()
    }

    private fun playChannel(ch: Channel) {
        if (current != null && current?.id != ch.id) previousChannel = current?.id
        current = ch
        prefs.lastChannel = ch.id
        engine.play(ch)
        showBanner(ch, "Connecting…", hideAfter = 0)
    }

    private fun onEngine(s: LiveEngine.State) {
        when (s) {
            is LiveEngine.State.Connecting -> {
                if (s.ch.id != current?.id) return
                val srv = s.server ?: if (s.total > 1) "finding the fastest of ${s.total} servers" else ""
                showBanner(s.ch, "Connecting… $srv".trim(), hideAfter = 0)
            }
            is LiveEngine.State.Playing -> {
                if (s.ch.id != current?.id) return
                showBanner(s.ch, s.server + if (s.total > 1) " · ${s.total} servers" else "", hideAfter = 3500)
            }
            is LiveEngine.State.Failed -> {
                if (s.ch.id != current?.id) return
                showBanner(s.ch, "⚠ ${s.message}", hideAfter = 0)
            }
        }
    }

    private fun zapList(): List<Channel> = Lists.channels(listKey, all, prefs.favorites.value).ifEmpty { all }

    private fun zap(dir: Int) {
        val list = zapList()
        if (list.isEmpty()) return
        val base = pendingZap ?: current
        val i = list.indexOfFirst { it.id == base?.id }
        val next = if (i < 0) list.first() else list[(i + dir + list.size) % list.size]
        pendingZap = next
        showBanner(next, Lists.label(listKey, prefs.favorites.value), hideAfter = 0)
        ui.removeCallbacks(zapRun)
        ui.postDelayed(zapRun, 160)
    }

    private val zapRun = Runnable { pendingZap?.let { pendingZap = null; playChannel(it) } }

    // ── on-screen bits ──

    private fun buildBanner(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(0xD9000000.toInt(), dpf(18f))
        setPadding(dp(20), dp(14), dp(24), dp(14))
        visibility = View.GONE
        bannerNum = label("", 34f, Color.WHITE, bold = true)
        addView(bannerNum, lp(WRAP, WRAP).apply { rightMargin = dp(18) })
        bannerLogo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; background = rounded(0x22FFFFFF, dpf(8f)); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        addView(bannerLogo, lp(dp(86), dp(56)).apply { rightMargin = dp(18) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        bannerName = label("", 24f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        bannerSub = label("", 15f, C.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(bannerName); col.addView(bannerSub, lp(WRAP, WRAP).apply { topMargin = dp(6) })
        addView(col, lp(0, WRAP, 1f))
    }

    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(200).withEndAction { banner.visibility = View.GONE }.start() }

    private fun showBanner(ch: Channel, sub: String, hideAfter: Long) {
        bannerNum.text = ch.number.toString()
        bannerName.text = (if (prefs.isFavorite(ch.id)) "★ " else "") + ch.name
        bannerSub.text = sub
        if (ch.logo != null) bannerLogo.load(ch.logo) { size(240, 160) } else bannerLogo.setImageDrawable(null)
        banner.animate().cancel()
        banner.alpha = 1f
        banner.visibility = View.VISIBLE
        ui.removeCallbacks(hideBanner)
        ui.postDelayed(hideBanner, if (hideAfter > 0) hideAfter else 9000)
    }

    private val clockFmt12 = SimpleDateFormat("h:mm a", Locale.US)
    private val clockFmt24 = SimpleDateFormat("HH:mm", Locale.US)
    private val clockTick = object : Runnable {
        override fun run() {
            clock.text = (if (prefs.clock24h) clockFmt24 else clockFmt12).format(Date())
            ui.postDelayed(this, 10_000)
        }
    }

    private fun applyLook() {
        clock.visibility = if (prefs.clockOn) View.VISIBLE else View.GONE
        clock.alpha = prefs.clockOpacity / 100f
        clock.text = (if (prefs.clock24h) clockFmt24 else clockFmt12).format(Date())
        playerView.resizeMode = when (prefs.aspect) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // ── volume ──

    private val fixedVolume get() = audio.isVolumeFixed

    override fun isMuted(): Boolean = if (fixedVolume) softMuted else audio.isStreamMute(AudioManager.STREAM_MUSIC)

    private fun changeVolume(d: Int) {
        if (fixedVolume) {
            softMuted = false
            softVolume = (softVolume + d * 0.1f).coerceIn(0f, 1f)
            engine.player.volume = softVolume
        } else {
            if (isMuted()) audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (d > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        }
        showVolume()
    }

    override fun onToggleMute() {
        if (fixedVolume) {
            softMuted = !softMuted
            engine.player.volume = if (softMuted) 0f else softVolume
        } else {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
        showVolume()
    }

    private val hideVolume = Runnable { volumeHud.visibility = View.GONE }

    private fun showVolume() {
        val (v, max) = if (fixedVolume) (softVolume * 10).toInt() to 10
        else audio.getStreamVolume(AudioManager.STREAM_MUSIC) to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val muted = isMuted()
        val bars = 12
        val filled = if (muted) 0 else (v * bars + max / 2) / max
        volumeHud.text = (if (muted || v == 0) "🔇  " else "🔊  ") + "▮".repeat(filled) + "▯".repeat(bars - filled) +
            (if (muted) "  Muted" else "  $v") + (if (isTv) "\nhold ◀ to ${if (muted) "unmute" else "mute"}" else "")
        volumeHud.visibility = View.VISIBLE
        ui.removeCallbacks(hideVolume)
        ui.postDelayed(hideVolume, 2600)
    }

    // ── remote control ──

    private val hideOverlay = Runnable { overlay.hide() }
    override fun onUserActivity() {
        ui.removeCallbacks(hideOverlay)
        ui.postDelayed(hideOverlay, 20_000)
    }

    private fun openOverlay() {
        if (all.isEmpty() && !app.repo.loading.value) { onSettings(); return }
        overlay.show()
        onUserActivity()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (overlay.isOpen && event.action == KeyEvent.ACTION_DOWN) onUserActivity()
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.isOpen) {
            if (keyCode == KeyEvent.KEYCODE_MENU) { overlay.hide(); return true }
            // arrows must stay unhandled here: Android moves the focus inside the panel only for keys nobody consumed
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(-1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.repeatCount == 0) { longLeft = false; event.startTracking() }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { changeVolume(+1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) { longOk = false; event.startTracking() }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT -> { openOverlay(); return true }
            KeyEvent.KEYCODE_INFO -> { current?.let { showBanner(it, Lists.label(listKey, prefs.favorites.value), 4000) }; return true }
            KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_VOLUME_MUTE -> { onToggleMute(); return true }
            KeyEvent.KEYCODE_LAST_CHANNEL -> { previousChannel?.let { id -> all.firstOrNull { it.id == id }?.let { playChannel(it) } }; return true }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { digit(keyCode - KeyEvent.KEYCODE_0); return true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.isOpen) return super.onKeyLongPress(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { longLeft = true; onToggleMute(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                longOk = true; current?.let { onFavMenu(it) }; return true
            }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (overlay.isOpen) return super.onKeyUp(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (!longLeft && !event.isCanceled) changeVolume(-1)
                longLeft = false; return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!longOk && !event.isCanceled) { if (digits.isNotEmpty()) commitDigits() else openOverlay() }
                longOk = false; return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private val dpadKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )

    private var previousChannel: String? = null

    private fun digit(d: Int) {
        if (digits.length >= 3) digits = ""
        digits += d
        numberView.text = digits
        numberView.visibility = View.VISIBLE
        ui.removeCallbacks(commitRun)
        ui.postDelayed(commitRun, 1600)
    }

    private val commitRun = Runnable { commitDigits() }

    private fun commitDigits() {
        ui.removeCallbacks(commitRun)
        val n = digits.toIntOrNull()
        digits = ""
        numberView.visibility = View.GONE
        val ch = all.firstOrNull { it.number == n } ?: all.filter { (n ?: 0) < it.number }.minByOrNull { it.number }
        if (ch == null) { Toast.makeText(this, "No channel $n", Toast.LENGTH_SHORT).show(); return }
        listKey = "all"; prefs.lastList = listKey
        playChannel(ch)
    }

    private fun onBack() {
        when {
            overlay.isOpen -> overlay.hide()
            digits.isNotEmpty() -> { digits = ""; numberView.visibility = View.GONE }
            !isTv -> finish()
            System.currentTimeMillis() - lastBack < 2500 -> finish()
            else -> { lastBack = System.currentTimeMillis(); Toast.makeText(this, "Press Back again to exit", Toast.LENGTH_SHORT).show() }
        }
    }

    // ── phone: tap = list, swipe up/down = channel, swipe left/right = volume ──

    private fun setupTouch(root: FrameLayout) {
        var acc = 0f
        val step = dpf(36f)
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { acc = 0f; return true }
            override fun onSingleTapUp(e: MotionEvent): Boolean { if (overlay.isOpen) overlay.hide() else openOverlay(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) > abs(vx) && abs(vy) > 900) { zap(if (vy < 0) +1 else -1); return true }
                return false
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (abs(dx) > abs(dy)) {
                    acc -= dx
                    while (acc > step) { changeVolume(+1); acc -= step }
                    while (acc < -step) { changeVolume(-1); acc += step }
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) { current?.let { onFavMenu(it) } }
        })
        root.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    }

    // ── OverlayPanel.Host ──

    override fun channels() = all
    override fun currentChannel() = current
    override fun currentList() = listKey

    override fun onPick(listKey: String, ch: Channel) {
        this.listKey = listKey
        prefs.lastList = listKey
        overlay.hide()
        if (ch.id != current?.id) playChannel(ch)
        else showBanner(ch, Lists.label(listKey, prefs.favorites.value), 3000)
    }

    override fun onFavMenu(ch: Channel) = FavoritesActivity.quickMenu(this, ch.id, ch.name) { overlay.refresh(); current?.let { if (it.id == ch.id && banner.visibility == View.VISIBLE) bannerName.text = (if (prefs.isFavorite(ch.id)) "★ " else "") + ch.name } }

    override fun onServerMenu() {
        val ch = current ?: return
        val order = engine.order
        if (order.size < 2) { Toast.makeText(this, "Only one server has this channel", Toast.LENGTH_SHORT).show(); return }
        val active = engine.active
        val names = listOf("⚡ Automatic (fastest)") + order.mapIndexed { i, r ->
            "${i + 1}. " + app.repo.sourceTitle(r.sourceId) + (if (r == active) "   ▶ playing" else "") +
                (if (prefs.pinnedSource(ch.id) == app.repo.keyOf(r)) "   📌" else "")
        }
        choose("Server for ${ch.name}", names) { i ->
            overlay.hide()
            if (i == 0) { prefs.setPinnedSource(ch.id, null); engine.retry() } else engine.useServer(order[i - 1])
        }
    }

    override fun onManageFavorites() { overlay.hide(); startActivity(Intent(this, FavoritesActivity::class.java)) }
    override fun onSettings() { overlay.hide(); startActivity(Intent(this, SettingsActivity::class.java)) }

    // ── lifecycle ──

    override fun onStart() {
        super.onStart()
        immersive()
        if (stopped) { stopped = false; current?.let { engine.play(it) } }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        engine.player.stop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        engine.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }
}
