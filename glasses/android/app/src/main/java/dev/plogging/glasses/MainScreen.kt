package dev.plogging.glasses

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val Night = Color(0xFF08110E)
private val NightSurface = Color(0xE614201A)
private val NightSurfaceStrong = Color(0xF20D1914)
private val Leaf = Color(0xFFC4F36B)
private val Mist = Color(0xFFE8F0E9)
private val Moss = Color(0xFF9AAA9E)
private val Coral = Color(0xFFFF8067)

@Composable
fun MainScreen(
    viewModel: AppViewModel,
    onStart: () -> Unit,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val frame by viewModel.frame.collectAsStateWithLifecycle()
  val displayStatus by viewModel.displayStatus.collectAsStateWithLifecycle()
  val displayPreview by viewModel.displayPreview.collectAsStateWithLifecycle()
  var menuOpen by remember { mutableStateOf(false) }
  BackHandler(enabled = menuOpen) { menuOpen = false }

  BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Night)) {
    val landscape = maxWidth > maxHeight

    if (frame != null) {
      FrameView(
          frame!!,
          state.detections,
          state.hands,
          fitInside = landscape,
          modifier = Modifier.fillMaxSize(),
      )
    } else {
      EmptyViewfinder(state.isRunning, state.needsRegistration)
    }

    // Keep the camera legible while giving the floating HUD enough contrast.
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.62f),
                0.26f to Color.Transparent,
                0.62f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.76f),
            )
        )
    )

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
      HudTopBar(state = state, onMenu = { menuOpen = !menuOpen }, onRecord = viewModel::toggleRecording)
      state.recordingMessage?.let { message ->
        Text(message, color = Leaf, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
      }
      PickBanner(state.lastPick)
      VoiceBanner(state.voiceLine)
      displayStatus?.let { GlassesDisplay(it, displayPreview, Modifier.padding(top = 10.dp)) }
      Spacer(Modifier.weight(1f))
      if (landscape) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
          DetectionHud(state, hasFrame = frame != null, Modifier.weight(1f))
          PrimaryAction(
              state = state,
              onStart = onStart,
              onStop = viewModel::stop,
              onConnect = onConnectGlasses,
              modifier = Modifier.width(176.dp),
          )
        }
      } else {
        DetectionHud(state, hasFrame = frame != null)
        Spacer(Modifier.height(10.dp))
        PrimaryAction(
            state = state,
            onStart = onStart,
            onStop = viewModel::stop,
            onConnect = onConnectGlasses,
            modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    if (menuOpen) {
      Box(
          Modifier.fillMaxSize()
              .background(Color.Black.copy(alpha = 0.42f))
              .clickable { menuOpen = false }
      )
      MenuPanel(
          state = state,
          onSelectSource = viewModel::select,
          onApiUrl = viewModel::setApiUrl,
          onStart = onStart,
          onStop = viewModel::stop,
          onConnect = onConnectGlasses,
          onDisconnect = onDisconnectGlasses,
          onClose = { menuOpen = false },
          modifier = Modifier.align(Alignment.TopEnd).padding(top = 76.dp, end = 18.dp),
      )
    }
  }

  state.errorMessage?.let { message ->
    AlertDialog(
        onDismissRequest = viewModel::dismissError,
        confirmButton = { TextButton(onClick = viewModel::dismissError) { Text("OK") } },
        title = { Text("Something went wrong") },
        text = { Text(message) },
    )
  }
}

