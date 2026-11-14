package dev.plogging.glasses

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs a YOLO ONNX model on single frames.
 *
 * The model must be exported with non-maximum suppression built in (`nms=True` in
 * Ultralytics): its output is then a list of ready boxes, `[x1, y1, x2, y2, confidence, class]`.
 * Call [detect] from one thread at a time.
 */
class ObjectDetector
private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val labels: Map<Int, String>,
    private val inputSize: Int,
    /** Detections below this confidence are dropped. */
    var confidenceThreshold: Float,
) : AutoCloseable {

  companion object {
    const val MODEL_ASSET = "detector.onnx"
    const val CONFIDENCE_THRESHOLD = 0.55f

    fun load(context: Context, confidenceThreshold: Float = CONFIDENCE_THRESHOLD): ObjectDetector {
      val model =
          try {
            context.assets.open(MODEL_ASSET).use { it.readBytes() }
          } catch (e: FileNotFoundException) {
            throw SetupException(MODEL_ASSET)
          }

      val environment = OrtEnvironment.getEnvironment()
      val session =
          OrtSession.SessionOptions().use { options ->
            // The default CPU backend. XNNPACK was tried and ran this model twice as slowly.
            options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
            environment.createSession(model, options)
          }

      // Ultralytics stores the class names in the model: "{0: 'person', 1: 'bicycle', ...}".
      val names = session.metadata.customMetadata["names"].orEmpty()
      val labels =
          Regex("""(\d+):\s*(?:'([^']*)'|"([^"]*)")""").findAll(names).associate { match ->
            match.groupValues[1].toInt() to match.groupValues[2].ifEmpty { match.groupValues[3] }
          }

      // The input is [1, 3, size, size].
      val shape = (session.inputInfo.values.first().info as TensorInfo).shape
      val inputSize = shape.last().toInt().takeIf { it > 0 } ?: 640

      return ObjectDetector(environment, session, labels, inputSize, confidenceThreshold)
    }
  }

  private val inputName = session.inputNames.first()
  private val letterbox = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
  private val canvas = Canvas(letterbox)
  private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val pixels = IntArray(inputSize * inputSize)
  private val values = FloatArray(3 * inputSize * inputSize)
  private val input: FloatBuffer =
      ByteBuffer.allocateDirect(3 * inputSize * inputSize * Float.SIZE_BYTES)
          .order(ByteOrder.nativeOrder())
          .asFloatBuffer()

  fun detect(frame: Bitmap): List<Detection> {
    // Fit the whole frame into the square model input with grey bars around it, as in
    // training. Stretching it instead distorts the objects: YOLO then calls bottles toothbrushes.
    val scale = min(inputSize.toFloat() / frame.width, inputSize.toFloat() / frame.height)
    val scaledWidth = (frame.width * scale).roundToInt()
    val scaledHeight = (frame.height * scale).roundToInt()
    val padX = (inputSize - scaledWidth) / 2f
    val padY = (inputSize - scaledHeight) / 2f

    canvas.drawColor(Color.rgb(114, 114, 114))
    canvas.drawBitmap(frame, null, RectF(padX, padY, padX + scaledWidth, padY + scaledHeight), paint)
    letterbox.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

    // The model wants the three colour planes one after another, each value in 0..1.
    val plane = inputSize * inputSize
    for (i in 0 until plane) {
      val pixel = pixels[i]
      values[i] = ((pixel shr 16) and 0xFF) / 255f
      values[plane + i] = ((pixel shr 8) and 0xFF) / 255f
      values[2 * plane + i] = (pixel and 0xFF) / 255f
    }
    input.rewind()
    input.put(values)
    input.rewind()

    val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
    OnnxTensor.createTensor(environment, input, shape).use { tensor ->
      session.run(mapOf(inputName to tensor)).use { result ->
        @Suppress("UNCHECKED_CAST") val rows = (result[0].value as Array<Array<FloatArray>>)[0]
        return rows.mapNotNull { row ->
          val confidence = row[4]
          if (confidence < confidenceThreshold) return@mapNotNull null
          // Back from the padded model input to the frame.
          Detection(
              label = labels[row[5].toInt()] ?: "class ${row[5].toInt()}",
              confidence = confidence,
              left = ((row[0] - padX) / scaledWidth).coerceIn(0f, 1f),
              top = ((row[1] - padY) / scaledHeight).coerceIn(0f, 1f),
              right = ((row[2] - padX) / scaledWidth).coerceIn(0f, 1f),
              bottom = ((row[3] - padY) / scaledHeight).coerceIn(0f, 1f),
          )
        }
      }
    }
  }

  override fun close() {
    session.close()
  }
}

/** A file made by `glasses/scripts/setup.sh` is missing from the app. */
class SetupException(file: String) :
    Exception("$file is not in the app. Run glasses/scripts/setup.sh and build again.")
