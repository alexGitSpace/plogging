package dev.plogging.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {

  private val viewModel: AppViewModel by viewModels()

  // Both must be registered before the activity starts.
  private lateinit var glassesCamera: GlassesCameraPermission
  private val bluetooth =
      registerForActivityResult(RequestPermission()) { granted ->
        viewModel.onBluetoothPermission(granted)
      }

  private var phoneCameraWaiting: CancellableContinuation<Boolean>? = null
  private val phoneCamera =
      registerForActivityResult(RequestPermission()) { granted ->
        phoneCameraWaiting?.resume(granted)
        phoneCameraWaiting = null
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    glassesCamera = GlassesCameraPermission(this)
    enableEdgeToEdge()
    // The phone is a viewfinder while this is open: do not let the screen sleep.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    setContent {
      MaterialTheme {
        MainScreen(
            viewModel = viewModel,
            onStart = { viewModel.start(glassesCamera::request, ::requestPhoneCamera) },
            onConnectGlasses = { viewModel.connectGlasses(this) },
            onDisconnectGlasses = { viewModel.disconnectGlasses(this) },
        )
      }
    }

    // The glasses talk over Bluetooth: Android shows this as "Nearby devices".
    bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
  }

  /** Asks Android for the camera of this phone. Returns whether it is allowed. */
  private suspend fun requestPhoneCamera(): Boolean {
    val allowed =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    if (allowed) return true
    return suspendCancellableCoroutine { waiting ->
      phoneCameraWaiting = waiting
      waiting.invokeOnCancellation { phoneCameraWaiting = null }
      phoneCamera.launch(Manifest.permission.CAMERA)
    }
  }
}
