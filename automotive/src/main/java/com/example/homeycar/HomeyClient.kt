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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AuthRequiredException : Exception("Not paired")
class SetupRequiredException : Exception("No Homey configured")

object HomeyClient {

    @Volatile var token: String = ""

    @Volatile var baseUrl: String = Config.BASE_URL
    fun setHomeyId(id: String) { baseUrl = "https://$id.connect.athom.com" }

    private const val APP_PATH = "/api/app/com.barradas.cardashboard"
    private val json = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class EnergyColumn(
        val key: String, val value: String, val low: Boolean,
        val producing: Boolean, val flow: String, val charging: Boolean,
    )

    data class TodayRow(val key: String, val value: String, val text: String, val tone: String)

    data class DeviceState(
        val id: String,
        val name: String,
        val zone: String,
        val zoneOrder: Int,
        val available: Boolean,
        val on: Boolean?,
        val locked: Boolean?,
        val coverOpen: Boolean?,
        val temperature: Double?,
        val humidity: Double?,
        val contactOpen: Boolean?,
        val position: Double?,
        val dim: Double?,
    )

    data class HomeLocation(val lat: Double, val lng: Double)

    data class TileState(
        val tileId: String,
        val type: String,
        val label: String,
        val summary: String,
        val summaryLine: String,
        val heroLabel: String?,     // energy: "Home consumption" etc.
        val attention: Boolean,
        val detail: String?,
        val footer: String?,
        val columns: List<EnergyColumn>,
        val today: List<TodayRow>,
        val instant: Boolean,
        val hasDetail: Boolean,
        val devices: List<DeviceState>,
    )

    data class Scene(val flowId: String, val label: String)

    data class Meta(val appVersion: String, val homeyVersion: String)

    data class Snapshot(
        val timestamp: String,
        val tiles: List<TileState>,
        val scenes: List<Scene>,
        val home: HomeLocation?,
        val meta: Meta?,
    )

    suspend fun fetchState(): Snapshot = withContext(Dispatchers.IO) {
        val obj = JSONObject(get("$APP_PATH/state"))
        Snapshot(
            timestamp = obj.optString("timestamp"),
            tiles = obj.optJSONArray("tiles").toTileStates(),
            scenes = obj.optJSONArray("scenes").toScenes(),
            home = obj.optJSONObject("home")?.let {
                HomeLocation(it.optDouble("lat"), it.optDouble("lng"))
            },
            meta = obj.optJSONObject("meta")?.let {
                Meta(it.optString("appVersion", ""), it.optString("homeyVersion", ""))
            },
        )
    }

    suspend fun sendAction(tileId: String, action: String): Boolean = withContext(Dispatchers.IO) {
        post("$APP_PATH/action", JSONObject().put("tileId", tileId).put("action", action))
    }

    suspend fun sendDeviceAction(tileId: String, action: String, deviceId: String): Boolean =
        withContext(Dispatchers.IO) {
            post("$APP_PATH/action",
                JSONObject().put("tileId", tileId).put("action", action).put("deviceId", deviceId))
        }

    suspend fun postCarMeta(meta: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        post("$APP_PATH/car-meta", JSONObject().put("meta", carMetaJson(meta)))
    }

    suspend fun setLevel(tileId: String, deviceId: String, level: Double): Boolean =
        withContext(Dispatchers.IO) {
            post("$APP_PATH/action",
                JSONObject().put("tileId", tileId).put("action", "setLevel")
                    .put("deviceId", deviceId).put("level", level))
        }

    fun carMetaJson(meta: Map<String, String>): JSONObject {
        val o = JSONObject()
        meta.forEach { (k, v) -> o.put(k, v) }
        return o
    }

