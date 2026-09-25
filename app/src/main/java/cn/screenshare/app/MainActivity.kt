package cn.screenshare.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val callState by CallService.state.collectAsStateWithLifecycle()
            DisposableEffect(callState.active) {
                val bars = if (callState.active) SystemBarStyle.dark(0xFF1D2331.toInt())
                    else SystemBarStyle.light(0xFFFFF9F0.toInt(), 0xFF49392D.toInt())
                enableEdgeToEdge(statusBarStyle = bars,
                    navigationBarStyle = SystemBarStyle.light(0xFFFFF9F0.toInt(), 0xFF49392D.toInt()))
                onDispose { }
            }
            ScreenShareTheme(darkMode = callState.active) { ScreenShareApp() }
        }
    }
}
@Composable
private fun ScreenShareTheme(darkMode: Boolean, content: @Composable () -> Unit) {
    val light = lightColorScheme(primary = Color(0xFFA65C2B), onPrimary = Color.White, primaryContainer = Color(0xFFF1DCC6), onPrimaryContainer = Color(0xFF624327),
        secondaryContainer = Color(0xFFF1DCC6), onSecondaryContainer = Color(0xFF624327),
        background = Color(0xFFFFF9F0), onBackground = Color(0xFF49392D), surface = Color(0xFFFFF9F0), onSurface = Color(0xFF49392D), surfaceVariant = Color(0xFFF0E6D9), onSurfaceVariant = Color(0xFF786453), outline = Color(0xFF9B8875))
    val dark = darkColorScheme(primary = Color(0xFFF2D28D), onPrimary = Color(0xFF30291C), primaryContainer = Color(0xFF293345), onPrimaryContainer = Color(0xFFF7F0DF),
        secondaryContainer = Color(0xFF293345), onSecondaryContainer = Color(0xFFF7F0DF),
        background = Color(0xFF1D2331), onBackground = Color(0xFFF7F0DF), surface = Color(0xFF1D2331), onSurface = Color(0xFFF7F0DF), surfaceVariant = Color(0xFF293345), onSurfaceVariant = Color(0xFFB6C0CF), outline = Color(0xFF8D9AAF))
    MaterialTheme(colorScheme = if (darkMode) dark else light, content = content)
}

