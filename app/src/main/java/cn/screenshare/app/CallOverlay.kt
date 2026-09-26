package cn.screenshare.app

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.isVisible
import kotlinx.coroutines.*
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** A single local presentation window; it reuses the call's tracks and commands. */
internal class CallOverlay(private val service: CallService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ink = Color.rgb(247,240,223)
    private val muted = Color.rgb(182,192,207)
    private val accent = Color.rgb(242,210,141)
    private val surface = Color.rgb(41,51,69)
    private val root = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
    private val title = label("共享通话", 15f, ink, true)
    private val fold = button("收起") { collapse(!collapsed) }
    private val bodyScroll = ScrollView(service).apply { isFillViewport = false }
    private val body = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val videoBar = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
    private val videoLabel = label("对方视频", 12f, muted)
    private val videoToggle = button("隐藏视频") {
        CallService.state.value = CallService.state.value.copy(floatingVideo = !CallService.state.value.floatingVideo)
    }
    private val video = FrameLayout(service)
    private val messages = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val chatScroll = ScrollView(service)
    private val unread = button("新消息") { chatScroll.fullScroll(View.FOCUS_DOWN); service.session.readChat() }
    private val editorRow = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL }
    private val editor = object : EditText(service) {
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus && isFocused) post {
                service.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
    private val send = button("发送", primary = true) { sendMessage() }
    private val actions = LinearLayout(service)
    private val mute = button("静音") { service.session.toggleMute() }
    private val qualityButton = button("画质") {
        finishEditing()
        qualityPanel.isVisible = !qualityPanel.isVisible
        if (qualityPanel.isVisible) bodyScroll.post { bodyScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private val stop = button("停止共享") { service.session.stopSharing() }
    private val qualityPanel = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
    private val qualityLabel = label("", 12f, muted)
    private val feedback = label("", 12f, Color.rgb(255,180,171))
    private val presetButtons = mutableListOf<Pair<Quality, Button>>()
    private var renderer: SurfaceViewRenderer? = null
    private var track: VideoTrack? = null
    private var collapsed = false
    private var attached = false
    val displaysMessages: Boolean get() = attached && !collapsed && chatVisible()
    private fun chatVisible(): Boolean {
        val visible = android.graphics.Rect()
        return chatScroll.height > 0 && chatScroll.getGlobalVisibleRect(visible) && visible.height() >= chatScroll.height
    }
    private var revision = -1
    private var imeHeight = 0
    private var params = WindowManager.LayoutParams(
        dp(328), dp(460), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(80); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()
    private fun shape(color: Int, radius: Int = 12) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(service).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private fun button(value: String, primary: Boolean = false, action: () -> Unit) = Button(service).apply {
        text = value; textSize = 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        setTextColor(if (primary) Color.rgb(48,41,28) else ink)
        background = RippleDrawable(ColorStateList.valueOf(0x334F6480), shape(if (primary) accent else surface), null)
        setOnClickListener { action() }
    }
    private fun LinearLayout.addWeighted(view: View) { addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }) }
    private fun TextView.setLabel(value: String) { if (text.toString() != value) text = value }

    @Suppress("ClickableViewAccessibility")
    fun show(): Boolean {
        if (!Settings.canDrawOverlays(service)) return false
        root.background = shape(Color.rgb(29,35,49), 16)
        root.elevation = dp(8).toFloat(); root.clipToOutline = true
        root.setPadding(dp(12), dp(8), dp(12), dp(12))
        header.addWeighted(title); header.addView(fold)
        val close = ImageButton(service).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel); imageTintList = ColorStateList.valueOf(muted)
            contentDescription = "关闭悬浮窗，通话继续"; background = RippleDrawable(ColorStateList.valueOf(0x334F6480), shape(Color.TRANSPARENT), null)
            setOnClickListener { service.hideCallOverlay() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        root.addView(header)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        title.contentDescription = "拖动悬浮窗；收起时点击展开"
        title.setOnClickListener { if (collapsed) collapse(false) }
        title.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX-downX) + kotlin.math.abs(e.rawY-downY) > dp(6)) moved = true
                    if (moved) { params.x = startX+(e.rawX-downX).toInt(); params.y = startY+(e.rawY-downY).toInt(); resize() }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); true }
                else -> false
            }
        }
        root.setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_OUTSIDE) finishEditing(); false }
        // Overlay windows can receive zero IME insets; use the visible display frame.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val screenHeight = service.resources.displayMetrics.heightPixels
            val obscured = (screenHeight - visible.bottom).coerceAtLeast(0)
            val height = if (obscured > dp(150)) obscured else 0
            if (imeHeight != height) { imeHeight = height; root.post { resize() } }
        }
        bodyScroll.addView(body)
        root.addView(bodyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        videoBar.addWeighted(videoLabel); videoBar.addView(videoToggle); body.addView(videoBar)
        video.background = shape(Color.rgb(16,21,31)); video.clipToOutline = true
        body.addView(video, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        body.addView(label("本次聊天", 12f, muted, true))
        chatScroll.addView(messages); body.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
        chatScroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int -> if (atBottom() && displaysMessages) service.session.readChat() }
        unread.visibility = View.GONE; body.addView(unread)
        editor.apply {
            hint = "发送消息…"; setHintTextColor(muted); setTextColor(ink); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 2; filters = arrayOf(InputFilter.LengthFilter(2000)); imeOptions = EditorInfo.IME_ACTION_SEND
            background = shape(surface); setPadding(dp(12), dp(8), dp(12), dp(8)); contentDescription = "悬浮窗消息输入框"
            setText(service.overlayDraft)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv(); resize()
                    requestFocus(); post { service.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
                }; false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { service.overlayDraft = s.toString(); updateSend() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false }
        }
        editorRow.addWeighted(editor); editorRow.addView(send)
        root.addView(editorRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
        qualityPanel.addView(qualityLabel)
        qualityPanel.addView(label("切换预设会替换当前画质设置", 11f, muted))
        for (presets in listOf("高清" to Quality.DEFAULT, "均衡" to Quality.AUTO, "文字" to Quality.CLEAR, "流畅" to Quality.SMOOTH).chunked(2)) {
            val row = LinearLayout(service)
            for ((name, q) in presets) {
                val control = button("$name · ${q.fps}帧") { service.session.quality(q); render(CallService.state.value) }
                control.contentDescription = "画质预设$name"; presetButtons.add(q to control); row.addWeighted(control)
            }
            qualityPanel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        body.addView(qualityPanel); body.addView(feedback)
        actions.addWeighted(mute); actions.addWeighted(qualityButton); actions.addWeighted(stop)
        stop.setTextColor(Color.rgb(255,180,171)); root.addView(actions)
        root.isFocusableInTouchMode = true
        resize(update = false)
        return try {
            wm.addView(root, params); attached = true
            scope.launch { CallService.state.collect { render(it) } }; true
        } catch (_: Exception) { close(); false }
    }

    private fun atBottom() = messages.height - chatScroll.scrollY - chatScroll.height <= dp(24)
    private fun updateSend() { send.isEnabled = CallService.state.value.let { it.connected && it.chatReady } && editor.text.isNotBlank() }
    private fun sendMessage() {
        if (editor.text.isBlank()) return
        if (service.session.sendChat(editor.text.toString())) { editor.setText(""); finishEditing() }
    }
    private fun finishEditing() {
        service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
        editor.clearFocus(); params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        imeHeight = 0; resize()
    }
    private fun collapse(value: Boolean) {
        finishEditing(); collapsed = value
        bodyScroll.visibility = if (value) View.GONE else View.VISIBLE
        editorRow.visibility = bodyScroll.visibility; actions.visibility = bodyScroll.visibility
        fold.setLabel(if (value) "展开" else "收起")
        resize(); render(CallService.state.value)
        if (!value) chatScroll.post { if (attached) { chatScroll.fullScroll(View.FOCUS_DOWN); service.session.readChat() } }
    }
    fun resize(update: Boolean = true) {
        val bounds = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else android.graphics.Rect(0,0,service.resources.displayMetrics.widthPixels,service.resources.displayMetrics.heightPixels)
        params.width = minOf(dp(if (collapsed) 236 else 328), bounds.width()-dp(24)).coerceAtLeast(dp(180))
        params.height = if (collapsed) dp(64) else minOf(dp(460),bounds.height()-imeHeight-dp(64)).coerceAtLeast(dp(180))
        params.x = params.x.coerceIn(0,(bounds.width()-params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(24),(bounds.height()-imeHeight-params.height-dp(24)).coerceAtLeast(dp(24)))
        if (update && attached) runCatching { wm.updateViewLayout(root,params) }.onFailure { service.hideCallOverlay() }
    }
    private fun render(state: CallState) {
        if (!state.active || !state.sharing) { service.hideCallOverlay(); return }
        title.setLabel(if (state.chatUnread > 0) "${state.chatUnread} 条新消息" else if (state.connected) "共享通话" else "正在重连…")
        title.contentDescription = "${title.text}，拖动可移动${if (collapsed) "，点击展开" else ""}"
        mute.setLabel(if (state.muted) "开麦" else "静音"); mute.isEnabled = state.microphoneAvailable
        stop.isEnabled = !state.shareBusy; qualityButton.isEnabled = !state.shareBusy
        qualityLabel.setLabel("当前 ${state.quality.label}")
        presetButtons.forEach { (q, b) -> b.isEnabled = !state.shareBusy; b.setTextColor(if (state.quality == q) accent else ink) }
        feedback.setLabel(state.error.orEmpty()); feedback.visibility = if (state.error == null) View.GONE else View.VISIBLE
        updateSend()
        videoToggle.setLabel(if (state.floatingVideo) "隐藏视频" else "显示视频")
        val next = if (!collapsed && state.floatingVideo) when { state.remoteCameraOn -> state.remoteCamera; state.cameraOn -> state.localCamera; else -> null } else null
        videoLabel.setLabel(if (state.remoteCameraOn) "对方视频" else if (state.cameraOn) "本地预览" else "摄像头未开启")
        if (track !== next) {
            releaseVideo()
            if (next != null) service.session.rtc?.let { rtc ->
                val view = SurfaceViewRenderer(service)
                try {
                    view.init(rtc.egl.eglBaseContext, null); view.setMirror(false); view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    video.addView(view, FrameLayout.LayoutParams(-1,-1)); next.addSink(view); renderer = view; track = next
                } catch (_: Exception) { video.removeView(view); view.release() }
            }
        }
        video.visibility = if (next == null) View.GONE else View.VISIBLE
        if (revision != state.chatRevision) {
            val follow = atBottom() || state.chatMessages.lastOrNull()?.mine == true
            val oldY = chatScroll.scrollY
            messages.removeAllViews()
            if (state.chatMessages.isEmpty()) messages.addView(label("消息只保留在本次通话中", 13f, muted))
            for (message in state.chatMessages.takeLast(20)) {
                val text = label((if (message.mine) "你  " else "对方  ")+message.text, 13f, ink)
                text.setPadding(dp(10),dp(7),dp(10),dp(7)); text.background = shape(if (message.mine) surface else Color.rgb(34,42,57),8)
                messages.addView(text, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(4) })
            }
            revision = state.chatRevision
            chatScroll.post { if (attached) { if (follow) { chatScroll.fullScroll(View.FOCUS_DOWN); if (displaysMessages) service.session.readChat() } else chatScroll.scrollTo(0,oldY) } }
        }
        unread.setLabel("${state.chatUnread} 条新消息 · 查看")
        unread.visibility = if (state.chatUnread > 0 && !atBottom()) View.VISIBLE else View.GONE
    }
    private fun releaseVideo() {
        renderer?.let { view -> runCatching { track?.removeSink(view) }; video.removeView(view); view.release() }
        renderer = null; track = null
    }
    fun close() {
        scope.cancel()
        if (attached) { attached = false; service.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken,0); runCatching { wm.removeViewImmediate(root) } }
        releaseVideo()
    }
}