    suspend fun pair(code: String, name: String, meta: Map<String, String> = emptyMap()): String? = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", code).put("name", name)
            .put("meta", carMetaJson(meta))
        val req = Request.Builder()
            .url(baseUrl + "$APP_PATH/pair")
            .post(payload.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return@withContext null
            val t = JSONObject(res.body!!.string()).optString("token")
            if (t.isEmpty()) null else { token = t; t }
        }
    }

    suspend fun unpair(): Boolean = withContext(Dispatchers.IO) {
        try { post("$APP_PATH/unpair", JSONObject()) } catch (e: Exception) { false }
    }

    suspend fun runScene(flowId: String): Boolean = withContext(Dispatchers.IO) {
        post("$APP_PATH/action", JSONObject().put("sceneId", flowId))
    }

    private fun get(path: String): String {
        if (baseUrl.contains("192.168.1.100")) throw SetupRequiredException()
        val sep = if (path.contains('?')) '&' else '?'
        val req = Request.Builder()
            .url("$baseUrl$path${sep}carToken=$token")
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 401) throw AuthRequiredException()
            check(res.isSuccessful) { "HTTP ${res.code} for $path" }
            return res.body!!.string()
        }
    }

    private fun post(path: String, payload: JSONObject): Boolean {
        payload.put("carToken", token)
        val req = Request.Builder()
            .url(baseUrl + path)
            .post(payload.toString().toRequestBody(json))
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 401) throw AuthRequiredException()
            return res.isSuccessful
        }
    }

    private fun JSONArray?.toTileStates(): List<TileState> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val t = getJSONObject(i)
            val names = t.optJSONArray("detailNames")
            val cols = t.optJSONArray("columns")
            TileState(
                tileId = t.getString("tileId"),
                type = t.getString("type"),
                label = t.optString("label", t.getString("type")),
                summary = t.optString("summary", "—"),
                summaryLine = t.optString("summaryLine", ""),
                heroLabel = t.optString("heroLabel").ifEmpty { null },
                attention = t.optBoolean("attention", false),
                detail = names?.let { arr ->
                    (0 until arr.length()).joinToString(", ") { arr.getString(it) }
                },
                footer = t.optString("footer", t.optString("footerNote")).ifEmpty { null },
                columns = cols?.let { arr ->
                    (0 until arr.length()).map { j ->
                        val c = arr.getJSONObject(j)
                        EnergyColumn(
                            key = c.getString("key"),
                            value = c.optString("value", "—"),
                            low = c.optBoolean("low", false),
                            producing = c.optBoolean("producing", false),
                            flow = c.optString("flow", ""),
                            charging = c.optBoolean("charging", false),
                        )
                    }
                } ?: emptyList(),
                today = t.optJSONArray("today")?.let { arr ->
                    (0 until arr.length()).map { j ->
                        val r = arr.getJSONObject(j)
                        TodayRow(r.getString("key"), r.optString("value", "—"), r.optString("text", ""), r.optString("tone", "neutral"))
                    }
                } ?: emptyList(),
                instant = t.optBoolean("instant", false),
                hasDetail = t.optBoolean("hasDetail", false),
                devices = t.optJSONArray("devices")?.let { arr ->
                    (0 until arr.length()).map { j ->
                        val d = arr.getJSONObject(j)
                        DeviceState(
                            id = d.getString("id"),
                            name = d.optString("name", "?"),
                            zone = d.optString("zone", ""),
                            zoneOrder = d.optInt("zoneOrder", 999),
                            position = if (d.has("position") && !d.isNull("position")) d.optDouble("position") else null,
                            dim = if (d.has("dim") && !d.isNull("dim")) d.optDouble("dim") else null,
                            available = d.optBoolean("available", true),
                            on = if (d.has("on") && !d.isNull("on")) d.getBoolean("on") else null,
                            locked = if (d.has("locked") && !d.isNull("locked")) d.getBoolean("locked") else null,
                            coverOpen = run {
                                val st = d.optString("coverState", "")
                                val pos = if (d.has("coverPosition") && !d.isNull("coverPosition")) d.getDouble("coverPosition") else null
                                when {
                                    pos != null -> pos > 0.05
                                    st == "up" -> true
                                    st == "down" -> false
                                    else -> null
                                }
                            },
                            temperature = if (d.has("temperature") && !d.isNull("temperature")) d.getDouble("temperature") else null,
                            humidity = if (d.has("humidity") && !d.isNull("humidity")) d.getDouble("humidity") else null,
                            contactOpen = if (d.has("contactAlarm") && !d.isNull("contactAlarm")) d.getBoolean("contactAlarm") else null,
                        )
                    }
                } ?: emptyList(),
            )
        }
    }

    private fun JSONArray?.toScenes(): List<Scene> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val s = getJSONObject(i)
            Scene(flowId = s.getString("flowId"), label = s.getString("label"))
        }
    }
}
