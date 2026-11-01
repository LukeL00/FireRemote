package com.luke.fireremote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.luke.fireremote.FireTvClient.Keys

private val Bg = Color(0xFF101114)
private val Surface = Color(0xFF1E2026)
private val SurfaceHi = Color(0xFF2B2D35)
private val Accent = Color(0xFFFF9900)
private val YouTubeRed = Color(0xFFFF0033)
private val NetflixRed = Color(0xFFE50914)
private val DisneyBlue = Color(0xFF0B2A6F)

class MainActivity : ComponentActivity() {
    private val client get() = (application as RemoteApp).client

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Lets the Quick Settings tile open the remote over the lock screen
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface)) {
                RemoteScreen(client)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Re-post the remote notification now that we're allowed to show it
        if ((application as RemoteApp).savedIp.isNotBlank()) RemoteService.start(this)
    }
}

private enum class Status { Disconnected, Connecting, Connected }

@Composable
private fun RemoteScreen(client: FireTvClient) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("remote", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var status by remember { mutableStateOf(Status.Disconnected) }
    var showSettings by remember { mutableStateOf(ip.isBlank()) }
    var showType by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun connect() {
        if (ip.isBlank()) { showSettings = true; return }
        status = Status.Connecting
        scope.launch {
            client.ensureConnected(ip).fold(
                onSuccess = {
                    status = Status.Connected
                    RemoteService.start(context)
                },
                onFailure = { e ->
                    status = Status.Disconnected
                    val reason = generateSequence(e) { it.cause }.last().let { "${it.javaClass.simpleName}: ${it.message}" }
                    Toast.makeText(context, "Couldn't connect to $ip\n$reason", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // Auto-connect on launch
    LaunchedEffect(Unit) { if (ip.isNotBlank()) connect() }

    fun send(block: suspend () -> Result<Unit>) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (status != Status.Connected) { connect(); return }
        scope.launch {
            block().onFailure {
                if (!client.isConnected) status = Status.Disconnected
                toast(it.message ?: "Something went wrong")
            }
        }
    }

    fun key(code: Int) = send { client.key(code) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .systemBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header: status + settings
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val dot = when (status) {
                Status.Connected -> Color(0xFF34C759)
                Status.Connecting -> Accent
                Status.Disconnected -> Color(0xFFFF453A)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(
                when (status) {
                    Status.Connected -> "Fire TV connected"
                    Status.Connecting -> "Connecting…"
                    Status.Disconnected -> "Not connected – tap to retry"
                },
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            if (status == Status.Disconnected) {
                IconButton(onClick = { connect() }) { Icon(Icons.Default.Refresh, "Reconnect", tint = Color.White) }
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
        }

        // Power / mute / keyboard
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Icons.Default.PowerSettingsNew, "Power", tint = Color(0xFFFF453A)) { key(Keys.POWER) }
            RoundButton(Icons.Default.Keyboard, "Type") { showType = true }
            RoundButton(Icons.Default.VolumeOff, "Mute") { key(Keys.MUTE) }
        }

        // App shortcuts
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppButton("YouTube", YouTubeRed, Color.White, Modifier.weight(1f)) {
                send { client.launchApp("YouTube", FireTvClient.Apps.YOUTUBE, "youtube") }
            }
            AppButton("NETFLIX", Color.Black, NetflixRed, Modifier.weight(1f)) {
                send { client.launchApp("Netflix", FireTvClient.Apps.NETFLIX, "netflix") }
            }
            AppButton("Disney+", DisneyBlue, Color.White, Modifier.weight(1f)) {
                send { client.launchApp("Disney+", FireTvClient.Apps.DISNEY, "disney") }
            }
        }

        // D-pad
        DPad(
            onUp = { key(Keys.UP) }, onDown = { key(Keys.DOWN) },
            onLeft = { key(Keys.LEFT) }, onRight = { key(Keys.RIGHT) },
            onOk = { key(Keys.OK) }
        )

        // Back / Home / Menu
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { key(Keys.BACK) }
            RoundButton(Icons.Default.Home, "Home") { key(Keys.HOME) }
            RoundButton(Icons.Default.Menu, "Menu") { key(Keys.MENU) }
        }

        // Playback
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(Icons.Default.FastRewind, "Rewind") { key(Keys.REWIND) }
            RoundButton(Icons.Default.PlayArrow, "Play/Pause", big = true) { key(Keys.PLAY_PAUSE) }
            RoundButton(Icons.Default.FastForward, "Fast forward") { key(Keys.FAST_FORWARD) }
        }

        // Volume
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(Icons.Default.VolumeDown, "Vol –", Modifier.weight(1f)) { key(Keys.VOL_DOWN) }
            PillButton(Icons.Default.VolumeUp, "Vol +", Modifier.weight(1f)) { key(Keys.VOL_UP) }
        }
    }

    if (showSettings) {
        SettingsDialog(
            current = ip,
            onDismiss = { if (ip.isNotBlank()) showSettings = false },
            onSave = {
                ip = it.trim()
                prefs.edit().putString("ip", ip).apply()
                showSettings = false
                connect()
            }
        )
    }

    if (showType) {
        TypeDialog(
            onDismiss = { showType = false },
            onSend = { text ->
                showType = false
                send { client.typeText(text) }
            }
        )
    }
}

