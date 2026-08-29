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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val op = intent.getStringExtra("op") ?: return          // "open" | "close"
        val nid = intent.getIntExtra("nid", 0)
        val prefs = context.getSharedPreferences("homey", 0)
        val token = prefs.getString("token", "") ?: return
        val base = prefs.getString("baseUrl", "") ?: return
        if (token.isEmpty() || base.isEmpty()) return
        val nm = NotificationManagerCompat.from(context)
        val pending = goAsync()
        thread {
            try {
                val http = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
                val path = "$base/api/app/com.barradas.cardashboard"
                val wanted = (intent.getStringExtra("tileId") ?: "").split(",").filter { it.isNotBlank() }
                data class T(val tileId: String, val type: String, val summary: String, val done: String)
                val targets = ArrayList<T>()
                http.newCall(Request.Builder().url("$path/state?carToken=$token").build())
                    .execute().use { res ->
                        if (res.isSuccessful) {
                            val tiles = JSONObject(res.body!!.string()).optJSONArray("tiles")
                            for (i in 0 until (tiles?.length() ?: 0)) {
                                val t = tiles!!.getJSONObject(i)
                                val ty = t.optString("type")
                                val id2 = t.optString("tileId")
                                if (ty != "garage" && ty != "gate") continue
                                if (wanted.isNotEmpty() && id2 !in wanted) continue
                                if (wanted.isEmpty() && ty != "garage") continue   // legacy intent: garage only
                                targets.add(T(id2, ty, t.optString("summary"),
                                    (if (op == "open") t.optString("openLabel", "Open") else t.optString("closeLabel", "Close"))))
                            }
                        }
                    }
                val results = ArrayList<String>()
                for (t in targets) {
                    val already = (op == "close" && (t.summary == "CLOSED" || t.summary == "LOCKED")) ||
                        (op == "open" && (t.summary == "OPEN" || t.summary == "UNLOCKED"))
                    if (already) { results.add("${t.done}: already done ✓"); continue }
                    var ok = false
                    val body = JSONObject()
                        .put("tileId", t.tileId).put("action", op)
                        .put("carToken", token).toString()
                        .toRequestBody("application/json".toMediaType())
                    http.newCall(Request.Builder()
                        .url("$path/action?carToken=$token").post(body).build())
                        .execute().use { res -> ok = res.isSuccessful }
                    results.add(if (ok) "${t.done} ✓" else "${t.done} FAILED — open the app")
                }
                if (nid != 0) nm.cancel(nid)
                if (results.isNotEmpty()) nm.notify(1003, NotificationCompat.Builder(context, "proximity")
                    .setSmallIcon(R.drawable.ic_garage)
                    .setContentTitle(results.joinToString(" · "))
                    .setAutoCancel(true)
                    .setTimeoutAfter(15_000)
                    .build())
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
