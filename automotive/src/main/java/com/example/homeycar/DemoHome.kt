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

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simulated home for Google Play review. Activated by pairing with
 *  Homey ID "demo" and code "2026REVIEW". Entirely local; no network. */
object DemoHome {

    const val HOMEY_ID = "demo"
    const val CODE = "2026REVIEW"

    private var garageClosed = true
    private var gateClosed = true
    private var frontLocked = true
    private val lightOn = linkedMapOf(
        "l1" to false, "l2" to true, "l3" to false, "l4" to false, "l5" to false, "l6" to true,
    )
    private val lightDim = mutableMapOf("l4" to 0.8, "l5" to 0.6)
    private var blindPos = mutableMapOf("b1" to 1.0, "b2" to 0.4)

    fun reset() {
        garageClosed = true; gateClosed = true; frontLocked = true
        listOf("l1","l3","l4","l5").forEach { lightOn[it] = false }
        lightOn["l2"] = true; lightOn["l6"] = true
        lightDim["l4"] = 0.8; lightDim["l5"] = 0.6
        blindPos["b1"] = 1.0; blindPos["b2"] = 0.4
    }

    fun runScene(flowId: String): Boolean {
        when (flowId) {
            "f1" -> {   // Movie night: ceiling off, TV lightstrip on, floor lamp dim 20%
                lightOn["l3"] = false
                lightOn["l4"] = true; lightDim["l4"] = 0.2
                lightOn["l5"] = true; lightDim["l5"] = 0.4
                blindPos["b1"] = 0.0
            }
            "f2" -> {   // Leaving home: all lights off, blinds closed, door locked
                lightOn.keys.forEach { lightOn[it] = false }
                blindPos.keys.forEach { blindPos[it] = 0.0 }
                frontLocked = true
            }
        }
        return true
    }

    fun action(tileId: String, action: String, deviceId: String?, level: Double?): Boolean {
        when (tileId) {
            "t_garage" -> garageClosed = action == "close"
            "t_gate" -> gateClosed = action == "close"
            "t_lock" -> frontLocked = action == "lock"
            "t_lights" -> {
                val ids = if (deviceId != null) listOf(deviceId) else lightOn.keys.toList()
                when (action) {
                    "setLevel" -> if (deviceId != null && level != null) {
                        lightDim[deviceId] = level
                        lightOn[deviceId] = level > 0.0
                    }
                    "toggleDevice" -> ids.forEach { lightOn[it] = !(lightOn[it] ?: false) }
                    else -> ids.forEach { lightOn[it] = action == "on" }
                }
            }
            "t_blinds" -> {
                val ids = if (deviceId != null) listOf(deviceId) else blindPos.keys.toList()
                when (action) {
                    "open" -> ids.forEach { blindPos[it] = 1.0 }
                    "close" -> ids.forEach { blindPos[it] = 0.0 }
                    "setLevel" -> if (deviceId != null && level != null) blindPos[deviceId] = level
                }
            }
        }
        return true
    }

    private fun dev(
        id: String, name: String, zone: String, zo: Int,
        on: Boolean? = null, locked: Boolean? = null, coverOpen: Boolean? = null,
        temperature: Double? = null, contactOpen: Boolean? = null, position: Double? = null,
        dim: Double? = null,
    ) = HomeyClient.DeviceState(id, name, zone, zo, true, on, locked, coverOpen,
        temperature, null, contactOpen, position, dim)

