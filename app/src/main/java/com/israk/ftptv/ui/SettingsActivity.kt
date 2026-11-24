package com.israk.ftptv.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.israk.ftptv.BuildConfig
import com.israk.ftptv.FtpTvApp
import com.israk.ftptv.data.Updater
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private sealed interface Row {
        data class Header(val title: String) : Row
        data class Item(
            val title: String,
            val value: String,
            val onClick: () -> Unit,
            val onLeft: (() -> Unit)? = null,
            val onRight: (() -> Unit)? = null,
            val onLong: (() -> Unit)? = null,
        ) : Row
    }

    private val app = FtpTvApp.instance
    private val prefs get() = app.prefs
    private lateinit var rv: RecyclerView
    private lateinit var adapter: ListAdapter<Row>
    private var isTv = true
    private var shownUpdateState: Updater.State? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = FtpTvApp.isTv(this)
        if (isTv) immersive() else hideStatusBar()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(if (isTv) 80 else 16), dp(if (isTv) 30 else 24), dp(if (isTv) 80 else 16), 0)
        }
        root.addView(label("Settings", 28f, bold = true), lp(MATCH, WRAP).apply { bottomMargin = dp(14) })
        rv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null; clipToPadding = false; setPadding(0, 0, 0, dp(40)) }
        adapter = ListAdapter(create = { p -> rowView() }, bind = { v, r, _ -> bind(v, r) })
        rv.adapter = adapter
        root.addView(rv, lp(MATCH, 0, 1f))
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.repo.status.collect { render() } }
                launch { app.repo.loading.collect { render() } }
                launch { Updater.state.collect { s -> render(); onUpdateState(s) } }
            }
        }
        rv.post { rv.getChildAt(1)?.requestFocus() }
    }

    private fun rowView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
        addView(label("", 17f).apply { tag = "t"; isDuplicateParentStateEnabled = true; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
        addView(label("", 15f, C.DIM).apply { tag = "v"; isDuplicateParentStateEnabled = true; gravity = Gravity.END; maxLines = 2 }, lp(WRAP, WRAP).apply { leftMargin = dp(16) })
    }

    private fun bind(v: View, r: Row) {
        val t = v.findViewWithTag<TextView>("t")
        val value = v.findViewWithTag<TextView>("v")
        when (r) {
            is Row.Header -> {
                v.isFocusable = false; v.isClickable = false; v.background = null
                v.setPadding(dp(4), dp(18), dp(4), dp(6))
                t.text = r.title.uppercase(); t.setTextColor(C.ACCENT); t.textSize = 13f
                value.text = ""
                v.setOnClickListener(null); v.setOnLongClickListener(null); v.tag = null
            }
            is Row.Item -> {
                v.isFocusable = true; v.isClickable = true
                v.background = focusBg(normal = C.CARD, radius = 12)
                v.setPadding(dp(18), dp(14), dp(18), dp(14))
                t.text = r.title; t.setTextColor(focusText()); t.textSize = 17f
                value.text = r.value + if (r.onLeft != null && isTv) "  ◀ ▶" else ""
                value.setTextColor(focusText(C.DIM, 0xFF444450.toInt()))
                v.setOnClickListener { r.onClick() }
                if (r.onLong != null) v.setOnLongClickListener { r.onLong.invoke(); true } else v.setOnLongClickListener(null)
                v.tag = r
            }
        }
    }

    private fun render() {
        val rows = ArrayList<Row>()
        rows += Row.Header("Screen")
        rows += Row.Item("On-screen clock", if (prefs.clockOn) "On" else "Off", { prefs.clockOn = !prefs.clockOn; render() })
        val opacity = { d: Int -> prefs.clockOpacity = (prefs.clockOpacity + d).coerceIn(10, 100); render() }
        rows += Row.Item("Clock transparency", "${prefs.clockOpacity}% visible", {
            prefs.clockOpacity = if (prefs.clockOpacity >= 100) 10 else prefs.clockOpacity + 10; render()
        }, onLeft = { opacity(-10) }, onRight = { opacity(+10) })
        rows += Row.Item("Clock format", if (prefs.clock24h) "24-hour" else "12-hour", { prefs.clock24h = !prefs.clock24h; render() })
        rows += Row.Item("Picture size", listOf("Fit", "Stretch", "Zoom")[prefs.aspect.coerceIn(0, 2)], { prefs.aspect = (prefs.aspect + 1) % 3; render() })

        rows += Row.Header("Playback")
        rows += Row.Item("Server choice", if (prefs.autoServer) "Automatic — fastest working" else "Fixed order (first that works)",
            { prefs.autoServer = !prefs.autoServer; render() })
        rows += Row.Item("Buffering", if (prefs.bufferMode == 0) "Fast zapping" else "Stable (weak connection)",
            { prefs.bufferMode = 1 - prefs.bufferMode; render(); Toast.makeText(this, "Applies when you reopen the player", Toast.LENGTH_SHORT).show() })
        rows += Row.Item("When the app opens", if (prefs.startWithLast) "Last watched channel" else "First channel", { prefs.startWithLast = !prefs.startWithLast; render() })

        rows += Row.Header("Servers")
        val status = app.repo.status.value
        for (cfg in prefs.sources()) {
            val st = status.firstOrNull { it.config.id == cfg.id }
            val v = when {
                !cfg.enabled -> "Off"
                st == null || st.ok == null -> if (app.repo.loading.value) "Checking…" else "On"
                st.ok == true -> "On · ${st.channels} ch · ${st.ms} ms"
                else -> "On · not reachable"
            }
            rows += Row.Item(cfg.title + if (!cfg.builtin) "  (M3U)" else "", v, {
                prefs.setSourceEnabled(cfg.id, !cfg.enabled); app.repo.refresh(); render()
            }, onLong = if (!cfg.builtin) ({
                dialog().setTitle("Remove ${cfg.title}?").setPositiveButton("Remove") { _, _ -> prefs.removeSource(cfg.id); app.repo.refresh(); render() }
                    .setNegativeButton("Cancel", null).show()
                Unit
            }) else null)
        }
        rows += Row.Item("Refresh channel list", if (app.repo.loading.value) "Working…" else "${app.repo.channels.value.size} channels", { app.repo.refresh(); render() })
        rows += Row.Item("Add M3U playlist…", "Your own link", { addM3u() })

        rows += Row.Header("App")
        val upd = when (val s = Updater.state.value) {
            Updater.State.Idle -> "v${BuildConfig.VERSION_NAME}"
            Updater.State.Checking -> "Checking…"
            Updater.State.UpToDate -> "Up to date (v${BuildConfig.VERSION_NAME})"
            is Updater.State.Available -> "v${s.release.version} available"
            is Updater.State.Downloading -> "Downloading ${(s.progress * 100).toInt()}%"
            is Updater.State.Installing -> "Installing…"
            is Updater.State.NeedsPermission -> "Allow installs first"
            is Updater.State.Failed -> "⚠ ${s.message}"
        }
        rows += Row.Item("Check for updates", upd, {
            when (val s = Updater.state.value) {
                is Updater.State.Available -> showUpdate(s.release)
                is Updater.State.NeedsPermission -> askPermission(s.release)
                is Updater.State.Downloading, is Updater.State.Installing -> Unit
                else -> Updater.check()
            }
        })
        rows += Row.Item("Favorites folders", "Group & reorder", { startActivity(android.content.Intent(this, FavoritesActivity::class.java)) })
        rows += Row.Item("About", "FTP TV v${BuildConfig.VERSION_NAME} · Made ❤️ by Israk Sisir", {
            dialog().setTitle("FTP TV").setMessage(
                "Live TV for BDIX/FTP servers, built for the Mi TV Stick.\n\n" +
                    "Up/Down: change channel\nLeft/Right: volume (hold Left = mute)\nOK: channel list · hold OK: favorites\nNumbers: jump to a channel\n\n" +
                    "Made ❤️ by Israk Sisir",
            ).setPositiveButton("OK", null).show()
        })

        val focusedPos = rv.focusedChild?.let { rv.getChildAdapterPosition(it) } ?: -1
        adapter.submit(rows)
        if (focusedPos >= 0) rv.post { rv.findViewHolderForAdapterPosition(focusedPos)?.itemView?.requestFocus() }
    }

    private fun addM3u() {
        ask("Playlist link (M3U / M3U8)", "http://", "http://server/playlist.m3u") { url ->
            if (!url.startsWith("http")) { Toast.makeText(this, "Enter a http:// or https:// link", Toast.LENGTH_SHORT).show(); return@ask }
            ask("Name for this server", url.substringAfter("://").substringBefore('/')) { name ->
                prefs.addM3u(name.ifBlank { "My playlist" }, url)
                app.repo.refresh()
                render()
            }
        }
    }

    private fun onUpdateState(s: Updater.State) {
        if (s == shownUpdateState) return
        shownUpdateState = s
        when (s) {
            is Updater.State.Available -> if (!isFinishing) showUpdate(s.release)
            is Updater.State.NeedsPermission -> askPermission(s.release)
            else -> Unit
        }
    }

    private fun showUpdate(r: Updater.Release) {
        dialog().setTitle("FTP TV ${r.version}")
            .setMessage(r.notes.ifBlank { "A new version is ready." })
            .setPositiveButton("Update now") { _, _ -> Updater.downloadAndInstall(r) }
            .setNegativeButton("Later", null).show()
    }

    private fun askPermission(r: Updater.Release) {
        dialog().setTitle("Allow FTP TV to install updates")
            .setMessage("Android needs your OK once: turn on “Unknown sources” / “Install unknown apps” for FTP TV, then come back and press Update again.")
            .setPositiveButton("Open settings") { _, _ -> Updater.openInstallPermission(this) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Left/Right change sliders (clock transparency) without leaving the row. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val item = rv.focusedChild?.tag as? Row.Item
            val f = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) item?.onLeft else item?.onRight
            if (f != null) { f(); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        render()
    }
}
