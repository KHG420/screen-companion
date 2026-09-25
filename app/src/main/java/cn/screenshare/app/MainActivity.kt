package cn.screenshare.app

import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.util.Rational
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
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
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

class MainActivity : ComponentActivity() {
    private var pip by mutableStateOf(false)
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }
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
            ScreenShareTheme(darkMode = callState.active) { ScreenShareApp(pip) }
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
private fun ScreenShareApp(pip: Boolean) {
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
    var remoteAspect by remember { mutableFloatStateOf(9f / 16f) }
    var inviteOpen by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(state.remoteSharing, state.remoteCameraOn, view) {
        view.keepScreenOn = state.remoteSharing || state.remoteCameraOn
        onDispose { view.keepScreenOn = false }
    }
    // Keep the video at one composition location when toggling fullscreen or PiP.
    val remoteScreen = remember { movableContentOf<VideoTrack?, Modifier> { track, modifier ->
        RemoteScreen(track, modifier, onAspectChanged = { remoteAspect = it })
    } }
    if (fullscreen && state.remoteSharing && !pip) {
        val activity = context as Activity
        DisposableEffect(activity) {
            val previousOrientation = activity.requestedOrientation
            val controller = WindowCompat.getInsetsController(activity.window, view)
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                activity.requestedOrientation = previousOrientation
                controller.systemBarsBehavior = previousBehavior
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        SideEffect {
            // Explicit fullscreen follows the source even when the phone's auto-rotate is off.
            val orientation = if (remoteAspect > 1f) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
        }
    }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val callUi = rememberSaveableStateHolder()
    fun launchCall() {
        val intent = Intent(context, CallService::class.java).setAction(CallService.START).putExtra("server", server)
        pendingRoom?.let { intent.putExtra("room", it) }
        try { context.startForegroundService(intent) } catch (_: Exception) { permissionError = "无法启动通话，请确认麦克风权限后重试" }
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) CallService.current?.session?.toggleMute()
        else permissionError = "暂未开启麦克风，你仍可观看、听声音和聊天。需要说话时可再次开启。"
    }
    val shareAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) CallService.current?.session?.prepareSharingAudio()
        else permissionError = "未允许录音，本次只共享画面。需要系统声音时，请允许麦克风权限后重新共享。"
        explainShare = true
    }
    fun toggleMicrophone() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) CallService.current?.session?.toggleMute()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun begin(target: String?) {
        if (server.isBlank()) { settings = true; return }
        if (target != null && !target.matches(Regex("[0-9]{8}"))) { permissionError = "请输入完整的 8 位房间号"; return }
        pendingRoom = target
        launchCall()
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) CallService.current?.session?.toggleCamera()
        else permissionError = "未开启摄像头。请在系统设置中允许摄像头权限，语音和屏幕共享仍可使用。"
    }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null && CallService.current != null) {
            context.startService(Intent(context, CallService::class.java).setAction(CallService.SHARE).putExtra("projection", result.data))
        } else if (state.active) scope.launch { snack.showSnackbar("未开始共享，语音通话继续") }
    }
    LaunchedEffect(state.active) { if (!state.active) { fullscreen = false; callUi.removeState("call") } }
    LaunchedEffect(state.active, state.remoteSharing, pip) {
        if (pip && (!state.active || !state.remoteSharing)) (context as Activity).moveTaskToBack(true)
    }
    BackHandler(fullscreen) { fullscreen = false }
    val immersive = state.remoteSharing && (fullscreen || pip)
    // Android 15 draws behind navigation bars; paint a stable light backing for system controls.
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF9F0)).navigationBarsPadding().displayCutoutPadding()) {
      Surface(Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) },
                topBar = {
                    if (!immersive) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (!state.active) TextButton(onClick = { settings = true }) { Text("连接设置") }
                        else Text(state.status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            ) { insets ->
                Column(Modifier.padding(insets).consumeWindowInsets(insets).imePadding().fillMaxSize()) {
                    val error = permissionError ?: state.error
                    if (error != null && !immersive) MessageBanner(error, true) { permissionError = null; CallService.current?.session?.dismissError() ?: run { CallService.state.value = state.copy(error = null) } }
                    state.notice?.takeIf { !immersive }?.let { MessageBanner(it, false) { CallService.state.value = state.copy(notice = null) } }
                    if (state.active) {
                      callUi.SaveableStateProvider("call") {
                        CallScreen(state, remoteScreen, fullscreen = fullscreen, pip = pip, onExitFullscreen = { fullscreen = false }, onCamera = {
                            if (state.cameraOn || context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                                CallService.current?.session?.toggleCamera()
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        }, onMute = { toggleMicrophone() }, onShare = {
                            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) explainShare = true
                            else shareAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }, onFullscreen = { fullscreen = true }, onCopy = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("房间号", state.roomId))
                            scope.launch { snack.showSnackbar("房间号已复制") }
                        }, onInvite = {
                            val link = java.net.URI(server).let { uri ->
                                java.net.URI(uri.scheme, null, uri.host, uri.port, "/screenshare/", null, "room=${state.roomId}").toASCIIString()
                            }
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("邀请链接", link))
                            scope.launch { snack.showSnackbar("邀请链接已复制，电脑打开即可加入；安卓可粘贴邀请链接") }
                        })
                      }
                    } else {
                        HomeScreen(room, { room = it.filter(Char::isDigit).take(8) }, server.isNotBlank(), { begin(null) }, { begin(room) }, { settings = true }, { inviteOpen = true })
                    }
                }
            }
    }
    }
    if (settings) ConnectionDialog(server, onDismiss = { settings = false }, onSave = {
        server = it; prefs.edit().putString("server", it).apply(); settings = false
    })
    if (inviteOpen) InviteDialog(onDismiss = { inviteOpen = false }) { address, code ->
        server = address; room = code; prefs.edit().putString("server", address).apply(); inviteOpen = false
        scope.launch { snack.showSnackbar("邀请已填写，点击加入通话") }
    }
    if (explainShare) AlertDialog(
        onDismissRequest = { explainShare = false },
        title = { Text("开始共享屏幕？") },
        text = { Text("对方将看到你选择共享的内容，并听到允许采集的媒体声音。静音仅关闭麦克风。部分应用、受保护内容和通话声音无法共享。通知、密码和聊天消息可能出现在画面中。随时返回同屏搭子即可停止共享；允许通知后，也可从通知栏停止。") },
        confirmButton = { TextButton(onClick = {
            explainShare = false
            capture.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }) { Text("继续") } },
        dismissButton = { TextButton(onClick = { explainShare = false }) { Text("暂不共享") } }
    )
}

