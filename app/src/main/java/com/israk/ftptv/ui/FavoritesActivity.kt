package com.israk.ftptv.ui

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.israk.ftptv.FtpTvApp
import com.israk.ftptv.data.Channel
import com.israk.ftptv.data.FavGroup
import kotlinx.coroutines.launch

/**
 * Favorites folders. D-pad: OK on a group or channel picks it up — Up/Down moves it — OK again drops it.
 * Hold OK for rename / delete / move to another group.
 */
class FavoritesActivity : ComponentActivity() {

    companion object {
        /** The "★" menu used from the player and the phone list. */
        fun quickMenu(ctx: Context, channelId: String, name: String, onDone: () -> Unit) {
            val prefs = FtpTvApp.instance.prefs
            val groups = prefs.favorites.value
            val items = groups.map { g -> if (channelId in g.ids) "★  Remove from ${g.name}" else "☆  Add to ${g.name}" } + "＋  New group…"
            ctx.choose(name, items) { i ->
                if (i < groups.size) {
                    prefs.toggleInGroup(groups[i].id, channelId); onDone()
                } else ctx.ask("New favorites group", hint = "e.g. Sports, Family") { n ->
                    if (n.isNotBlank()) { val g = prefs.addGroup(n); prefs.toggleInGroup(g.id, channelId); onDone() }
                }
            }
        }
    }

    private val app = FtpTvApp.instance
    private val prefs get() = app.prefs
    private var isTv = true
    private var selectedGroup: String? = null
    private var movingGroup: String? = null
    private var movingChannel: String? = null

