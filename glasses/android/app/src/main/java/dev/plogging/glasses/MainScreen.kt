package dev.plogging.glasses

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
fun MainScreen(
    viewModel: AppViewModel,
    onStart: () -> Unit,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit,
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()

  Surface(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SourcePicker(state, onSelect = viewModel::select)
      Preview(viewModel, state.isRunning, state.detections, Modifier.weight(1f))
      StatusLine(state, hasFrame = viewModel.frame.collectAsStateWithLifecycle().value != null)
      DetectionList(state.detections)
      Controls(state, onStart, viewModel::stop, onConnectGlasses, onDisconnectGlasses)
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
private fun SourcePicker(state: UiState, onSelect: (SourceKind) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Column {
    Box {
      TextButton(onClick = { expanded = true }, enabled = !state.isRunning) {
        Text("${state.sourceKind.title} ▾", style = MaterialTheme.typography.titleMedium)
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        SourceKind.entries.forEach { kind ->
          DropdownMenuItem(
              text = { Text(kind.title) },
              onClick = {
                expanded = false
                onSelect(kind)
              },
          )
        }
      }
    }
    Text(
        state.sourceKind.explanation,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun Preview(
    viewModel: AppViewModel,
    isRunning: Boolean,
    detections: List<Detection>,
    modifier: Modifier = Modifier,
) {
  val frame by viewModel.frame.collectAsStateWithLifecycle()
  Box(
      modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black),
      contentAlignment = Alignment.Center,
  ) {
    val current = frame
    if (current != null) {
      FrameView(current, detections)
    } else {
      Text(
          if (isRunning) "Waiting for the first frame…" else "Press Start",
          color = Color.White.copy(alpha = 0.7f),
      )
    }
  }
}

/** One frame with the boxes of the detected objects on top. */
@Composable
private fun FrameView(frame: Bitmap, detections: List<Detection>) {
  val textMeasurer = rememberTextMeasurer()
  val labelStyle = TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)

  // The box takes the shape of the frame, so the picture and the drawing layer match exactly.
  Box(modifier = Modifier.aspectRatio(frame.width.toFloat() / frame.height)) {
    Image(
        bitmap = frame.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
    Canvas(modifier = Modifier.fillMaxSize()) {
      for (detection in detections) {
        val color = colorFor(detection.label)
        val topLeft = Offset(detection.left * size.width, detection.top * size.height)
        val boxSize =
            Size(
                (detection.right - detection.left) * size.width,
                (detection.bottom - detection.top) * size.height,
            )
        drawRoundRect(
            color,
            topLeft,
            boxSize,
            CornerRadius(4.dp.toPx()),
            style = Stroke(2.dp.toPx()),
        )

        val text = "${detection.label} ${(detection.confidence * 100).roundToInt()}%"
        val label = textMeasurer.measure(text, labelStyle)
        val padding = 4.dp.toPx()
        val tagSize = Size(label.size.width + 2 * padding, label.size.height + 2f)
        // Keep the tag inside the picture: drawing text outside the canvas is an error.
        val tagLeft = topLeft.x.coerceIn(0f, (size.width - tagSize.width).coerceAtLeast(0f))
        val tagTop =
            (topLeft.y - tagSize.height).coerceIn(0f, (size.height - tagSize.height).coerceAtLeast(0f))
        drawRoundRect(color, Offset(tagLeft, tagTop), tagSize, CornerRadius(3.dp.toPx()))
        if (tagSize.width <= size.width && tagSize.height <= size.height) {
          drawText(label, topLeft = Offset(tagLeft + padding, tagTop + 1f))
        }
      }
    }
  }
}

/** The same colour for the same class on every frame. */
private fun colorFor(label: String): Color {
  val hue = label.fold(0) { seed, char -> (seed * 31 + char.code) % 360 }
  return Color.hsv(hue.toFloat(), 0.75f, 1f)
}

@Composable
private fun StatusLine(state: UiState, hasFrame: Boolean) {
  Row(modifier = Modifier.fillMaxWidth()) {
    Text(
        state.status,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(1f),
    )
    if (hasFrame) {
      Text(
          "${state.detectionsPerSecond} detections/s · ${state.inferenceMilliseconds.roundToInt()} ms",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** What is in the frame right now, such as "bottle ×2, person". */
@Composable
private fun DetectionList(detections: List<Detection>) {
  val text =
      detections
          .groupingBy { it.label }
          .eachCount()
          .toSortedMap()
          .map { (label, count) -> if (count > 1) "$label ×$count" else label }
          .joinToString(", ")
  Text(
      text.ifEmpty { " " },
      style = MaterialTheme.typography.titleMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
  )
}

@Composable
private fun Controls(
    state: UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onConnectGlasses: () -> Unit,
    onDisconnectGlasses: () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    if (state.needsRegistration) {
      Button(onClick = onConnectGlasses) { Text("Connect glasses") }
      Text(
          "Opens the Meta AI app. Turn on Developer Mode there first.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.isRunning) {
          Button(
              onClick = onStop,
              colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          ) {
            Text("Stop")
          }
        } else {
          Button(onClick = onStart) { Text("Start") }
          if (state.sourceKind == SourceKind.GLASSES) {
            OutlinedButton(onClick = onDisconnectGlasses) { Text("Disconnect") }
          }
        }
      }
      Spacer(Modifier)
    }
  }
}