@Composable
private fun HomeScreen(room: String, onRoom: (String) -> Unit, configured: Boolean, create: () -> Unit, join: () -> Unit, configure: () -> Unit, invite: () -> Unit) {
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
            TextButton(invite, Modifier.fillMaxWidth()) { Text("粘贴邀请链接") }
        }
        if (!configured) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("首次使用", style = MaterialTheme.typography.titleSmall)
                Text("先填写连接服务地址，两台设备使用同一个服务。", style = MaterialTheme.typography.bodyMedium)
                TextButton(configure, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("设置连接地址") }
            }
        }
        Text("暂不开麦，也能观看、听声音和聊天。共享需要你的系统授权。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallScreen(state: CallState, remoteScreen: @Composable (VideoTrack?, Modifier) -> Unit, fullscreen: Boolean, pip: Boolean, onExitFullscreen: () -> Unit, onCamera: () -> Unit, onMute: () -> Unit, onShare: () -> Unit, onFullscreen: () -> Unit, onCopy: () -> Unit, onInvite: () -> Unit) {
    val context = LocalContext.current
    val session = CallService.current?.session
    val scope = rememberCoroutineScope()
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var moreOpen by remember { mutableStateOf(false) }
    var qualityMenu by remember { mutableStateOf(false) }
    var camerasHidden by rememberSaveable { mutableStateOf(false) }
    val unread = state.chatUnread
    var followMessages by remember { mutableStateOf(true) }
    val immersive = state.remoteSharing && (fullscreen || pip)
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(fullscreen, interaction) { controlsVisible = true; if (fullscreen) { delay(4000); controlsVisible = false } }
    val chatScroll = rememberLazyListState()
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { session?.refreshRoutes() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(chatScroll) {
        snapshotFlow { chatScroll.isScrollInProgress to chatScroll.canScrollForward }.collect { (scrolling, canForward) ->
            if (scrolling) followMessages = !canForward
            if (!canForward && chatOpen) session?.readChat()
        }
    }
    LaunchedEffect(state.chatRevision, chatOpen) {
        if (chatOpen && (followMessages || state.chatMessages.lastOrNull()?.mine == true)) {
            if (state.chatMessages.isNotEmpty()) chatScroll.scrollToItem(state.chatMessages.lastIndex)
            session?.readChat(); followMessages = true
        }
    }
    if (chatOpen) ModalBottomSheet(onDismissRequest = { chatOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("房间聊天", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { chatOpen = false }) { Text("收起") }
            }
            Text("仅保留本次通话最近 200 条消息。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = chatScroll, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.chatMessages.isEmpty()) item { Text("发一句问候吧。") }
                items(state.chatMessages) { message ->
                    Column {
                        Text(if (message.mine) "你" else "对方", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        Text(message.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (unread > 0) TextButton(onClick = { followMessages = true; session?.readChat(); scope.launch { chatScroll.animateScrollToItem(state.chatMessages.lastIndex.coerceAtLeast(0)) } }) { Text("$unread 条新消息 · 回到底部") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { if (it.length <= 2000) draft = it }, label = { Text("消息 · 最多 2000 字符") }, modifier = Modifier.weight(1f), maxLines = 3)
                Button(onClick = { if (session?.sendChat(draft) == true) draft = "" }, enabled = state.connected && state.chatReady && draft.isNotBlank()) { Text("发送") }
            }
            if (!state.connected || !state.chatReady) Text("文字通道正在连接…", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
    if (moreOpen) ModalBottomSheet(onDismissRequest = { moreOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("画质、声音与连接", style = MaterialTheme.typography.titleLarge)
            Text("自己共享目标：${state.quality.label} · 上限 ${state.quality.bitrate / 1_000_000.0} Mbps", style = MaterialTheme.typography.bodyMedium)
            Text(state.quality.controlLabel, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { moreOpen = false; qualityMenu = true }, enabled = !state.shareBusy) { Text("精确调整画质") }
            Text("声音输出", style = MaterialTheme.typography.titleSmall)
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                TextButton(onClick = { bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("允许使用蓝牙耳机") }
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                TextButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("允许通话通知") }
            state.routes.forEach { route ->
                TextButton(onClick = { session?.selectRoute(route.id) }) { Text(route.label + if (route.id == state.selectedRoute) " · 正在使用" else "") }
            }
            if (state.sharing) {
                Text(if (state.systemAudio) (if (state.systemMuted) "共享声音已关闭，麦克风独立控制" else "正在共享系统声音，麦克风独立控制") else "当前未采集系统声音；部分应用禁止声音采集", style = MaterialTheme.typography.bodySmall)
                if (state.systemAudio) OutlinedButton(onClick = { session?.toggleSystemAudio() }) { Text(if (state.systemMuted) "开启共享声音" else "关闭共享声音") }
            }
            if (state.cameraOn || state.remoteCameraOn) TextButton(onClick = { camerasHidden = !camerasHidden }) { Text(if (camerasHidden) "显示摄像头画面" else "收起摄像头画面") }
            if (state.cameraOn) TextButton(onClick = { session?.switchCamera() }) { Text("切换摄像头") }
            if (state.remoteSharing && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) TextButton(onClick = {
                moreOpen = false
                val entered = runCatching { (context as Activity).enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }.getOrDefault(false)
                if (!entered) CallService.state.value = CallService.state.value.copy(error = "系统未允许小窗观看，通话继续。可在系统设置中允许画中画。")
            }) { Text("小窗观看") }
            HorizontalDivider()
            Text("连接详情", style = MaterialTheme.typography.titleSmall)
            Text(state.stats.ifEmpty { "正在测量…" }, style = MaterialTheme.typography.bodySmall)
            if (!state.hasTurn) Text("当前服务未配置中转，跨网络连接可能失败。", style = MaterialTheme.typography.bodySmall)
            TextButton(onInvite, enabled = state.roomId.isNotEmpty()) { Text("复制邀请链接") }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (qualityMenu) QualityDialog(state.quality, onDismiss = { qualityMenu = false }) { session?.quality(it); qualityMenu = false }
    Column(Modifier.fillMaxSize().padding(horizontal = if (immersive) 0.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (immersive) 0.dp else 6.dp)) {
        if (!immersive) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("房间 " + state.roomId.ifEmpty { "········" }.chunked(4).joinToString(" "), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
            TextButton(onCopy, enabled = state.roomId.isNotEmpty()) { Text("复制") }
            TextButton(onInvite, enabled = state.roomId.isNotEmpty()) { Text("邀请") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(if (immersive) 0.dp else 12.dp)).background(Color.Black)) {
            if (state.remoteSharing) {
                remoteScreen(state.remoteTrack, Modifier.fillMaxSize())
                if (!pip) {
                    if (!fullscreen) FilledTonalButton(onFullscreen, Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Text("全屏观看") }
                    else if (controlsVisible) {
                        FilledTonalButton(onExitFullscreen, Modifier.align(Alignment.TopEnd).padding(16.dp)) { Text("退出全屏") }
                        Row(Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FilledTonalButton(onClick = { onMute(); interaction++ }) { Text(if (state.muted) "开启麦克风" else "静音") }
                            Button(onClick = { session?.end(notice = "你已结束通话") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("挂断") }
                        }
                    } else TextButton(onClick = { interaction++ }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("显示操作") }
                }
                if (!state.connected) Surface(Modifier.align(Alignment.TopStart).padding(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                    Text("正在恢复画面和声音…", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            } else if (state.sharing || (!state.cameraOn && !state.remoteCameraOn) || camerasHidden) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.Center) {
                    if (!state.sharing) Image(painterResource(R.drawable.puppies_wait), contentDescription = "小白牵着小金毛，等待一起同屏", modifier = Modifier.fillMaxWidth().height(120.dp))
                    Text(when { state.sharing -> "正在共享你的屏幕"; state.connected -> "已经连上啦"; state.peerPresent -> "正在与对方连接"; else -> "等对方来，一起看" }, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text(when { state.sharing -> "切换到你想展示的应用。停止共享不会结束通话。"; state.connected && state.muted -> "可以听对方说话，也可以开启麦克风。"; state.connected -> "可以说话了。选一个屏幕，一起看。"; else -> "把房间号或邀请链接发给对方。" }, style = MaterialTheme.typography.bodyMedium)
                    if (state.sharing) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("前往手机桌面") }
                }
            }
            if (!immersive && !camerasHidden && (state.cameraOn || state.remoteCameraOn)) {
                val inset = state.remoteSharing || state.sharing
                Row(Modifier.align(if (inset) Alignment.TopEnd else Alignment.Center).then(if (inset) Modifier.widthIn(max = 208.dp).padding(8.dp) else Modifier.fillMaxWidth().padding(12.dp)), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.remoteCameraOn) Column(Modifier.weight(1f)) {
                        RemoteScreen(state.remoteCamera, Modifier.fillMaxWidth().height(if (inset) 90.dp else 200.dp).background(Color.Black))
                        Text("对方", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                    if (state.cameraOn) Column(Modifier.weight(1f)) {
                        RemoteScreen(state.localCamera, Modifier.fillMaxWidth().height(if (inset) 90.dp else 200.dp).background(Color.Black))
                        Text("你 · 本地预览", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    }
                }
            }
        }
        if (!immersive) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { if (state.sharing) session?.stopSharing() else onShare() }, enabled = !state.shareBusy && (state.sharing || (state.connected && !state.remoteSharing)), modifier = Modifier.weight(1.25f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                Text(when { state.shareBusy -> "准备中…"; state.sharing -> "停止共享"; state.remoteSharing -> "对方共享中"; else -> "共享屏幕" })
            }
            OutlinedButton(onMute, Modifier.weight(1f), enabled = state.microphoneAvailable || state.connected, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp)) { Text(if (!state.microphoneAvailable) "开启麦克风" else if (state.muted) "取消静音" else "静音") }
            Button(onClick = { session?.end(notice = "你已结束通话") }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) { Text("挂断") }
        }
        if (!immersive) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onCamera, enabled = state.cameraOn || (state.connected && state.chatReady)) { Text(if (state.cameraOn) "关闭摄像头" else "摄像头") }
            TextButton(onClick = { followMessages = true; chatOpen = true }) { Text(if (unread > 0) "聊天 · $unread" else "聊天") }
            TextButton(onClick = { moreOpen = true }) { Text("画质与声音") }
        }
    }
}

@Composable
private fun RemoteScreen(track: VideoTrack?, modifier: Modifier, onAspectChanged: (Float) -> Unit = {}) {
    val context = LocalContext.current
    val rtc = CallService.current?.session?.rtc
    // The callback and its aspect state have the same lifetime as the renderer.
    // Renegotiation can replace a Java VideoTrack wrapper without replacing this renderer.
    var videoAspect by remember(rtc) { mutableFloatStateOf(9f / 16f) }
    LaunchedEffect(videoAspect) { onAspectChanged(videoAspect) }
    var firstFrame by remember(rtc) { mutableStateOf(false) }
    val renderer = remember(rtc) { if (rtc == null) null else SurfaceViewRenderer(context).apply {
        init(rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
            override fun onFirstFrameRendered() { post { firstFrame = true } }
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
    var pan by remember(track) { mutableStateOf(Offset.Zero) }
    var bounds by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(videoAspect, bounds) {
        // A zoom/pan from the previous viewport must not crop a newly rotated screen.
        zoom = 1f
        pan = Offset.Zero
    }
    DisposableEffect(track, renderer) {
        if (renderer != null) track?.addSink(renderer)
        onDispose { if (renderer != null) runCatching { track?.removeSink(renderer) } }
    }
    DisposableEffect(renderer) { onDispose { renderer?.release() } }
    Box(modifier.clip(RoundedCornerShape(0.dp)).onSizeChanged { bounds = it }.pointerInput(track) {
        detectTapGestures(onDoubleTap = { zoom = 1f; pan = Offset.Zero })
    }.pointerInput(track, bounds) {
        detectTransformGestures { _, move, change, _ ->
            zoom = (zoom * change).coerceIn(1f, 3f)
            val maxX = bounds.width * (zoom - 1f) / 2f
            val maxY = bounds.height * (zoom - 1f) / 2f
            pan = Offset((pan.x + move.x).coerceIn(-maxX, maxX), (pan.y + move.y).coerceIn(-maxY, maxY))
        }
    }, contentAlignment = Alignment.Center) {
        if (renderer != null && track != null) AndroidView(factory = { renderer }, modifier = Modifier.aspectRatio(videoAspect), update = { it.scaleX = zoom; it.scaleY = zoom; it.translationX = pan.x; it.translationY = pan.y })
        if (!firstFrame) Text("等待首帧画面…", color = Color.White, modifier = Modifier.background(Color(0xFF293345)).padding(8.dp))
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
private fun InviteDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var value by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("粘贴邀请链接") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("填写对方从同屏搭子复制的链接，会带入服务地址和房间号。")
            OutlinedTextField(value, { value = it; error = null }, label = { Text("邀请链接") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, isError = error != null, supportingText = { error?.let { Text(it) } })
        }
    }, confirmButton = { TextButton(onClick = {
        try { val (address, code) = ServerAddress.invitation(value, BuildConfig.DEBUG); onSave(address, code) }
        catch (e: IllegalArgumentException) { error = e.message }
    }, enabled = value.isNotBlank()) { Text("填写邀请") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
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
    var resolutionLocked by remember { mutableStateOf(current.resolutionLocked) }
    var fpsLocked by remember { mutableStateOf(current.fpsLocked) }
    var error by remember { mutableStateOf<String?>(null) }
    fun fill(q: Quality) { longEdge=q.longEdge.toString(); shortEdge=q.shortEdge.toString(); fps=q.fps.toString(); mbps=(q.bitrate/1_000_000.0).toString(); priority=q.priority; resolutionLocked=q.resolutionLocked; fpsLocked=q.fpsLocked; error=null }
    AlertDialog(onDismissRequest=onDismiss, title={ Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("精确调整画质")
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
    } }, text={
        Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("手动修改尺寸或帧率会锁定该项，自动策略不能覆盖。预设会重新填写参数与锁定状态。", style=MaterialTheme.typography.bodySmall)
            Quality.presets.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { (name,q) -> OutlinedButton(onClick={fill(q)},modifier=Modifier.weight(1f)) { Text(name) } }
                }
            }
            Text("分辨率上限", style=MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(1280 to 720,1920 to 1080,2560 to 1440,3840 to 2160).forEach { (w,h) ->
                    TextButton(onClick={longEdge=w.toString();shortEdge=h.toString();resolutionLocked=true}) { Text(if(h==2160) "4K" else "${h}p") }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(longEdge,{longEdge=it;resolutionLocked=true},Modifier.weight(1f),label={Text("长边像素")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                OutlinedTextField(shortEdge,{shortEdge=it;resolutionLocked=true},Modifier.weight(1f),label={Text("短边像素")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            }
            OutlinedTextField(fps,{fps=it;fpsLocked=true},Modifier.fillMaxWidth(),label={Text("目标帧率：1–60 FPS")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(15,24,30,60).forEach { value -> TextButton(onClick={fps=value.toString();fpsLocked=true}) { Text("${value} 帧") } }
            }
            OutlinedTextField(mbps,{mbps=it},Modifier.fillMaxWidth(),label={Text("码率上限：0.5–80 Mbps")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=resolutionLocked,onCheckedChange={resolutionLocked=it})
                TextButton(onClick={resolutionLocked=!resolutionLocked}) { Text("锁定分辨率") }
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(checked=fpsLocked,onCheckedChange={fpsLocked=it})
                TextButton(onClick={fpsLocked=!fpsLocked}) { Text("锁定帧率目标") }
            }
            Text(if (resolutionLocked || fpsLocked) "手动锁定优先；取消锁定才允许自动调整该项" else "未锁定参数的自动策略",style=MaterialTheme.typography.bodySmall)
            VideoPriority.entries.forEach { option ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=priority==option,onClick={priority=option},enabled=!resolutionLocked && !fpsLocked)
                    TextButton(onClick={priority=option},enabled=!resolutionLocked && !fpsLocked) { Text(option.label) }
                }
            }
            Text("尺寸按屏幕比例适配竖屏，不放大低分辨率源。参数为目标上限，4K / 60 帧受屏幕、编码器、网络限制；静止画面可能降低帧率，实际发送和接收数据见通话页。锁定项不主动降档，但不能保证设备与网络始终达到目标；码率仍是上限，拥塞控制始终生效。",style=MaterialTheme.typography.bodySmall)
        }
    }, confirmButton={ TextButton(onClick={
        try {
            val rate=mbps.toDoubleOrNull()
            require(rate!=null && rate.isFinite() && rate in 0.5..80.0) { "码率上限需为 0.5–80 Mbps" }
            onSave(Quality(longEdge.toIntOrNull() ?: 0,shortEdge.toIntOrNull() ?: 0,fps.toIntOrNull() ?: 0,(rate*1_000_000).toInt(),priority,resolutionLocked,fpsLocked))
        } catch(e: IllegalArgumentException) { error=e.message }
    }) {Text("应用")} },dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}