/**
 * A button that fires the moment your finger touches it (not on release),
 * and optionally repeats while held - like a real remote.
 */
@Composable
private fun PressSurface(
    modifier: Modifier,
    shape: Shape,
    color: Color,
    repeat: Boolean = false,
    onPress: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val currentOnPress by rememberUpdatedState(onPress)
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) lerp(color, Color.White, 0.18f) else color)
            .pointerInput(repeat) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentOnPress()
                    val repeater = if (repeat) scope.launch {
                        delay(400)
                        while (true) { currentOnPress(); delay(110) }
                    } else null
                    waitForUpOrCancellation()
                    repeater?.cancel()
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    big: Boolean = false,
    repeat: Boolean = false,
    onClick: () -> Unit
) {
    val size = if (big) 76.dp else 64.dp
    PressSurface(Modifier.size(size), CircleShape, if (big) Accent else Surface, repeat, onClick) {
        Icon(icon, label, tint = if (big) Color.Black else tint, modifier = Modifier.size(if (big) 38.dp else 28.dp))
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    PressSurface(modifier.height(56.dp), RoundedCornerShape(28.dp), Surface, repeat = true, onPress = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 16.sp, color = Color.White)
        }
    }
}

@Composable
private fun AppButton(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    PressSurface(modifier.height(64.dp), RoundedCornerShape(16.dp), bg, onPress = onClick) {
        Text(
            label, color = fg, fontWeight = FontWeight.Black, fontSize = 17.sp, maxLines = 1, softWrap = false,
            letterSpacing = if (label == "NETFLIX") 1.sp else 0.sp
        )
    }
}

@Composable
private fun DPad(onUp: () -> Unit, onDown: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit, onOk: () -> Unit) {
    Box(
        Modifier.size(240.dp).clip(CircleShape).background(Surface),
        contentAlignment = Alignment.Center
    ) {
        val arrow = Modifier.size(76.dp)
        val iconMod = Modifier.size(44.dp)
        PressSurface(arrow.align(Alignment.TopCenter), CircleShape, Surface, repeat = true, onPress = onUp) {
            Icon(Icons.Default.KeyboardArrowUp, "Up", tint = Color.White, modifier = iconMod)
        }
        PressSurface(arrow.align(Alignment.BottomCenter), CircleShape, Surface, repeat = true, onPress = onDown) {
            Icon(Icons.Default.KeyboardArrowDown, "Down", tint = Color.White, modifier = iconMod)
        }
        PressSurface(arrow.align(Alignment.CenterStart), CircleShape, Surface, repeat = true, onPress = onLeft) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", tint = Color.White, modifier = iconMod)
        }
        PressSurface(arrow.align(Alignment.CenterEnd), CircleShape, Surface, repeat = true, onPress = onRight) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", tint = Color.White, modifier = iconMod)
        }
        PressSurface(Modifier.size(96.dp), CircleShape, SurfaceHi, onPress = onOk) {
            Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
private fun SettingsDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fire Stick IP address") },
        text = {
            Column {
                Text(
                    "On the Fire Stick: Settings → My Fire TV → About → Network. " +
                        "ADB Debugging must be on in Developer Options.",
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onSave(text) })
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Connect") } },
        dismissButton = { if (current.isNotBlank()) TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun TypeDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Type on TV") },
        text = {
            Column {
                Text("Select a search box on the TV first.", fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text) })
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