@Composable
private fun ScreenShareApp() {
    val context = LocalContext.current
    val state by CallService.state.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("connection", Context.MODE_PRIVATE) }
    var server by rememberSaveable { mutableStateOf(prefs.getString("server", "").orEmpty()) }
    var room by rememberSaveable { mutableStateOf("") }
    var settings by rememberSaveable { mutableStateOf(false) }
    var pendingRoom by rememberSaveable { mutableStateOf<String?>(null) }
    var permissionError by rememberSaveable { mutableStateOf<String?>(null) }
    var explainShare by rememberSaveable { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // Move the existing video surface between layouts so a static screen stays visible.
    val remoteScreen = remember { movableContentOf<VideoTrack?, Modifier> { track, modifier -> RemoteScreen(track, modifier) } }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun launchCall() {
        val intent = Intent(context, CallService::class.java).setAction(CallService.START).putExtra("server", server)
        pendingRoom?.let { intent.putExtra("room", it) }
        try { context.startForegroundService(intent) } catch (_: Exception) { permissionError = "无法启动通话，请确认麦克风权限后重试" }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchCall()
        else permissionError = "语音通话需要麦克风权限。请允许权限后重试，或在系统设置中开启。"
    }
    fun begin(target: String?) {
        if (server.isBlank()) { settings = true; return }
        if (target != null && !target.matches(Regex("[0-9]{8}"))) { permissionError = "请输入完整的 8 位房间号"; return }
        pendingRoom = target
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) needed += Manifest.permission.BLUETOOTH_CONNECT
        permissions.launch(needed.toTypedArray())
    }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && CallService.current != null) {
            context.startService(Intent(context, CallService::class.java).setAction(CallService.SHARE).putExtra("projection", result.data))
        } else if (state.active) scope.launch { snack.showSnackbar("未开始共享，语音通话继续") }
    }
    LaunchedEffect(state.active) { if (!state.active) fullscreen = false }
    BackHandler(fullscreen) { fullscreen = false }
    // Android 15 draws behind navigation bars; paint a stable light backing for system controls.
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF9F0)).navigationBarsPadding()) {
      Surface(Modifier.fillMaxSize()) {
        if (fullscreen && state.remoteSharing) {
            Box(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
                remoteScreen(state.remoteTrack, Modifier.fillMaxSize())
                FilledTonalButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("退出全屏") }
                Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { CallService.current?.session?.toggleMute() }) { Text(if (state.muted) "开启麦克风" else "静音") }
                    Button(onClick = { CallService.current?.session?.end(notice = "你已结束通话") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("挂断") }
                }
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("同屏", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!state.active) TextButton(onClick = { settings = true }) { Text("连接设置") }
                        else Text(state.status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            ) { insets ->
                Column(Modifier.padding(insets).consumeWindowInsets(insets).imePadding().fillMaxSize()) {
                    val error = permissionError ?: state.error
                    if (error != null) MessageBanner(error, true) { permissionError = null; CallService.current?.session?.dismissError() ?: run { CallService.state.value = state.copy(error = null) } }
                    state.notice?.let { MessageBanner(it, false) { CallService.state.value = state.copy(notice = null) } }
                    if (state.active) {
                        CallScreen(state, remoteScreen, onShare = { explainShare = true }, onFullscreen = { fullscreen = true }, onCopy = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("房间号", state.roomId))
                            scope.launch { snack.showSnackbar("房间号已复制") }
                        })
                    } else {
                        HomeScreen(room, { room = it.filter(Char::isDigit).take(8) }, server.isNotBlank(), { begin(null) }, { begin(room) }, { settings = true })
                    }
                }
            }
        }
    }
    }
    if (settings) ConnectionDialog(server, onDismiss = { settings = false }, onSave = {
        server = it; prefs.edit().putString("server", it).apply(); settings = false
    })
    if (explainShare) AlertDialog(
        onDismissRequest = { explainShare = false },
        title = { Text("开始共享屏幕？") },
        text = { Text("对方将看到你选择共享的内容，并听到允许采集的媒体声音。静音仅关闭麦克风。部分应用、受保护内容和通话声音无法共享。通知、密码和聊天消息可能出现在画面中。随时返回同屏即可停止共享；允许通知后，也可从通知栏停止。") },
        confirmButton = { TextButton(onClick = {
            explainShare = false
            capture.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { explainShare = false }) { Text("暂不共享") } }
    )
}