    private lateinit var groupsRv: RecyclerView
    private lateinit var chRv: RecyclerView
    private lateinit var chTitle: TextView
    private lateinit var hint: TextView
    private lateinit var groupAdapter: ListAdapter<FavGroup>
    private lateinit var chAdapter: ListAdapter<Channel>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = FtpTvApp.isTv(this)
        if (isTv) immersive() else hideStatusBar()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(if (isTv) 48 else 16), dp(if (isTv) 30 else 24), dp(if (isTv) 48 else 16), dp(16))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("Favorites", 28f, bold = true), lp(0, WRAP, 1f))
        top.addView(pill("＋ New group") { newGroup() })
        top.addView(pill("＋ Add channels") { addChannels() }, lp(WRAP, WRAP).apply { leftMargin = dp(10) })
        root.addView(top)
        hint = label(if (isTv) "OK = pick up & move with ▲▼, OK again to drop · hold OK for more" else "Tap for options", 14f, C.DIM)
        root.addView(hint, lp(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(14) })

        val cols = LinearLayout(this).apply { orientation = if (isTv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(cols, lp(MATCH, 0, 1f))

        groupsRv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context, if (isTv) RecyclerView.VERTICAL else RecyclerView.HORIZONTAL, false); itemAnimator = null }
        groupAdapter = ListAdapter(
            create = { p ->
                label("", 17f).apply {
                    layoutParams = RecyclerView.LayoutParams(if (isTv) MATCH else WRAP, WRAP).apply { bottomMargin = dp(4); rightMargin = dp(6) }
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                    setTextColor(focusText()); background = focusBg(selected = 0x40FFFFFF)
                    isFocusable = true; isClickable = true; isLongClickable = true
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }
            },
            bind = { v, g, _ ->
                v as TextView
                v.text = (if (movingGroup == g.id) "↕ " else "★ ") + g.name + "  (${g.ids.size})"
                v.isSelected = g.id == selectedGroup
                v.setOnFocusChangeListener { _, f -> if (f && movingGroup == null) selectGroup(g.id) }
                v.setOnClickListener {
                    if (!isTv) { selectGroup(g.id); return@setOnClickListener }
                    movingGroup = if (movingGroup == g.id) null else g.id
                    movingChannel = null
                    groupAdapter.notifyDataSetChanged()
                }
                v.setOnLongClickListener { groupMenu(g); true }
            },
        )
        groupsRv.adapter = groupAdapter
        cols.addView(groupsRv, if (isTv) lp(dp(280), MATCH) else lp(MATCH, WRAP))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(right, if (isTv) lp(0, MATCH, 1f).apply { leftMargin = dp(24) } else lp(MATCH, 0, 1f).apply { topMargin = dp(12) })
        chTitle = label("", 18f, C.DIM)
        right.addView(chTitle, lp(MATCH, WRAP).apply { bottomMargin = dp(8) })
        chRv = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context); itemAnimator = null }
        chAdapter = ListAdapter(create = { p -> row(p.context) }, bind = { v, ch, _ -> bindRow(v, ch) })
        chRv.adapter = chAdapter
        right.addView(chRv, lp(MATCH, 0, 1f))
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { prefs.favorites.collect { render() } }
                launch { app.repo.channels.collect { render() } }
            }
        }
    }

    private fun row(ctx: Context): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(MATCH, dp(56)).apply { bottomMargin = dp(4) }
        setPadding(dp(12), 0, dp(12), 0)
        background = focusBg(normal = C.CARD, selected = 0x66E50914)
        isFocusable = true; isClickable = true; isLongClickable = true
        addView(ImageView(ctx).apply { tag = "logo"; scaleType = ImageView.ScaleType.FIT_CENTER }, lp(dp(56), dp(38)).apply { rightMargin = dp(14) })
        addView(label("", 17f).apply { tag = "name"; isDuplicateParentStateEnabled = true; setTextColor(focusText()); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, lp(0, WRAP, 1f))
    }

    private fun bindRow(v: View, ch: Channel) {
        v.isSelected = movingChannel == ch.id
        v.findViewWithTag<TextView>("name").text = (if (movingChannel == ch.id) "↕  " else "${ch.number}  ") + ch.name
        val logo = v.findViewWithTag<ImageView>("logo")
        if (ch.logo != null) logo.load(ch.logo) { size(160, 110) } else logo.setImageDrawable(null)
        v.setOnClickListener {
            if (!isTv) { channelMenu(ch); return@setOnClickListener }
            movingChannel = if (movingChannel == ch.id) null else ch.id
            movingGroup = null
            chAdapter.notifyDataSetChanged()
        }
        v.setOnLongClickListener { channelMenu(ch); true }
    }

    private fun render() {
        val groups = prefs.favorites.value
        if (selectedGroup == null || groups.none { it.id == selectedGroup }) selectedGroup = groups.firstOrNull()?.id
        groupAdapter.submit(groups)
        renderChannels()
    }

    private fun renderChannels() {
        val g = prefs.favorites.value.firstOrNull { it.id == selectedGroup }
        val byId = app.repo.channels.value.associateBy { it.id }
        val list = g?.ids?.mapNotNull { byId[it] }.orEmpty()
        chTitle.text = if (g == null) "" else if (list.isEmpty()) "${g.name} is empty — use “＋ Add channels”, or hold OK on a channel while watching" else "${g.name} · ${list.size} channels"
        chAdapter.submit(list)
    }

    private fun selectGroup(id: String) {
        if (selectedGroup == id) return
        selectedGroup = id
        for (i in 0 until groupsRv.childCount) {
            val c = groupsRv.getChildAt(i); val pos = groupsRv.getChildAdapterPosition(c)
            if (pos >= 0) c.isSelected = groupAdapter.items.getOrNull(pos)?.id == id
        }
        renderChannels()
    }

    private fun newGroup() = ask("New favorites group", hint = "e.g. Sports, Family, Kids") { n ->
        if (n.isNotBlank()) { val g = prefs.addGroup(n); selectedGroup = g.id; render() }
    }

    private fun addChannels() {
        val g = prefs.favorites.value.firstOrNull { it.id == selectedGroup } ?: return
        val all = app.repo.channels.value
        if (all.isEmpty()) { Toast.makeText(this, "Channels are still loading", Toast.LENGTH_SHORT).show(); return }
        val checked = BooleanArray(all.size) { all[it].id in g.ids }
        dialog().setTitle("Add to ${g.name}")
            .setMultiChoiceItems(all.map { "${it.number}  ${it.name}" }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Save") { _, _ ->
                val keep = g.ids.filter { id -> all.indexOfFirst { it.id == id }.let { it < 0 || checked[it] } }
                val added = all.indices.filter { checked[it] && all[it].id !in g.ids }.map { all[it].id }
                prefs.setFavorites(prefs.favorites.value.map { if (it.id == g.id) it.copy(ids = keep + added) else it })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun groupMenu(g: FavGroup) {
        choose(g.name, listOf("Rename", "Move up", "Move down", "Delete group")) { i ->
            when (i) {
                0 -> ask("Rename group", g.name) { if (it.isNotBlank()) prefs.renameGroup(g.id, it) }
                1 -> prefs.moveGroup(g.id, -1)
                2 -> prefs.moveGroup(g.id, +1)
                3 -> dialog().setTitle("Delete “${g.name}”?").setMessage("The channels stay in the app; only this folder goes.")
                    .setPositiveButton("Delete") { _, _ -> prefs.deleteGroup(g.id) }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun channelMenu(ch: Channel) {
        val gid = selectedGroup ?: return
        val others = prefs.favorites.value.filter { it.id != gid }
        choose(ch.name, listOf("▶ Watch", "Move up", "Move down") + (if (others.isNotEmpty()) listOf("Move to another group…") else emptyList()) + "Remove from group") { i ->
            when (i) {
                0 -> { PlayerActivity.open(this, ch.id, "fav:$gid"); finish() }
                1 -> prefs.moveInGroup(gid, ch.id, -1)
                2 -> prefs.moveInGroup(gid, ch.id, +1)
                3 -> if (others.isNotEmpty()) choose("Move to", others.map { it.name }) { j -> prefs.moveToGroup(gid, others[j].id, ch.id) }
                     else prefs.toggleInGroup(gid, ch.id)
                4 -> prefs.toggleInGroup(gid, ch.id)
            }
        }
    }

    /** While something is "picked up", Up/Down move it instead of the focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
        val down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if ((up || down) && (movingChannel != null || movingGroup != null)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val d = if (up) -1 else +1
                movingChannel?.let { id ->
                    val gid = selectedGroup ?: return true
                    prefs.moveInGroup(gid, id, d)
                    render()
                    focusLater(chRv) { chAdapter.items.indexOfFirst { it.id == id } }
                }
                movingGroup?.let { id ->
                    prefs.moveGroup(id, d)
                    render()
                    focusLater(groupsRv) { groupAdapter.items.indexOfFirst { it.id == id } }
                }
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && (movingChannel != null || movingGroup != null)) {
            if (event.action == KeyEvent.ACTION_UP) { movingChannel = null; movingGroup = null; render() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun focusLater(rv: RecyclerView, pos: () -> Int) {
        rv.post {
            val p = pos(); if (p < 0) return@post
            rv.scrollToPosition(p)
            rv.post { rv.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
        }
    }
}
