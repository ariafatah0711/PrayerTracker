package com.prayertracker.app.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object LocationHelper {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentDeviceLocation(context: Context): Result<Triple<String, Double, Double>> = withContext(Dispatchers.IO) {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@withContext Result.failure(IllegalStateException("LocationManager tidak tersedia"))

            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null

            for (provider in providers) {
                val loc = try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                }
                if (loc != null) {
                    if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                        bestLocation = loc
                    }
                }
            }

            if (bestLocation == null) {
                return@withContext Result.failure(IllegalStateException("Lokasi GPS belum terdeteksi. Pastikan GPS HP kamu aktif."))
            }

            val lat = bestLocation.latitude
            val lng = bestLocation.longitude
            var cityName = "Lokasi Saya"

            try {
                val geocoder = Geocoder(context, Locale("id", "ID"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // For Android 13+ synchronous fallback
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        cityName = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: "Lokasi Saya"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        cityName = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: "Lokasi Saya"
                    }
                }
            } catch (_: Exception) {
                cityName = "Koordinat (${String.format(Locale.US, "%.2f", lat)}, ${String.format(Locale.US, "%.2f", lng)})"
            }

            // Bersihkan prefix jika ada (misal: "Kota Depok" -> "Depok")
            cityName = cityName.replace("Kota ", "", ignoreCase = true)
                .replace("Kabupaten ", "Kab. ", ignoreCase = true)
                .trim()

            Result.success(Triple(cityName, lat, lng))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
