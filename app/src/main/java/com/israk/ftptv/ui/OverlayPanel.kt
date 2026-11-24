package com.israk.ftptv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.israk.ftptv.FtpTvApp
import com.israk.ftptv.data.Channel
import com.israk.ftptv.data.Lists

/**
 * The see-through panel OK opens over the playing video: categories on the left, that list's channels
 * (current one centred, so previous/next sit right above/below) and a row of quick actions.
 */
@SuppressLint("ViewConstructor")
class OverlayPanel(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        fun channels(): List<Channel>
        fun currentChannel(): Channel?
        fun currentList(): String
        fun isMuted(): Boolean
        fun onPick(listKey: String, ch: Channel)
        fun onFavMenu(ch: Channel)
        fun onToggleMute()
        fun onServerMenu()
        fun onManageFavorites()
        fun onSettings()
        fun onUserActivity()
    }

    private val app = FtpTvApp.instance
    private val cats = RecyclerView(context)
    private val list = RecyclerView(context)
    private val header = context.label("", 20f, bold = true)
    private val sub = context.label("", 13f, C.DIM)
    private val actions = LinearLayout(context)
    private var shownKey = "all"
    private var shownChannels: List<Channel> = emptyList()
    private val catAdapter: ListAdapter<Lists.Entry>
    private val chAdapter: ListAdapter<Channel>

    init {
        visibility = GONE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isFocusable = false
        isClickable = true // swallow touches meant for the video below
        setOnClickListener { hide() }

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2000000.toInt(), 0xD9000000.toInt(), 0x00000000))
            setPadding(context.dp(24), context.dp(20), context.dp(60), context.dp(16))
            isClickable = true
            isFocusable = false
        }
        addView(panel, flp(WRAP, MATCH, Gravity.START))

        // ── categories ──
        catAdapter = ListAdapter(
            create = { parent ->
                context.label("", 16f).apply {
                    layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = context.dp(4) }
                    setPadding(context.dp(14), context.dp(11), context.dp(14), context.dp(11))
                    setTextColor(focusText())
                    background = context.focusBg(selected = 0x40FFFFFF)
                    isFocusable = true; isClickable = true
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }
            },
            bind = { v, e, _ ->
                v as TextView
                v.text = e.label
                v.isSelected = e.key == shownKey
                v.setOnFocusChangeListener { _, f -> if (f) { host.onUserActivity(); showList(e.key, keepFocus = true) } }
                v.setOnClickListener { showList(e.key, keepFocus = false); focusCurrentOrFirst() }
            },
        )
        cats.layoutManager = LinearLayoutManager(context)
        cats.adapter = catAdapter
        cats.itemAnimator = null
        panel.addView(cats, lp(context.dp(210), MATCH))

        // ── channels ──
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(right, lp(context.dp(400), MATCH).apply { leftMargin = context.dp(18) })
        right.addView(header, lp(MATCH, WRAP))
        right.addView(sub, lp(MATCH, WRAP).apply { topMargin = context.dp(4); bottomMargin = context.dp(10) })

        chAdapter = ListAdapter(create = { channelRow(it) }, bind = { v, ch, _ -> bindChannel(v, ch) })
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = chAdapter
        list.itemAnimator = null
        list.setHasFixedSize(true)
        list.setItemViewCacheSize(12)
        right.addView(list, lp(MATCH, 0, 1f))

        val scroller = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; isFillViewport = false }
        actions.orientation = LinearLayout.HORIZONTAL
        scroller.addView(actions)
        right.addView(scroller, lp(MATCH, WRAP).apply { topMargin = context.dp(10) })
    }

    private fun channelRow(parent: ViewGroup): View {
        val ctx = parent.context
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(MATCH, ctx.dp(54)).apply { bottomMargin = ctx.dp(3) }
            setPadding(ctx.dp(10), 0, ctx.dp(12), 0)
            background = ctx.focusBg(selected = 0x33E50914)
            isFocusable = true; isClickable = true; isLongClickable = true
            addView(ctx.label("", 15f, C.DIM, bold = true).apply { tag = "num"; gravity = Gravity.END; duplicateParentStateIfFocus() }, lp(ctx.dp(40), WRAP))
            addView(ImageView(ctx).apply {
                tag = "logo"; scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(0x22FFFFFF, ctx.dpf(6f)); setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            }, lp(ctx.dp(56), ctx.dp(38)).apply { leftMargin = ctx.dp(10); rightMargin = ctx.dp(12) })
            addView(ctx.label("", 17f).apply { tag = "name"; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; duplicateParentStateIfFocus() }, lp(0, WRAP, 1f))
            addView(ctx.label("", 13f, C.DIM).apply { tag = "meta"; duplicateParentStateIfFocus() }, lp(WRAP, WRAP))
        }
    }

    private fun TextView.duplicateParentStateIfFocus() {
        isDuplicateParentStateEnabled = true
        setTextColor(focusText(currentTextColor))
    }

    private fun bindChannel(v: View, ch: Channel) {
        val cur = host.currentChannel()?.id == ch.id
        v.isSelected = cur
        (v.findViewWithTag<TextView>("num")).text = ch.number.toString()
        (v.findViewWithTag<TextView>("name")).text = (if (cur) "▶ " else "") + ch.name
        val fav = app.prefs.isFavorite(ch.id)
        (v.findViewWithTag<TextView>("meta")).text = buildString {
            if (fav) append("★ ")
            if (ch.streams.size > 1) append("${ch.streams.size} srv")
        }
        val logo = v.findViewWithTag<ImageView>("logo")
        if (ch.logo != null) logo.load(ch.logo) { size(160, 110) } else logo.setImageDrawable(null)
        v.setOnClickListener { host.onPick(shownKey, ch) }
        v.setOnLongClickListener { host.onFavMenu(ch); true }
        v.setOnFocusChangeListener { _, f -> if (f) host.onUserActivity() }
    }

    val isOpen get() = visibility == VISIBLE

    fun show() {
        buildActions()
        val groups = app.prefs.favorites.value
        catAdapter.submit(Lists.entries(groups))
        showList(host.currentList(), keepFocus = false)
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(120).start()
        focusCurrentOrFirst()
    }

    fun hide() {
        if (!isOpen) return
        // block focus first, so clearing it can't land on another row of the panel that's going away
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        findFocus()?.clearFocus()
        animate().cancel()
        visibility = GONE
    }

    /** Re-binds after favorites / mute changes, keeping focus where it is. */
    fun refresh() {
        if (!isOpen) return
        buildActions()
        val groups = app.prefs.favorites.value
        catAdapter.submit(Lists.entries(groups))
        val focusedPos = list.focusedChild?.let { list.getChildAdapterPosition(it) } ?: -1
        showList(shownKey, keepFocus = true)
        if (focusedPos >= 0) list.post { list.findViewHolderForAdapterPosition(focusedPos.coerceAtMost(chAdapter.itemCount - 1))?.itemView?.requestFocus() }
    }

    private fun showList(key: String, keepFocus: Boolean) {
        val groups = app.prefs.favorites.value
        val chs = Lists.channels(key, host.channels(), groups)
        val changed = key != shownKey || chs != shownChannels
        shownKey = key
        shownChannels = chs
        header.text = Lists.label(key, groups)
        sub.text = when {
            chs.isEmpty() && key.startsWith("fav:") -> "Empty — hold OK on a channel to add it here"
            chs.isEmpty() -> "No channels here right now"
            else -> "${chs.size} channels · hold OK on a channel for favorites"
        }
        for (i in 0 until cats.childCount) {
            val c = cats.getChildAt(i)
            val pos = cats.getChildAdapterPosition(c)
            if (pos >= 0) c.isSelected = catAdapter.items.getOrNull(pos)?.key == key
        }
        if (changed) {
            chAdapter.submit(chs)
            val idx = chs.indexOfFirst { it.id == host.currentChannel()?.id }
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx.coerceAtLeast(0), list.height / 3)
        } else if (!keepFocus) chAdapter.notifyDataSetChanged()
    }

    private fun focusCurrentOrFirst() {
        val idx = shownChannels.indexOfFirst { it.id == host.currentChannel()?.id }.coerceAtLeast(0)
        if (shownChannels.isEmpty()) {
            post { cats.findViewHolderForAdapterPosition(catAdapter.items.indexOfFirst { it.key == shownKey }.coerceAtLeast(0))?.itemView?.requestFocus() ?: cats.requestFocus() }
            return
        }
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, (list.height / 3).coerceAtLeast(context.dp(120)))
        list.post { list.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() ?: list.requestFocus() }
    }

    private fun buildActions() {
        actions.removeAllViews()
        fun add(text: String, onClick: () -> Unit) {
            actions.addView(context.pill(text) { host.onUserActivity(); onClick() }, lp(WRAP, WRAP).apply { rightMargin = context.dp(8) })
        }
        add(if (host.isMuted()) "🔇 Unmute" else "🔊 Mute") { host.onToggleMute(); refresh() }
        val cur = host.currentChannel()
        if (cur != null && cur.streams.size > 1) add("⇄ Server") { host.onServerMenu() }
        if (cur != null) add(if (app.prefs.isFavorite(cur.id)) "★ Favorite" else "☆ Favorite") { host.onFavMenu(cur) }
        add("☰ Favorites") { host.onManageFavorites() }
        add("⚙ Settings") { host.onSettings() }
    }
}