@Composable
private fun HomeScreen(room: String, onRoom: (String) -> Unit, configured: Boolean, create: () -> Unit, join: () -> Unit, configure: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("一起看，慢慢聊。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("共享屏幕，也分享陪伴。\n电脑和安卓，输入房间号就能连接。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Image(painterResource(R.drawable.puppies_home), contentDescription = "小白和小金毛靠在一起看电脑", modifier = Modifier.fillMaxWidth().height(132.dp))
        Button(create, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("创建房间", style = MaterialTheme.typography.titleMedium) }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("加入对方的房间", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(room, onRoom, Modifier.fillMaxWidth(), label = { Text("8 位房间号") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace))
            OutlinedButton(join, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = room.length == 8) { Text("加入通话") }
        }
        if (!configured) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("首次使用", style = MaterialTheme.typography.titleSmall)
                Text("先填写连接服务地址，两台设备使用同一个服务。", style = MaterialTheme.typography.bodyMedium)
                TextButton(configure, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("设置连接地址") }
            }
        }
        Text("共享需要你的系统授权。停止共享后，语音通话可以继续。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CallScreen(state: CallState, remoteScreen: @Composable (VideoTrack?, Modifier) -> Unit, onShare: () -> Unit, onFullscreen: () -> Unit, onCopy: () -> Unit) {
    val context = LocalContext.current
    val session = CallService.current?.session
    var routeMenu by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    if (qualityMenu) QualityDialog(state.quality, onDismiss = { qualityMenu = false }) {
        session?.quality(it); qualityMenu = false
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("房间号", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.roomId.ifEmpty { "···· ····" }.chunked(4).joinToString(" "), style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace)
            }
            TextButton(onCopy, enabled = state.roomId.isNotEmpty()) { Text("复制") }
        }
        if (state.remoteSharing) {
            Box(Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
                remoteScreen(state.remoteTrack, Modifier.fillMaxSize())
                FilledTonalButton(onFullscreen, Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Text("全屏观看") }
            }
            Text("双指缩放画面", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(24.dp).heightIn(min = 144.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!state.sharing) Image(painterResource(R.drawable.puppies_wait), contentDescription = "小白牵着小金毛，等待一起同屏", modifier = Modifier.fillMaxWidth().height(140.dp))
                    Text(when { state.sharing -> "正在共享你的屏幕"; state.connected -> "已经连上，可以说话了"; state.peerPresent -> "正在与对方连接"; else -> "等对方来，一起看" },
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(when { state.sharing -> "切换到你想展示的应用。停止共享不会结束语音。"; state.connected -> "点击下方开始共享，把手机画面展示给对方。"; state.peerPresent -> "正在建立音频和画面通道，请稍候。"; else -> "把上方的房间号发给对方。对方加入后即可开始语音和屏幕共享。" },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (state.sharing) TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("前往手机桌面") }
                }
            }
        }
        Text("自己共享目标：${state.quality.label} · 上限 ${state.quality.bitrate / 1_000_000.0} Mbps", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.stats.isNotEmpty()) Text(state.stats, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.hasTurn && state.roomId.isNotEmpty()) Text("当前服务未配置中转，跨网络连接可能失败。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
        Button(onClick = { if (state.sharing) session?.stopSharing() else onShare() },
            enabled = state.sharing || (state.connected && !state.remoteSharing && !state.shareBusy), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(when { state.shareBusy -> "正在更新共享…"; state.sharing -> "停止共享"; state.remoteSharing -> "对方正在共享"; else -> "开始共享屏幕" })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { session?.toggleMute() }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), enabled = state.roomId.isNotEmpty()) {
                Text(if (state.muted) "开启麦克风" else "静音")
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { routeMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = state.routes.isNotEmpty()) {
                    Text(state.routes.firstOrNull { it.id == state.selectedRoute }?.label ?: "音频设备")
                }
                DropdownMenu(routeMenu, { routeMenu = false }) {
                    state.routes.forEach { route -> DropdownMenuItem(text = { Text(route.label) }, onClick = { session?.selectRoute(route.id); routeMenu = false }) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { qualityMenu = true }, enabled = !state.shareBusy) { Text("画质设置") }
            TextButton(onClick = { session?.end(notice = "你已结束通话") }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("挂断通话") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RemoteScreen(track: VideoTrack?, modifier: Modifier) {
    val context = LocalContext.current
    val rtc = CallService.current?.session?.rtc
    var videoAspect by remember(track) { mutableFloatStateOf(9f / 16f) }
    val renderer = remember(rtc) { if (rtc == null) null else SurfaceViewRenderer(context).apply {
        init(rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() = Unit
            override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                post { videoAspect = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width }
            }
        })
        // Let the surface follow the Compose layout; fixed buffers can retain the initial portrait crop.
        setEnableHardwareScaler(false)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        setMirror(false)
    } }
    var zoom by remember(track) { mutableFloatStateOf(1f) }
    DisposableEffect(track, renderer) {
        if (renderer != null) track?.addSink(renderer)
        onDispose { if (renderer != null) runCatching { track?.removeSink(renderer) } }
    }
    DisposableEffect(renderer) { onDispose { renderer?.release() } }
    Box(modifier.clip(RoundedCornerShape(0.dp)).pointerInput(track) {
        detectTransformGestures { _, _, change, _ -> zoom = (zoom * change).coerceIn(1f, 3f) }
    }, contentAlignment = Alignment.Center) {
        if (renderer != null && track != null) AndroidView(factory = { renderer }, modifier = Modifier.aspectRatio(videoAspect), update = { it.scaleX = zoom; it.scaleY = zoom })
        else Text("等待共享画面…", color = Color.White)
    }
}

@Composable
private fun MessageBanner(message: String, error: Boolean, dismiss: () -> Unit) {
    Surface(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(dismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun ConnectionDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by rememberSaveable { mutableStateOf(current) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接设置") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("安卓使用此服务地址。电脑通过对应网页进入同一房间。")
            OutlinedTextField(value, { value = it; error = null }, label = { Text("服务器地址") }, placeholder = { Text("https://share.example.com") }, singleLine = true,
                isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), supportingText = { error?.let { Text(it) } })
            if (BuildConfig.DEBUG) Text("此测试版支持局域网 HTTP 地址。请勿在公共网络中使用明文连接。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = {
        try { onSave(ServerAddress.normalize(value, BuildConfig.DEBUG)) } catch (e: IllegalArgumentException) { error = e.message }
    }) { Text("保存") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}


@Composable
private fun QualityDialog(current: Quality, onDismiss: () -> Unit, onSave: (Quality) -> Unit) {
    var longEdge by remember { mutableStateOf(current.longEdge.toString()) }
    var shortEdge by remember { mutableStateOf(current.shortEdge.toString()) }
    var fps by remember { mutableStateOf(current.fps.toString()) }
    var mbps by remember { mutableStateOf((current.bitrate / 1_000_000.0).toString()) }
    var priority by remember { mutableStateOf(current.priority) }
    var error by remember { mutableStateOf<String?>(null) }
    fun fill(q: Quality) { longEdge=q.longEdge.toString(); shortEdge=q.shortEdge.toString(); fps=q.fps.toString(); mbps=(q.bitrate/1_000_000.0).toString(); priority=q.priority; error=null }
    AlertDialog(onDismissRequest=onDismiss, title={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("精确调整画质")
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
    } }, text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("只调整自己共享的画面，语音保持连接。", style=MaterialTheme.typography.bodySmall)
            Quality.presets.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { (name,q) -> OutlinedButton(onClick={fill(q)},modifier=Modifier.weight(1f)) { Text(name) } }
                }
            }
            Text("分辨率上限", style=MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160).forEach { (w,h) ->
                    TextButton(onClick={longEdge=w.toString();shortEdge=h.toString()}) { Text(if(h==2160) "4K" else "${h}p") }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(longEdge,{longEdge=it},Modifier.weight(1f),label={Text("长边像素")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(shortEdge,{shortEdge=it},Modifier.weight(1f),label={Text("短边像素")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            OutlinedTextField(fps,{fps=it},Modifier.fillMaxWidth(),label={Text("目标帧率：1–60 FPS")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(15,24,30,60).forEach { value -> TextButton(onClick={fps=value.toString()}) { Text("${value} 帧") } }
            }
            OutlinedTextField(mbps,{mbps=it},Modifier.fillMaxWidth(),label={Text("码率上限：0.5–80 Mbps")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            Text("资源不足时",style=MaterialTheme.typography.titleSmall)
            VideoPriority.entries.forEach { option ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=priority==option,onClick={priority=option})
                    TextButton(onClick={priority=option}) { Text(option.label) }
                }
            }
            Text("尺寸按屏幕比例适配竖屏，不放大低分辨率源。参数为目标上限，4K / 60 帧受屏幕、编码器、网络限制；静止画面可能降低帧率，实际发送和接收数据见通话页。清晰优先允许降帧，帧率优先允许降分辨率；拥塞控制始终生效。",style=MaterialTheme.typography.bodySmall)
        }
    }, confirmButton={ TextButton(onClick={
        try {
            val rate=mbps.toDoubleOrNull()
            require(rate!=null && rate.isFinite() && rate in 0.5..80.0) { "码率上限需为 0.5–80 Mbps" }
            onSave(Quality(longEdge.toIntOrNull() ?: 0,shortEdge.toIntOrNull() ?: 0,fps.toIntOrNull() ?: 0,(rate*1_000_000).toInt(),priority))
        } catch(e: IllegalArgumentException) { error=e.message }
    }) {Text("应用")} },dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
