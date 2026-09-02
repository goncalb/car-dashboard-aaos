/*
 * Car Dashboard for Android Automotive OS
 * Copyright (C) 2026 Gonçalo Barradas
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.example.homeycar

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Geofencing {

    fun scheduleRefresh(context: Context) {
        val req = androidx.work.PeriodicWorkRequestBuilder<FenceWorker>(
            6, java.util.concurrent.TimeUnit.HOURS).build()
        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "fence-refresh", androidx.work.ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun reregisterFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences("homey", 0)
        val lat = prefs.getString("homeLat", null)?.toDoubleOrNull() ?: return
        val lng = prefs.getString("homeLng", null)?.toDoubleOrNull() ?: return
        prefs.edit().remove("fenceSig").apply()   // force a fresh addGeofences
        register(context, lat, lng)
    }

    private const val FENCE_ID = "home"
    const val RADIUS_M = 300f

    @SuppressLint("MissingPermission") // checked explicitly below
    fun register(context: Context, lat: Double, lng: Double) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val prefs = context.getSharedPreferences("homey", 0)
        val sig = "%.5f,%.5f".format(lat, lng)

        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(lat, lng, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(30_000)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        val pi = PendingIntent.getBroadcast(
            context, 2001,
            Intent(context, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pi)
            .addOnSuccessListener {
                prefs.edit().putString("fenceSig", sig)
                    .putString("homeLat", lat.toString())
                    .putString("homeLng", lng.toString()).apply()
                scheduleRefresh(context)
            }
    }
}

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val entering = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val prefs = context.getSharedPreferences("homey", 0)
        val stamp = "${if (entering) "ENTER" else "EXIT"} " +
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                .format(java.util.Date())
        val log = (listOf(stamp) +
            (prefs.getString("fenceLog", "") ?: "").split("|").filter { it.isNotBlank() })
            .take(5).joinToString("|")
        prefs.edit().putString("fenceLog", log)
            .putLong("lastFenceEvent", System.currentTimeMillis()).apply()
        val leaving = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT
        if (!entering && !leaving) return

        val token = prefs.getString("token", "") ?: return
        if (token.isEmpty()) return
        prefsBaseUrl = prefs.getString("baseUrl", "") ?: ""
        if (prefsBaseUrl.isEmpty()) return

        val pending = goAsync()
        Thread {
            try {
                val items = fetchBarrierTiles(token)
                val nm = NotificationManagerCompat.from(context)
                nm.createNotificationChannel(
                    NotificationChannelCompat.Builder("proximity", NotificationManagerCompat.IMPORTANCE_HIGH)
                        .setName("Garage alerts").build()
                )
                if (items.isEmpty()) return@Thread
                if (entering) {
                    nm.cancel(1002)
                    val closed = items.filter { it.closed == true }
                    if (closed.isNotEmpty()) {
                        val mention = items.filter { it.closed == false }
                            .joinToString("") { " · ${it.label} already open" }
                        notifyBarrier(context, nm, 1001, "Near home",
                            closed.joinToString(" · ") { "${it.label} ${it.stateWord()}" } + mention,
                            closed.map { Triple(it.openLabel, it.tileId, "open") })
                    }
                } else {
                    nm.cancel(1001)
                    val open = items.filter { it.closed == false }
                    if (open.isEmpty()) return@Thread
                    val auto = open.filter { it.auto }
                    val ask = open.filter { !it.auto }
                    val done = ArrayList<String>()
                    for (it0 in auto) {
                        if (postBarrierAction(token, it0.tileId, "close")) {
                            val verb = if (it0.closeLabel.startsWith("Lock")) "locked" else "closed"
                            done.add("${it0.label} $verb automatically ✓")
                        }
                    }
                    val parts = ArrayList<String>()
                    parts.addAll(done)
                    parts.addAll(ask.map { "${it.label} still ${it.stateWord()}" })
                    if (parts.isNotEmpty()) {
                        notifyBarrier(context, nm, 1002, "You left home", parts.joinToString(" · "),
                            ask.map { Triple(it.closeLabel, it.tileId, "close") })
                    }
                }
            } catch (_: Exception) { }
            finally { pending.finish() }
        }.start()
    }

    data class BarrierTile(
        val tileId: String, val type: String, val label: String, val summary: String,
        val auto: Boolean, val openLabel: String, val closeLabel: String,
    ) {
        val closed: Boolean? = when (summary) {
            "CLOSED", "LOCKED" -> true
            "OPEN", "UNLOCKED" -> false
            else -> null
        }
        fun stateWord() = if (summary == "UNLOCKED") "unlocked" else if (summary == "LOCKED") "locked" else summary.lowercase()
    }

    private var prefsBaseUrl: String = ""

    private fun fetchBarrierTiles(token: String): List<BarrierTile> {
        val http = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url("${prefsBaseUrl}/api/app/com.barradas.cardashboard/state?carToken=$token")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val tiles = JSONObject(res.body!!.string()).optJSONArray("tiles") ?: return emptyList()
            val out = ArrayList<BarrierTile>()
            for (i in 0 until tiles.length()) {
                val t = tiles.getJSONObject(i)
                val type = t.optString("type")
                if (type != "garage" && type != "gate") continue
                if (!t.optBoolean("geofence", true)) continue
                out.add(BarrierTile(
                    t.optString("tileId"), type,
                    t.optString("label", if (type == "gate") "Gate" else "Garage"),
                    t.optString("summary"),
                    t.optBoolean("auto", false),
                    t.optString("openLabel", "Open"),
                    t.optString("closeLabel", "Close"),
                ))
            }
            return out
        }
    }

    private fun postBarrierAction(token: String, tileId: String, op: String): Boolean {
        return try {
            val http = OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
            val body = JSONObject().put("tileId", tileId).put("action", op).put("carToken", token)
                .toString().toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder()
                .url("${prefsBaseUrl}/api/app/com.barradas.cardashboard/action?carToken=$token")
                .post(body).build()).execute().use { it.isSuccessful }
        } catch (_: Exception) { false }
    }

    private fun notifyBarrier(
        context: Context, nm: NotificationManagerCompat, id: Int,
        title: String, text: String, actions: List<Triple<String, String, String>>,
    ) {
        try {
            val body = PendingIntent.getActivity(
                context, id,
                Intent(context, androidx.car.app.activity.CarAppActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            val b = NotificationCompat.Builder(context, "proximity")
                .setSmallIcon(R.drawable.ic_garage)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setTimeoutAfter(20 * 60_000L)
                .extend(androidx.car.app.notification.CarAppExtender.Builder()
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH).build())
            val shown = actions.take(if (actions.size > 2) 2 else actions.size)
            shown.forEachIndexed { idx, (label, tileId, op) ->
                b.addAction(R.drawable.ic_garage_open, label, PendingIntent.getBroadcast(
                    context, id * 10 + idx,
                    Intent(context, NotifActionReceiver::class.java)
                        .putExtra("op", op).putExtra("tileId", tileId).putExtra("nid", id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            if (actions.size > 1) {
                val ids = actions.joinToString(",") { it.second }
                b.addAction(R.drawable.ic_garage_open, if (actions.size > 2) "All" else "Both",
                    PendingIntent.getBroadcast(
                        context, id * 10 + 9,
                        Intent(context, NotifActionReceiver::class.java)
                            .putExtra("op", actions[0].third).putExtra("tileId", ids).putExtra("nid", id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }
            nm.notify(id, b.build())
        } catch (_: Exception) { }
    }
}
