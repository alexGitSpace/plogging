package dev.plogging.glasses

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import org.json.JSONObject

internal data class Recognised(val litterType: String, val confidence: Float)

internal object RecogniseClient {
  const val JPEG_QUALITY = 70
  const val CROP_PAD = 0.2f
  const val MIN_CONFIDENCE = 0.4f

  fun cropJpeg(frame: Bitmap, box: Detection, quality: Int = JPEG_QUALITY): ByteArray {
    val width = frame.width
    val height = frame.height
    if (width <= 0 || height <= 0) return ByteArray(0)
    val left = ((box.left - CROP_PAD) * width).roundToInt().coerceIn(0, width - 1)
    val top = ((box.top - CROP_PAD) * height).roundToInt().coerceIn(0, height - 1)
    val right = ((box.right + CROP_PAD) * width).roundToInt().coerceIn(left + 1, width)
    val bottom = ((box.bottom + CROP_PAD) * height).roundToInt().coerceIn(top + 1, height)
    val cropped =
        if (left == 0 && top == 0 && right == width && bottom == height) frame
        else Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    val stream = ByteArrayOutputStream()
    cropped.compress(Bitmap.CompressFormat.JPEG, quality, stream)
    if (cropped !== frame) cropped.recycle()
    return stream.toByteArray()
  }

  fun post(apiUrl: String, jpeg: ByteArray, hint: String?, hintConfidence: Float?): Recognised? {
    if (jpeg.isEmpty()) return null
    val connection = URL(recogniseUrl(apiUrl)).openConnection() as HttpURLConnection
    try {
      connection.requestMethod = "POST"
      connection.connectTimeout = 10_000
      connection.readTimeout = 12_000
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      connection.outputStream.use { it.write(body(jpeg, hint, hintConfidence).toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      if (code !in 200..299) {
        Log.w(TAG, "Recognise HTTP $code")
        return null
      }
      return parse(text)
    } catch (e: IOException) {
      Log.w(TAG, "Recognise post failed", e)
      return null
    } finally {
      connection.disconnect()
    }
  }

  private fun body(jpeg: ByteArray, hint: String?, hintConfidence: Float?): String {
    val obj = JSONObject()
    obj.put("image_jpeg_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
    if (hint != null) obj.put("hint", hint)
    if (hintConfidence != null) obj.put("hint_confidence", hintConfidence.toDouble())
    return obj.toString()
  }

  private fun parse(text: String): Recognised? {
    return try {
      val obj = JSONObject(text)
      val type = obj.getString("litter_type")
      val confidence = obj.getDouble("confidence").toFloat()
      if (type !in LITTER_TYPES || !confidence.isFinite()) null
      else Recognised(type, confidence.coerceIn(0f, 1f))
    } catch (e: Exception) {
      Log.w(TAG, "Recognise response was unusable", e)
      null
    }
  }

  private const val TAG = "RecogniseClient"
}
