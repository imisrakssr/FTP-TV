package com.israk.ftptv.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.israk.ftptv.FtpTvApp
import com.israk.ftptv.data.Channel
import com.israk.ftptv.data.Lists
import com.israk.ftptv.data.Updater
import kotlinx.coroutines.launch

/** Phone home: category chips + channel grid, floating pill tab bar, no status bar. */
class PhoneHomeActivity : ComponentActivity() {

    private val app = FtpTvApp.instance
    private val prefs get() = app.prefs
    private var tab = 0 // 0 live, 1 favorites
    private var key = "all"

    private lateinit var chips: RecyclerView
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var manage: TextView
    private lateinit var chipAdapter: ListAdapter<Lists.Entry>
    private lateinit var gridAdapter: ListAdapter<Channel>
    private val tabViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideStatusBar()
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(20), dp(14), 0) }
        root.addView(col, flp(MATCH, MATCH))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("FTP TV", 26f, bold = true), lp(0, WRAP, 1f))
        manage = pill("Manage") { startActivity(Intent(this, FavoritesActivity::class.java)) }.apply { visibility = View.GONE }
        top.addView(manage)
        col.addView(top, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        chips = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false); itemAnimator = null }
        chipAdapter = ListAdapter(
            create = {
                label("", 14f).apply {
                    layoutParams = RecyclerView.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
                    setPadding(dp(16), dp(9), dp(16), dp(9))
                    isClickable = true
                }
            },
            bind = { v, e, _ ->
                v as TextView
                val on = e.key == key
                v.text = if (tab == 1) e.label.removePrefix("★ ") else e.label
                v.setTextColor(if (on) C.FOCUS_TEXT else C.TEXT)
                v.background = rounded(if (on) Color.WHITE else 0x26FFFFFF, dpf(20f))
                v.setOnClickListener { key = e.key; chipAdapter.notifyDataSetChanged(); renderGrid() }
            },
        )
        chips.adapter = chipAdapter
        col.addView(chips, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })

        empty = label("", 15f, C.DIM).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        col.addView(empty, lp(MATCH, WRAP).apply { topMargin = dp(40) })

        val span = (resources.displayMetrics.widthPixels / dp(112)).coerceIn(3, 8)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(context, span)
            itemAnimator = null
            clipToPadding = false
            setPadding(0, 0, 0, dp(110))
            setHasFixedSize(true)
        }
        gridAdapter = ListAdapter(create = { card() }, bind = { v, ch, _ -> bindCard(v, ch) })
        grid.adapter = gridAdapter
        col.addView(grid, lp(MATCH, 0, 1f))

        // floating pill nav
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xF01C1C24.toInt(), dpf(32f), dp(1), 0x22FFFFFF)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            elevation = dpf(10f)
        }
        listOf("📺  Live", "★  Favorites", "⚙  Settings").forEachIndexed { i, t ->
            val tv = label(t, 15f, bold = true).apply {
                gravity = Gravity.CENTER; setPadding(dp(18), dp(12), dp(18), dp(12)); isClickable = true
                setOnClickListener { selectTab(i) }
            }
            tabViews += tv
            nav.addView(tv)
        }
        root.addView(nav, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(22) })
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.repo.channels.collect { renderAll() } }
                launch { app.repo.loading.collect { renderGrid() } }
                launch { prefs.favorites.collect { renderAll() } }
            }
        }
        Updater.checkIfStale()
        selectTab(0)
    }

    private fun selectTab(i: Int) {
        if (i == 2) { startActivity(Intent(this, SettingsActivity::class.java)); return }
        tab = i
        key = if (i == 1) "fav:" + (prefs.favorites.value.firstOrNull()?.id ?: "default") else "all"
        tabViews.forEachIndexed { j, tv ->
            val on = j == tab
            tv.setTextColor(if (on) C.FOCUS_TEXT else C.TEXT)
            tv.background = if (on) rounded(Color.WHITE, dpf(26f)) else null
        }
        manage.visibility = if (tab == 1) View.VISIBLE else View.GONE
        renderAll()
    }

    private fun renderAll() {
        val groups = prefs.favorites.value
        val entries = Lists.entries(groups).filter { if (tab == 1) it.isFav else !it.isFav }
        if (entries.none { it.key == key }) key = entries.firstOrNull()?.key ?: "all"
        chipAdapter.submit(entries)
        renderGrid()
    }

    private fun renderGrid() {
        val list = Lists.channels(key, app.repo.channels.value, prefs.favorites.value)
        gridAdapter.submit(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            app.repo.channels.value.isEmpty() && app.repo.loading.value -> "Loading channels…"
            app.repo.channels.value.isEmpty() -> "No channels found. Check your connection or Settings → Servers."
            tab == 1 -> "No favorites here yet.\nLong-press a channel to add it."
            else -> "Nothing in this category right now."
        }
    }

    private fun card(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { setMargins(dp(4), dp(4), dp(4), dp(8)) }
        isClickable = true; isLongClickable = true
        background = focusBg(normal = C.CARD, focused = 0x33FFFFFF, radius = 14)
        setPadding(dp(8), dp(10), dp(8), dp(10))
        addView(ImageView(context).apply { tag = "logo"; scaleType = ImageView.ScaleType.FIT_CENTER }, lp(MATCH, dp(52)))
        addView(label("", 12f).apply { tag = "name"; gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
    }

    private fun bindCard(v: View, ch: Channel) {
        v.findViewWithTag<TextView>("name").text = (if (prefs.isFavorite(ch.id)) "★ " else "") + ch.name
        val logo = v.findViewWithTag<ImageView>("logo")
        logo.dispose()
        if (ch.logo != null) logo.load(ch.logo) { size(200, 140) } else logo.setImageDrawable(null)
        v.setOnClickListener { prefs.lastList = key; PlayerActivity.open(this, ch.id, key) }
        v.setOnLongClickListener { FavoritesActivity.quickMenu(this, ch.id, ch.name) { renderGrid() }; true }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
        renderAll()
    }
}
