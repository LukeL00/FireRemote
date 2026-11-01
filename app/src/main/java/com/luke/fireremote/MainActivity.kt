package com.luke.fireremote

import android.content.Context
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
import androidx.compose.ui.graphics.Color
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

class MainActivity : ComponentActivity() {
    private lateinit var client: FireTvClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = FireTvClient(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface)) {
                RemoteScreen(client)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        client.close()
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
            client.connect(ip).fold(
                onSuccess = { status = Status.Connected },
                onFailure = {
                    status = Status.Disconnected
                    toast("Couldn't connect. Check the IP, and accept the prompt on the TV.")
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppButton("YouTube", YouTubeRed, Color.White, Modifier.weight(1f)) {
                send { client.launchApp("YouTube", FireTvClient.Apps.YOUTUBE, "youtube") }
            }
            AppButton("NETFLIX", Color.Black, NetflixRed, Modifier.weight(1f)) {
                send { client.launchApp("Netflix", FireTvClient.Apps.NETFLIX, "netflix") }
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

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    big: Boolean = false,
    onClick: () -> Unit
) {
    val size = if (big) 76.dp else 64.dp
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (big) Accent else Surface,
            contentColor = if (big) Color.Black else tint
        )
    ) { Icon(icon, label, modifier = Modifier.size(if (big) 38.dp else 28.dp)) }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Surface, contentColor = Color.White)
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 16.sp)
    }
}

@Composable
private fun AppButton(label: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)
    ) {
        if (label == "YouTube") {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = if (label == "NETFLIX") 1.5.sp else 0.sp)
    }
}

@Composable
private fun DPad(onUp: () -> Unit, onDown: () -> Unit, onLeft: () -> Unit, onRight: () -> Unit, onOk: () -> Unit) {
    Box(
        Modifier.size(240.dp).clip(CircleShape).background(Surface),
        contentAlignment = Alignment.Center
    ) {
        val arrow = Modifier.size(72.dp)
        IconButton(onClick = onUp, modifier = arrow.align(Alignment.TopCenter)) {
            Icon(Icons.Default.KeyboardArrowUp, "Up", tint = Color.White, modifier = Modifier.size(44.dp))
        }
        IconButton(onClick = onDown, modifier = arrow.align(Alignment.BottomCenter)) {
            Icon(Icons.Default.KeyboardArrowDown, "Down", tint = Color.White, modifier = Modifier.size(44.dp))
        }
        IconButton(onClick = onLeft, modifier = arrow.align(Alignment.CenterStart)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", tint = Color.White, modifier = Modifier.size(44.dp))
        }
        IconButton(onClick = onRight, modifier = arrow.align(Alignment.CenterEnd)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Button(
            onClick = onOk,
            modifier = Modifier.size(96.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceHi, contentColor = Color.White)
        ) { Text("OK", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
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
