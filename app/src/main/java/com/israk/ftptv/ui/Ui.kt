package com.israk.ftptv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.israk.ftptv.R

object C {
    const val BG = 0xFF0B0B10.toInt()
    const val PANEL = 0xE6101016.toInt()
    const val CARD = 0xFF1A1A22.toInt()
    const val FOCUS = 0xFFFFFFFF.toInt()
    const val ACCENT = 0xFFE50914.toInt()
    const val TEXT = 0xFFF2F2F5.toInt()
    const val DIM = 0xFF9A9AA8.toInt()
    const val FOCUS_TEXT = 0xFF0B0B10.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

/** Background that turns white (with dark text via [focusText]) when the row has D-pad focus. */
fun Context.focusBg(normal: Int = Color.TRANSPARENT, focused: Int = C.FOCUS, radius: Int = 10, selected: Int? = null): Drawable =
    StateListDrawable().apply {
        val r = dpf(radius.toFloat())
        addState(intArrayOf(android.R.attr.state_focused), rounded(focused, r))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(focused, r))
        if (selected != null) addState(intArrayOf(android.R.attr.state_selected), rounded(selected, r))
        addState(intArrayOf(), if (normal == Color.TRANSPARENT) ColorDrawable(Color.TRANSPARENT) else rounded(normal, r))
    }

fun focusText(normal: Int = C.TEXT, focused: Int = C.FOCUS_TEXT) = android.content.res.ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
    intArrayOf(focused, focused, normal),
)

fun Context.label(text: CharSequence = "", sizeSp: Float = 16f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
    }

/** A focusable pill button for the TV (works with touch too). */
fun Context.pill(text: String, onClick: (View) -> Unit): TextView = label(text, 15f).apply {
    setTextColor(focusText())
    background = focusBg(normal = 0x33FFFFFF, radius = 22)
    setPadding(dp(16), dp(10), dp(16), dp(10))
    isFocusable = true
    isClickable = true
    gravity = Gravity.CENTER
    setOnClickListener(onClick)
}

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun View.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): View {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(l, t, r, b)
    return this
}

/** Minimal list adapter: one view type, items replaced wholesale (lists are small). */
class ListAdapter<T>(
    private val create: (ViewGroup) -> View,
    private val bind: (View, T, Int) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var items: List<T> = emptyList()
        private set

    fun submit(list: List<T>) { items = list; notifyDataSetChanged() }

    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(create(parent)) {}
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = bind(holder.itemView, items[position], position)
}

fun Activity.immersive() {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }
}

/** Hides only the status bar (phone screens keep the gesture/nav bar). */
fun Activity.hideStatusBar() {
    if (Build.VERSION.SDK_INT >= 30) {
        window.insetsController?.hide(WindowInsets.Type.statusBars())
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
    }
}

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.Theme_FtpTv_Dialog)

fun Context.ask(title: String, initial: String = "", hint: String = "", onOk: (String) -> Unit) {
    val input = EditText(this).apply {
        setText(initial); setSelection(initial.length); this.hint = hint
        setSingleLine(true)
    }
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
    dialog().setTitle(title).setView(box)
        .setPositiveButton("OK") { _, _ -> onOk(input.text.toString().trim()) }
        .setNegativeButton("Cancel", null)
        .show()
}

fun Context.choose(title: String, items: List<String>, onPick: (Int) -> Unit) {
    dialog().setTitle(title).setItems(items.toTypedArray()) { _, which -> onPick(which) }.setNegativeButton("Cancel", null).show()
}
