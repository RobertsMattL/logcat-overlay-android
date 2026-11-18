package com.devtechdesign.android.logcat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Floating, semi-transparent, draggable and resizable logcat window.
 *
 * - Drag the title bar to move it; drag the ◢ corner to resize it.
 * - Type in the search box to filter (case-insensitive; several words must all match).
 * - The level button cycles the minimum level shown (V → D → I → W → E).
 * - Long-press a line to copy it.
 *
 * Geometry and settings live in [LogcatOverlay.state] so they survive moving between activities.
 */
@SuppressLint("ViewConstructor", "SetTextI18n", "ClickableViewAccessibility")
class LogcatOverlayView(
    context: Context,
    private val state: LogcatOverlay.State,
    private val onClose: () -> Unit
) : FrameLayout(context), LogcatReader.Listener {

    private val filtered = ArrayList<LogLine>()
    private val adapter = LineAdapter()
    private val list: RecyclerView
    private val countText: TextView
    private val levelButton: TextView
    private val pauseButton: TextView
    private val search: EditText
    private var terms: List<String> = emptyList()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    init {
        background = GradientDrawable().apply {
            setColor(Color.argb(0xC8, 0x0B, 0x0F, 0x14))
            cornerRadius = dp(8f)
            setStroke(dp(1), Color.argb(0x80, 0x4C, 0xAF, 0x50))
        }
        alpha = state.opacity
        elevation = dp(12f)
        isClickable = true // don't let touches on the window fall through to the map

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // --- Title bar (drag handle + buttons) ---
        val titleBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setBackgroundColor(Color.argb(0x60, 0x00, 0x00, 0x00))
        }
        val title = label("⠿ LOGCAT", 12f, Color.rgb(0x4C, 0xAF, 0x50), bold = true)
        countText = label("", 10f, Color.LTGRAY)
        titleBar.addView(title)
        titleBar.addView(countText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(8)
        })
        levelButton = button("") { cycleLevel() }
        pauseButton = button("") { togglePause() }
        titleBar.addView(levelButton)
        titleBar.addView(pauseButton)
        titleBar.addView(button("◐") { cycleOpacity() })
        titleBar.addView(button("⌫") { LogcatReader.clear() })
        titleBar.addView(button("✕") { onClose() })
        column.addView(titleBar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        title.setOnTouchListener(DragToMove())
        titleBar.setOnTouchListener(DragToMove())

        // --- Search box ---
        search = EditText(context).apply {
            hint = "Filter logs…"
            setText(state.query)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(0x80, 0x20, 0x26, 0x2E))
                cornerRadius = dp(4f)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    state.query = s?.toString().orEmpty()
                    refilter()
                }
            })
        }
        column.addView(search, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(4), dp(6), dp(4))
        })

        // --- Log lines ---
        list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
            adapter = this@LogcatOverlayView.adapter
            itemAnimator = null
            setPadding(dp(4), 0, dp(4), dp(4))
            clipToPadding = false
        }
        column.addView(list, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // --- Resize handle (bottom-right corner) ---
        val resize = label("◢", 16f, Color.rgb(0x4C, 0xAF, 0x50)).apply {
            gravity = Gravity.CENTER
            setOnTouchListener(DragToResize())
        }
        addView(resize, LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.END))

        updateButtons()
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        LogcatReader.addListener(this)
        refilter()
    }

    override fun onDetachedFromWindow() {
        LogcatReader.removeListener(this)
        super.onDetachedFromWindow()
    }

    /** Position and size come from [state]; called by the controller after adding the view. */
    fun applyGeometry() {
        val parent = parent as? ViewGroup ?: return
        val maxW = parent.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val maxH = parent.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        if (state.width <= 0) {
            state.width = (maxW * 0.9f).toInt()
            state.height = (maxH * 0.4f).toInt()
            state.x = (maxW - state.width) / 2
            state.y = dp(80)
        }
        state.width = state.width.coerceIn(dp(200), maxW)
        state.height = state.height.coerceIn(dp(140), maxH)
        state.x = state.x.coerceIn(0, (maxW - state.width).coerceAtLeast(0))
        state.y = state.y.coerceIn(0, (maxH - dp(40)).coerceAtLeast(0))
        layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(state.width, state.height)).apply {
            width = state.width
            height = state.height
            gravity = Gravity.TOP or Gravity.START
            leftMargin = state.x
            topMargin = state.y
        }
    }

    // ---------------------------------------------------------------- reader events

    override fun onLines(added: List<LogLine>, dropped: List<LogLine>) {
        if (state.paused) return
        val atBottom = !list.canScrollVertically(1)

        var removed = 0
        for (line in dropped) {
            if (filtered.isNotEmpty() && filtered[0] === line) {
                filtered.removeAt(0)
                removed++
            }
        }
        if (removed > 0) adapter.notifyItemRangeRemoved(0, removed)

        val start = filtered.size
        added.filterTo(filtered) { matches(it) }
        if (filtered.size > start) adapter.notifyItemRangeInserted(start, filtered.size - start)

        updateCount()
        if (atBottom && filtered.isNotEmpty()) list.scrollToPosition(filtered.size - 1)
    }

    override fun onCleared() {
        filtered.clear()
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        updateCount()
    }

    // ---------------------------------------------------------------- filtering

    private fun matches(line: LogLine): Boolean {
        if (levelRank(line.level) < levelRank(state.minLevel)) return false
        return terms.all { line.raw.contains(it, ignoreCase = true) }
    }

    private fun refilter() {
        terms = state.query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        filtered.clear()
        LogcatReader.lines.filterTo(filtered) { matches(it) }
        @Suppress("NotifyDataSetChanged")
        adapter.notifyDataSetChanged()
        updateCount()
        if (filtered.isNotEmpty()) list.scrollToPosition(filtered.size - 1)
    }

    private fun updateCount() {
        val total = LogcatReader.lines.size
        countText.text = if (filtered.size == total) "$total lines" else "${filtered.size} / $total"
    }

    // ---------------------------------------------------------------- buttons

    private fun cycleLevel() {
        val i = LEVELS.indexOf(state.minLevel)
        state.minLevel = LEVELS[(i + 1) % LEVELS.size]
        updateButtons()
        refilter()
    }

    private fun togglePause() {
        state.paused = !state.paused
        updateButtons()
        if (!state.paused) refilter()
    }

    private fun cycleOpacity() {
        val i = OPACITIES.indexOfFirst { it == state.opacity }
        state.opacity = OPACITIES[(i + 1) % OPACITIES.size]
        alpha = state.opacity
        state.save(context)
    }

    private fun updateButtons() {
        levelButton.text = "${state.minLevel}+"
        pauseButton.text = if (state.paused) "▶" else "❚❚"
    }

    // ---------------------------------------------------------------- touch

    /** Drag the title bar to move the window. */
    private inner class DragToMove : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var origX = 0
        private var origY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    origX = state.x; origY = state.y
                }
                MotionEvent.ACTION_MOVE -> {
                    state.x = origX + (e.rawX - startX).toInt()
                    state.y = origY + (e.rawY - startY).toInt()
                    applyGeometry()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> state.save(context)
            }
            return true
        }
    }

    /** Drag the corner handle to resize the window. */
    private inner class DragToResize : View.OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var origW = 0
        private var origH = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY
                    origW = state.width; origH = state.height
                }
                MotionEvent.ACTION_MOVE -> {
                    state.width = origW + (e.rawX - startX).toInt()
                    state.height = origH + (e.rawY - startY).toInt()
                    applyGeometry()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> state.save(context)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- views

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(text: String, onClick: () -> Unit) =
        label(text, 13f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            minWidth = dp(34)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { onClick() }
        }

    private inner class LineAdapter : RecyclerView.Adapter<LineAdapter.Holder>() {
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val tv = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10f
                setPadding(0, dp(1), 0, dp(1))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val holder = Holder(tv)
            tv.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) copy(filtered[pos].raw)
                true
            }
            return holder
        }

        override fun getItemCount() = filtered.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val line = filtered[position]
            holder.text.text = line.raw
            holder.text.setTextColor(colorFor(line.level))
        }
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("logcat", text))
        Toast.makeText(context, "Copied log line", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val LEVELS = listOf('V', 'D', 'I', 'W', 'E')
        private val OPACITIES = listOf(1.0f, 0.8f, 0.6f, 0.4f)

        private fun levelRank(level: Char) = when (level) {
            'V' -> 0; 'D' -> 1; 'I' -> 2; 'W' -> 3; 'E' -> 4; 'F', 'A' -> 5
            else -> 0
        }

        private fun colorFor(level: Char) = when (level) {
            'V' -> Color.rgb(0x9E, 0x9E, 0x9E)
            'D' -> Color.rgb(0x81, 0xD4, 0xFA)
            'I' -> Color.rgb(0xA5, 0xD6, 0xA7)
            'W' -> Color.rgb(0xFF, 0xD5, 0x4F)
            'E', 'F', 'A' -> Color.rgb(0xEF, 0x53, 0x50)
            else -> Color.LTGRAY
        }
    }
}