    fun snapshot(): HomeyClient.Snapshot {
        val lights = listOf(
            dev("l1", "Ceiling light", "Kitchen", 0, on = lightOn["l1"]),
            dev("l2", "Counter light", "Kitchen", 0, on = lightOn["l2"]),
            dev("l3", "Ceiling light", "Living room", 1, on = lightOn["l3"]),
            dev("l4", "Floor lamp", "Living room", 1, on = lightOn["l4"], dim = lightDim["l4"]),
            dev("l5", "TV lightstrip", "Living room", 1, on = lightOn["l5"], dim = lightDim["l5"]),
            dev("l6", "Garden light", "Garden", 2, on = lightOn["l6"]),
        )
        val onCount = lightOn.values.count { it }
        val blinds = listOf(
            dev("b1", "Living room blind", "Living room", 1,
                coverOpen = blindPos["b1"]!! > 0.05, position = blindPos["b1"]),
            dev("b2", "Bedroom blind", "Bedroom", 3,
                coverOpen = blindPos["b2"]!! > 0.05, position = blindPos["b2"]),
        )
        val openBlinds = blinds.count { it.coverOpen == true }
        fun tile(
            id: String, type: String, label: String, summary: String,
            attention: Boolean = false, devices: List<HomeyClient.DeviceState> = emptyList(),
            summaryLine: String = "", hasDetail: Boolean = devices.isNotEmpty(),
            columns: List<HomeyClient.EnergyColumn> = emptyList(),
            today: List<HomeyClient.TodayRow> = emptyList(),
            heroLabel: String? = null, detail: String? = null, footer: String? = null,
        ) = HomeyClient.TileState(id, type, label, summary, summaryLine, heroLabel,
            attention, detail, footer, columns, today, hasDetail, devices)
        val tiles = listOf(
            tile("t_garage", "garage", "Garage", if (garageClosed) "CLOSED" else "OPEN",
                attention = !garageClosed),
            tile("t_gate", "gate", "Gate", if (gateClosed) "CLOSED" else "OPEN",
                attention = !gateClosed),
            tile("t_lock", "lock", "Front door", if (frontLocked) "LOCKED" else "UNLOCKED",
                attention = !frontLocked,
                devices = listOf(dev("d1", "Front door", "Entrance", 0, locked = frontLocked))),
            tile("t_lights", "lights", "Lights", "$onCount ON",
                devices = lights, summaryLine = "${lights.size} lights · $onCount on"),
            tile("t_blinds", "blinds", "Blinds",
                if (openBlinds == 0) "ALL CLOSED" else "$openBlinds OPEN",
                devices = blinds, summaryLine = "${blinds.size} blinds · $openBlinds open"),
            tile("t_temp", "temperature", "Inside", "21.5°",
                devices = listOf(dev("d2", "Living room", "Living room", 1, temperature = 21.5))),
            tile("t_energy", "energy", "Energy", "1.8 kW",
                heroLabel = "Home consumption", hasDetail = true,
                columns = listOf(
                    HomeyClient.EnergyColumn("home", "1.8 kW", false, false, "", false),
                    HomeyClient.EnergyColumn("solar", "3.1 kW", false, true, "producing", false),
                    HomeyClient.EnergyColumn("battery", "86%", false, false, "charging", true),
                    HomeyClient.EnergyColumn("grid", "0.9 kW", false, false, "exporting", false),
                ),
                today = listOf(
                    HomeyClient.TodayRow("cons", "12.4 kWh", "Consumption · 71% self-sufficient", "neutral"),
                    HomeyClient.TodayRow("solar", "18.9 kWh", "Solar · 64% used at home", "amber"),
                    HomeyClient.TodayRow("batt", "+6.2 / −4.1 kWh", "Battery · charged / discharged", "neutral"),
                    HomeyClient.TodayRow("grid", "↓3.6 / ↑6.8 kWh", "Grid · imported / exported", "neutral"),
                ),
                footer = "Demo values"),
        )
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        return HomeyClient.Snapshot(
            timestamp = ts,
            tiles = tiles,
            scenes = listOf(
                HomeyClient.Scene("f1", "Movie night"),
                HomeyClient.Scene("f2", "Leaving home"),
            ),
            home = null,   // no geofence in the demo home
            meta = HomeyClient.Meta("demo", "demo"),
        )
    }
}
