package dev.plogging.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The phone's position while a session is running. */
internal class PhoneLocation(context: Context) {
  private val appContext = context.applicationContext
  private val manager = appContext.getSystemService(LocationManager::class.java)

  private val _fix = MutableStateFlow<LocationFix?>(null)
  val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

  private var running = false

  private val listener = LocationListener { location -> _fix.value = location.toFix() }

  @SuppressLint("MissingPermission")
  fun start() {
    val service = manager ?: return
    if (running || !permitted()) return
    lastKnown()?.let { _fix.value = it.toFix() }
    val providers =
        listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    for (provider in providers) {
      val started =
          runCatching {
                service.requestLocationUpdates(
                    provider,
                    1_000L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
              }
              .isSuccess
      if (started) {
        running = true
        return
      }
    }
  }

  fun stop() {
    if (!running) return
    runCatching { manager?.removeUpdates(listener) }
    running = false
  }

  private fun permitted(): Boolean {
    val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine || coarse
  }

  private fun granted(permission: String): Boolean =
      ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

  @SuppressLint("MissingPermission")
  private fun lastKnown(): Location? {
    val service = manager ?: return null
    if (!permitted()) return null
    val providers =
        listOf(
            LocationManager.FUSED_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
    return providers.firstNotNullOfOrNull { provider ->
      runCatching { service.getLastKnownLocation(provider) }.getOrNull()
    }
  }
}

private fun Location.toFix() = LocationFix(latitude, longitude)