@Composable
private fun EmptyViewfinder(isRunning: Boolean, needsRegistration: Boolean) {
  Column(
      modifier = Modifier.fillMaxSize().background(Night).padding(32.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
  ) {
    Text("PLOGGING", color = Leaf, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    Spacer(Modifier.height(12.dp))
    Text(
        when {
          needsRegistration -> "Connect your Meta glasses"
          isRunning -> "Finding your glasses"
          else -> "Your view, with a little more impact."
        },
        color = Mist,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        when {
          needsRegistration -> "Tap Connect glasses to authorize the live camera in Meta AI."
          isRunning -> "Waiting for the first frame…"
          else -> "Choose a camera source in the HUD menu to begin."
        },
        color = Moss,
        fontSize = 13.sp,
    )
  }
}

@Composable
private fun HudTopBar(state: UiState, onMenu: () -> Unit, onRecord: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(NightSurface).padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.isRunning) Leaf else Moss))
      Spacer(Modifier.width(8.dp))
      Column {
        Text("PLOGGING", color = Mist, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Text(
            if (state.isRunning) "LIVE VIEW" else "VIEWFINDER",
            color = if (state.isRunning) Leaf else Moss,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      if (state.isRunning) {
        Surface(
            modifier = Modifier.height(36.dp).clickable(onClick = onRecord)
                .semantics { contentDescription = if (state.isRecording) "Stop recording" else "Record glasses and display" },
            shape = RoundedCornerShape(50),
            color = if (state.isRecording) Coral else NightSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
          Row(
              modifier = Modifier.padding(horizontal = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.isRecording) Color.White else Coral))
            Spacer(Modifier.width(6.dp))
            Text(
                "REC",
                color = if (state.isRecording) Color.White else Mist,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
          }
        }
        Spacer(Modifier.width(8.dp))
      }
      if (state.isRunning || state.picks > 0) {
        Surface(
            shape = RoundedCornerShape(50),
            color = NightSurface,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
          Text(
              "${state.picks} ${if (state.picks == 1) "PICK" else "PICKS"}",
              color = Leaf,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 0.8.sp,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
          )
        }
        Spacer(Modifier.width(8.dp))
      }
      Surface(
          modifier = Modifier.size(48.dp).clickable(onClick = onMenu)
              .semantics { contentDescription = "Open HUD menu" },
          shape = CircleShape,
          color = NightSurface,
          border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(if (state.isRunning) "···" else "☰", color = Mist, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

@Composable
private fun PickBanner(lastPick: Pick?) {
  var shown by remember { mutableStateOf<Pick?>(null) }
  LaunchedEffect(lastPick?.t, lastPick?.label) {
    if (lastPick == null) return@LaunchedEffect
    shown = lastPick
    delay(2_000)
    if (shown == lastPick) shown = null
  }
  val pick = shown ?: return
  Surface(
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      color = Leaf,
      shape = RoundedCornerShape(14.dp),
  ) {
    Text(
        "Picked: ${pick.label}",
        color = Night,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
  }
}

@Composable
private fun VoiceBanner(line: String?) {
  if (line.isNullOrBlank()) return
  Surface(
      modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
      color = NightSurface,
      shape = RoundedCornerShape(14.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, Leaf.copy(alpha = 0.35f)),
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
      Text(
          "VOICE",
          color = Leaf,
          fontSize = 9.sp,
          fontWeight = FontWeight.Bold,
          letterSpacing = 1.2.sp,
      )
      Text(
          line,
          color = Mist,
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium,
      )
    }
  }
}

/** What the display of the glasses is doing. Simulated glasses show the display itself too. */
@Composable
private fun GlassesDisplay(
    status: String,
    preview: ((Context) -> View)?,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier,
      color = NightSurface,
      shape = RoundedCornerShape(14.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
  ) {
    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      if (preview != null) {
        key(preview) {
          // The display is 600 wide and the view shrinks it to a quarter at most: 150 dp shows it whole.
          AndroidView(
              factory = preview,
              modifier = Modifier.size(150.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black),
              onRelease = { (it as? WebView)?.destroy() },
          )
        }
      }
      Column(Modifier.widthIn(max = 140.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("GLASSES DISPLAY", color = Moss, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(
            status,
            color = Mist,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun DetectionHud(state: UiState, hasFrame: Boolean, modifier: Modifier = Modifier) {
  val grouped = remember(state.detections) {
    state.detections.groupingBy { it.label }.eachCount().toSortedMap()
  }
  val summary = grouped.entries.joinToString("  ·  ") { (label, count) ->
    if (count > 1) "$label ×$count" else label
  }
  val count = state.detections.size

  Surface(
      modifier = modifier,
      color = NightSurface,
      shape = RoundedCornerShape(18.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.13f)),
  ) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("IN YOUR VIEW", color = Moss, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Spacer(Modifier.weight(1f))
        if (hasFrame) {
          Text("$count ${if (count == 1) "OBJECT" else "OBJECTS"}", color = Leaf, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
      }
      Spacer(Modifier.height(5.dp))
      Text(
          text = summary.ifEmpty { if (hasFrame) "Scanning for litter…" else state.status },
          color = Mist,
          fontSize = 15.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      if (hasFrame) {
        Spacer(Modifier.height(5.dp))
        Text(
            motionLine(state),
            color = Moss,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
      }
      Spacer(Modifier.height(5.dp))
      Text(
          reportLine(state),
          color = if (state.reportError != null) Coral else Moss,
          fontSize = 11.sp,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun PrimaryAction(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val needsConnect = state.needsRegistration
  val action = when {
    needsConnect -> onConnect
    state.isRunning -> onStop
    else -> onStart
  }
  val title = when {
    needsConnect -> "Connect glasses"
    state.isRunning -> "Stop session"
    else -> "Start plogging"
  }
  val subtitle = when {
    needsConnect -> "Pair with Meta AI"
    state.isRunning -> "Tap to end live view"
    else -> "Begin live detection"
  }

  Button(
      onClick = action,
      modifier = modifier.height(60.dp),
      shape = RoundedCornerShape(18.dp),
      colors = ButtonDefaults.buttonColors(
          containerColor = if (state.isRunning) Coral else Leaf,
          contentColor = Night,
      ),
      contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
      Text(subtitle, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
  }
}

@Composable
private fun MenuPanel(
    state: UiState,
    onSelectSource: (SourceKind) -> Unit,
    onApiUrl: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Surface(
      modifier = modifier.fillMaxWidth(0.88f).width(360.dp).clickable(onClick = {}),
      color = NightSurfaceStrong,
      shape = RoundedCornerShape(24.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
      shadowElevation = 22.dp,
  ) {
    Column(
        Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text("HUD MENU", color = Leaf, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
          Text("Camera source", color = Mist, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onClose) { Text("Done", color = Moss) }
      }

      SourceKind.entries.forEach { kind ->
        SourceOption(
            kind = kind,
            selected = state.sourceKind == kind,
            enabled = !state.isRunning,
            onClick = {
              onSelectSource(kind)
              onClose()
            },
        )
      }

      Text("API", color = Leaf, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
      OutlinedTextField(
          value = state.apiUrl,
          onValueChange = onApiUrl,
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          textStyle = TextStyle(color = Mist, fontSize = 13.sp),
          colors =
              OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = Leaf,
                  unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
                  focusedTextColor = Mist,
                  unfocusedTextColor = Mist,
                  cursorColor = Leaf,
              ),
          keyboardOptions =
              KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
      )

      Text(
          if (state.isRunning) "Stop the session before switching sources." else "Selected source: ${state.sourceKind.title}",
          color = Moss,
          fontSize = 11.sp,
          modifier = Modifier.padding(top = 1.dp),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 3.dp)) {
        if (state.isRunning) {
          Button(
              onClick = { onStop(); onClose() },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(containerColor = Coral, contentColor = Night),
              shape = RoundedCornerShape(14.dp),
          ) { Text("Stop", fontWeight = FontWeight.Bold) }
        } else if (state.needsRegistration) {
          Button(
              onClick = { onConnect(); onClose() },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Night),
              shape = RoundedCornerShape(14.dp),
          ) { Text("Connect glasses", fontWeight = FontWeight.Bold) }
        } else {
          Button(
              onClick = { onStart(); onClose() },
              modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Night),
              shape = RoundedCornerShape(14.dp),
          ) { Text("Start", fontWeight = FontWeight.Bold) }
        }
        if (state.sourceKind == SourceKind.GLASSES && !state.isRunning && !state.needsRegistration) {
          OutlinedButton(
              onClick = { onDisconnect(); onClose() },
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(14.dp),
              contentPadding = PaddingValues(horizontal = 8.dp),
          ) { Text("Disconnect", maxLines = 1) }
        }
      }
    }
  }
}

@Composable
private fun SourceOption(kind: SourceKind, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val container = if (selected) Color(0xFF283B2D) else Color.White.copy(alpha = 0.045f)
  Row(
      modifier = Modifier.fillMaxWidth()
          .clip(RoundedCornerShape(15.dp))
          .background(container)
          .border(1.dp, if (selected) Leaf.copy(alpha = 0.68f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(15.dp))
          .clickable(enabled = enabled, onClick = onClick)
          .padding(horizontal = 13.dp, vertical = 11.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
          kind.title,
          color = if (enabled) Mist else Moss,
          fontSize = 13.sp,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          kind.explanation,
          color = Moss,
          fontSize = 10.sp,
          lineHeight = 13.sp,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
    }
    if (selected) {
      Spacer(Modifier.width(10.dp))
      Box(Modifier.size(8.dp).clip(CircleShape).background(Leaf))
    }
  }
}

/** Full-screen cropped viewfinder with detection bounds transformed to match the crop. */
@Composable
private fun FrameView(
    frame: Bitmap,
    detections: List<Detection>,
    hands: List<HandPose>,
    fitInside: Boolean,
    modifier: Modifier = Modifier,
) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = TextStyle(color = Night, fontSize = 11.sp, fontWeight = FontWeight.Bold)
  Image(
      bitmap = frame.asImageBitmap(),
      contentDescription = "Live glasses camera view",
      contentScale = if (fitInside) ContentScale.Fit else ContentScale.Crop,
      modifier = modifier,
  )
  Canvas(modifier = modifier) {
    val widthScale = size.width / frame.width
    val heightScale = size.height / frame.height
    val scale = if (fitInside) minOf(widthScale, heightScale) else max(widthScale, heightScale)
    val drawnWidth = frame.width * scale
    val drawnHeight = frame.height * scale
    val cropX = (size.width - drawnWidth) / 2f
    val cropY = (size.height - drawnHeight) / 2f

    for (detection in detections) {
      val color = colorFor(detection.label)
      val topLeft = Offset(cropX + detection.left * drawnWidth, cropY + detection.top * drawnHeight)
      val boxSize = Size(
          (detection.right - detection.left) * drawnWidth,
          (detection.bottom - detection.top) * drawnHeight,
      )
      drawRoundRect(color, topLeft, boxSize, CornerRadius(5.dp.toPx()), style = Stroke(2.dp.toPx()))

      val text = "${detection.label} ${(detection.confidence * 100).roundToInt()}%"
      val label = textMeasurer.measure(text, labelStyle)
      val padding = 5.dp.toPx()
      val tagSize = Size(label.size.width + padding * 2, label.size.height + 3f)
      val tagLeft = topLeft.x.coerceIn(0f, (size.width - tagSize.width).coerceAtLeast(0f))
      val tagTop = (topLeft.y - tagSize.height).coerceIn(0f, (size.height - tagSize.height).coerceAtLeast(0f))
      drawRoundRect(color, Offset(tagLeft, tagTop), tagSize, CornerRadius(4.dp.toPx()))
      if (tagSize.width <= size.width && tagSize.height <= size.height) {
        drawText(label, topLeft = Offset(tagLeft + padding, tagTop + 1f))
      }
    }

    fun at(x: Float, y: Float) = Offset(cropX + x * drawnWidth, cropY + y * drawnHeight)
    for (hand in hands) {
      if (hand.points.size < 21) continue
      for ((start, end) in HAND_EDGES) {
        drawLine(Color.White, at(hand.points[start].x, hand.points[start].y), at(hand.points[end].x, hand.points[end].y), 2.dp.toPx())
      }
      for (point in hand.points) {
        drawCircle(Leaf, 3.dp.toPx(), at(point.x, point.y))
      }
    }
  }
}

private val HAND_EDGES =
    listOf(
        0 to 1, 1 to 2, 2 to 3, 3 to 4,
        0 to 5, 5 to 6, 6 to 7, 7 to 8,
        0 to 9, 9 to 10, 10 to 11, 11 to 12,
        0 to 13, 13 to 14, 14 to 15, 15 to 16,
        0 to 17, 17 to 18, 18 to 19, 19 to 20,
        5 to 9, 9 to 13, 13 to 17,
    )

/** The same colour for the same class on every frame. */
private fun colorFor(label: String): Color {
  val hue = label.fold(0) { seed, char -> (seed * 31 + char.code) % 360 }
  return Color.hsv(hue.toFloat(), 0.75f, 1f)
}

private fun reportLine(state: UiState): String {
  val error = state.reportError
  if (error != null) return error
  if (state.isRunning && !state.hasLocation) return "Waiting for GPS"
  return "${state.reportSent} sent · ${state.reportQueued} queued"
}

private fun motionLine(state: UiState): String {
  val parts = mutableListOf("${state.detectionsPerSecond} fps  ·  ${state.inferenceMilliseconds.roundToInt()} ms")
  val tilt = state.tiltDegrees
  if (tilt != null) parts += "tilt ${tilt.roundToInt()}°"
  else if (state.isRunning) parts += "pick detection off"
  parts += if (state.handTracking) "hands ${state.hands.size}" else "hands off"
  return parts.joinToString("  ·  ")
}
