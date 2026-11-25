package app.lockmein

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The phone's rough position (city level is enough for fajr), or null when location is off or not allowed. */
object PhoneLocation {
    suspend fun current(context: Context): Pair<Double, Double>? = runCatching {
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val fresh = providers.firstOrNull()?.let { provider ->
            withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { cont ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
                }
            }
        }
        (fresh ?: last)?.let { it.latitude to it.longitude }
    }.getOrNull()
}
