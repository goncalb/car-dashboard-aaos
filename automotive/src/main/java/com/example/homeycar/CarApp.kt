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

import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.car.app.model.signin.InputSignInMethod
import androidx.car.app.model.signin.SignInTemplate
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

suspend fun cityOrNull(carContext: CarContext): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(carContext,
                    android.Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) return@withContext null
            val fused = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(carContext)
            val loc = try {
                com.google.android.gms.tasks.Tasks.await(
                    fused.getCurrentLocation(
                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        com.google.android.gms.tasks.CancellationTokenSource().token),
                    8, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) { null }
                ?: try {
                    com.google.android.gms.tasks.Tasks.await(fused.lastLocation,
                        3, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) { null }
                ?: return@withContext null
            val a = try {
                if (android.location.Geocoder.isPresent())
                    android.location.Geocoder(carContext, java.util.Locale.getDefault())
                        .getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                else null
            } catch (e: Exception) { null }
            val city = a?.let { it.locality ?: it.subAdminArea }
            if (city != null) city + (a.countryCode?.let { ", $it" } ?: "")
            else String.format(java.util.Locale.US, "\u2248 %.2f\u00b0, %.2f\u00b0",
                loc.latitude, loc.longitude)
        } catch (e: Exception) { null }
    }

fun carMeta(carContext: CarContext): Map<String, String> {
    val dm = carContext.resources.displayMetrics
    return mapOf(
        "model" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
        "android" to android.os.Build.VERSION.RELEASE,
        "appVersion" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        "carApi" to carContext.carAppApiLevel.toString(),
        "screen" to "${dm.widthPixels}×${dm.heightPixels}",
        "network" to run {
            try {
                val cm = carContext.getSystemService(android.net.ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val kind = when {
                    caps == null -> "offline"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile" +
                        (carContext.getSystemService(android.telephony.TelephonyManager::class.java)
                            ?.networkOperatorName?.takeIf { it.isNotBlank() }
                            ?.let { " \u00b7 $it" } ?: "")
                    else -> "Other"
                }
                val ip = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
                    ?.map { it.address }
                    ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                kind + (ip?.let { " · $it" } ?: "")
            } catch (e: Exception) { "unknown" }
        },
    )
}

fun listLimit(ctx: CarContext): Int = try {
    ctx.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
        .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
} catch (e: Exception) { Int.MAX_VALUE }

fun leadRow(ctx: CarContext, total: Int, title: String, parked: String): Row {
    val driving = total > listLimit(ctx)
    return Row.Builder().setTitle(title).addText(if (driving) "Full list when parked" else parked).build()
}

class Sections(private val lead: String) {
    private val items = ArrayList<Pair<String, MutableList<Row.Builder>>>()
    fun section(header: String): MutableList<Row.Builder> = ArrayList<Row.Builder>().also { items.add(header to it) }
    fun applyTo(tpl: ListTemplate.Builder, ctx: CarContext, title: String, parked: String): ListTemplate.Builder {
        val total = items.sumOf { it.second.size } + 1
        tpl.addSectionedList(SectionedItemList.create(
            ItemList.Builder().addItem(leadRow(ctx, total, title, parked)).build(), lead))
        for ((header, rows) in items) {
            if (rows.isEmpty()) continue
            val list = ItemList.Builder()
            rows.forEach { list.addItem(it.build()) }
            tpl.addSectionedList(SectionedItemList.create(list.build(), header))
        }
        return tpl
    }
}

fun sectionHeader(zone: String, count: Int, type: String): String {
    val noun = when (type) {
        "lights" -> "LIGHT"
        "blinds" -> "BLIND"
        "lock" -> "LOCK"
        "temperature", "contact" -> "SENSOR"
        else -> "DEVICE"
    }
    return "\u2002" + zone.uppercase() + " · " + count + " " + noun + (if (count == 1) "" else "S")
}

object PendingActions { @Volatile var openGarage = false; @Volatile var closeGarage = false }

class CarDashboardService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = DashboardSession()
}

class DashboardSession : Session() {

    override fun onNewIntent(intent: Intent) {
        when (intent.dataString) {
            "homeydash://garage/open" -> PendingActions.openGarage = true
            "homeydash://garage/close" -> PendingActions.closeGarage = true
        }
    }

    override fun onCreateScreen(intent: Intent): Screen {
        android.util.Log.i("HomeyDash", "Car App API level: ${carContext.carAppApiLevel}")
        when (intent.dataString) {
            "homeydash://garage/open" -> PendingActions.openGarage = true
            "homeydash://garage/close" -> PendingActions.closeGarage = true
        }
        val prefs = carContext.getSharedPreferences("homey", 0)
        HomeyClient.token = prefs.getString("token", "") ?: ""
        prefs.getString("baseUrl", "")?.takeIf { it.isNotEmpty() }?.let {
            if (it == "demo") HomeyClient.demo = true else HomeyClient.baseUrl = it
        }
        return HomeScreen(carContext)
    }
}

class PermissionScreen(carContext: CarContext) : Screen(carContext) {

    private var message = "Location enables the near-home garage shortcut and arrival/departure alerts — even with the app closed (geofencing). Notifications deliver those alerts. All optional; the dashboard works without them."
    private var step = 0

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(message)
            .setTitle("Enable smart features?")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle(if (step == 0) "Enable" else "Allow all the time")
                    .setOnClickListener { request() }
                    .build()
            )
            .addAction(
                Action.Builder().setTitle("Not now")
                    .setOnClickListener { screenManager.pop() }.build()
            )
            .build()

    private fun request() {
        val perms = if (step == 0) listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) else listOf(
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        try {
            carContext.requestPermissions(perms) { granted, _ ->
                if (step == 0 && granted.isNotEmpty()) {
                    step = 1
                    message = "One more: choose \"Allow all the time\" so arrival/departure alerts work with the app closed. If location still seems off afterwards, also check the car's master Location switch in Settings."
                } else if (step == 1) {
                    message = if (granted.isNotEmpty())
                        "All set ✓ — background alerts active."
                    else
                        "Background access declined or unavailable — alerts will work while the app is open. You can change this later in the car's Settings → Apps."
                } else {
                    message = "Declined or unsupported here — you can grant permissions in the car's Settings → Apps → Homey Dashboard."
                }
                invalidate()
            }
        } catch (e: Exception) {
            message = "This screen can't request here — grant via car Settings → Apps → Homey Dashboard."
            invalidate()
        }
    }
}

class SetupScreen(carContext: CarContext) : Screen(carContext) {

    private var message = "One-time setup: open the Homey app → Car Dashboard settings → Generate pairing code. Enter the Homey ID shown there (Step 1)."
    private var busy = false

    override fun onGetTemplate(): Template {
        val input = InputSignInMethod.Builder(object : androidx.car.app.model.InputCallback {
            override fun onInputSubmitted(text: String) { submit(text) }
        })
            .setHint("Homey ID (24 characters)")
            .build()
        return SignInTemplate.Builder(input)
            .setTitle("Connect to your Homey")
            .setInstructions(message)
            .setHeaderAction(Action.APP_ICON)
            .setLoading(busy)
            .build()
    }

    private fun submit(raw: String) {
        val id = raw.trim().lowercase()
            .removePrefix("https://").removeSuffix("/")
            .replace(".connect.athom.com", "")
            .filter { it.isLetterOrDigit() }
        if (id.length != 24) {
            if (id.equals(DemoHome.HOMEY_ID, ignoreCase = true)) {
                carContext.getSharedPreferences("homey", 0).edit()
                    .putString("baseUrl", "demo").commit()
                screenManager.push(PairScreen(carContext))
                return
            }
            message = "That doesn't look like a Homey ID (24 letters/digits). It's shown in the Homey app → Car Dashboard settings, Step 1."
            invalidate(); return
        }
        busy = true
        message = "Checking your Homey ID…"
        invalidate()
        lifecycleScope.launch {
            HomeyClient.setHomeyId(id)
            val ok = try { HomeyClient.fetchState(); true } catch (e: AuthRequiredException) { true } catch (e: Exception) { false }
            busy = false
            if (ok) {
                carContext.getSharedPreferences("homey", 0).edit()
                    .putString("baseUrl", "https://$id.connect.athom.com").commit()
                CarToast.makeText(carContext, "Homey found ✓", CarToast.LENGTH_SHORT).show()
                screenManager.push(PairScreen(carContext))
            } else {
                message = "Couldn't reach that Homey — check the ID and that the car has connectivity, then try again."
                invalidate()
            }
        }
    }
}

class PairScreen(carContext: CarContext) : Screen(carContext) {

    private val isDemo: Boolean
        get() = carContext.getSharedPreferences("homey", 0).getString("baseUrl", "") == "demo"
    private var message = ""
    private var busy = false

    override fun onGetTemplate(): Template {
        if (message.isEmpty()) message = if (isDemo) "Demo home: enter the demo code."
            else "Open the Homey app → Car Dashboard settings → Generate pairing code, then enter it here."
        val input = InputSignInMethod.Builder(object : androidx.car.app.model.InputCallback {
            override fun onInputSubmitted(text: String) { submit(text.trim().uppercase()) }
        })
            .setHint(if (isDemo) "Demo code" else "6-character code")
            .build()

        return SignInTemplate.Builder(input)
            .setTitle("Pair with Homey")
            .setInstructions(message)
            .setHeaderAction(Action.APP_ICON)
            .setLoading(busy)
            .build()
    }

    private fun submit(raw: String) {
        val code = raw.trim().uppercase()
        val prefs0 = carContext.getSharedPreferences("homey", 0)
        if (prefs0.getString("baseUrl", "") == "demo") {
            if (code == DemoHome.CODE) {
                HomeyClient.demo = true
                DemoHome.reset()
                prefs0.edit().putString("token", "demo")
                    .putLong("pairedAt", System.currentTimeMillis())
                    .putString("tokenTail", "demo").commit()
                CarToast.makeText(carContext, "Demo home ✓", CarToast.LENGTH_SHORT).show()
                HomeScreen.resetToHome = true
                screenManager.popToRoot()
            } else {
                message = "Demo code incorrect."
                invalidate()
            }
            return
        }
        busy = true; invalidate()
        lifecycleScope.launch {
            val token = try { HomeyClient.pair(code, "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim().ifEmpty { "Car" }, carMeta(carContext)) } catch (e: Exception) { null }
            busy = false
            if (token != null) {
                carContext.getSharedPreferences("homey", 0).edit()
                    .putString("token", token)
                    .putLong("pairedAt", System.currentTimeMillis())
                    .putString("tokenTail", token.takeLast(4))
                    .commit()
                CarToast.makeText(carContext, "Paired ✓", CarToast.LENGTH_SHORT).show()
                HomeScreen.resetToHome = true
                screenManager.popToRoot()
            } else {
                message = "Code rejected or expired — generate a fresh one in Homey settings and try again."
                invalidate()
            }
        }
    }
}

private var lastMetaAt = 0L

class HomeScreen(carContext: CarContext) : Screen(carContext) {

    companion object { @JvmStatic @Volatile var resetToHome = false }

    private var snapshot: HomeyClient.Snapshot? = null
    private var error: String? = null
    private var refreshJob: Job? = null
    private var nearHome = false
    private var arrivedAt = 0L
    private val ARRIVAL_WINDOW_MS = 10 * 60 * 1000L

    private fun arrivalHintActive(): Boolean =
        nearHome && arrivedAt > 0 &&
        (System.currentTimeMillis() - arrivedAt) < ARRIVAL_WINDOW_MS
    private var justDeparted = false
    private var departNotified = false
    private var approachNotified = false
    private var permOffered = false

    private fun maybeOfferPermissions() {
        if (permOffered) return
        permOffered = true
        val prefs = carContext.getSharedPreferences("homey", 0)
        if (prefs.getBoolean("permOffered", false)) return
        val missing = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ).any { ContextCompat.checkSelfPermission(carContext, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) {
            prefs.edit().putBoolean("permOffered", true).commit()
            screenManager.push(PermissionScreen(carContext))
        }
    }

    private fun updateProximity(home: HomeyClient.HomeLocation?) {
        if (home == null) { nearHome = false; return }
        if (ContextCompat.checkSelfPermission(carContext, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            val lm = carContext.getSystemService(CarContext.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
                ?: return
            val res = FloatArray(1)
            android.location.Location.distanceBetween(loc.latitude, loc.longitude, home.lat, home.lng, res)
            val dist = res[0]
            val wasNear = nearHome
            nearHome = dist < 300f
            if (!wasNear && nearHome) arrivedAt = System.currentTimeMillis()
            if (wasNear && dist > 500f) { justDeparted = true; arrivedAt = 0L }
            if (nearHome) justDeparted = false
        } catch (_: Exception) { }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                val p = carContext.getSharedPreferences("homey", 0)
                HomeyClient.token = p.getString("token", "") ?: ""
                p.getString("baseUrl", "")?.takeIf { it.isNotEmpty() }?.let { HomeyClient.baseUrl = it }
                pairingShown = false
                maybeOfferPermissions()
                if (HomeyClient.token.isNotEmpty() &&
                    System.currentTimeMillis() - lastMetaAt > 15 * 60_000L) {
                    lastMetaAt = System.currentTimeMillis()
                    lifecycleScope.launch {
                        try {
                            val meta = carMeta(carContext).toMutableMap()
                            cityOrNull(carContext)?.let { meta["location"] = it }
                            HomeyClient.postCarMeta(meta)
                        } catch (_: Exception) { }
                    }
                }
                refreshJob = lifecycleScope.launch {
                    while (true) { refresh(); delay(20_000) }
                }
            }
            override fun onStop(owner: LifecycleOwner) { refreshJob?.cancel() }
        })
    }

    private var pairingShown = false

    private var lastRefreshAt = 0L
    private var lastRefreshResult = "never"

    private suspend fun refresh() {
        try {
            snapshot = HomeyClient.fetchState()
            lastRefreshAt = System.currentTimeMillis()
            lastRefreshResult = "OK"
            updateProximity(snapshot?.home)
            snapshot?.home?.let { Geofencing.register(carContext, it.lat, it.lng) }
            maybeNotifyApproach()
            consumePendingActions()
            error = null
        } catch (e: AuthRequiredException) {
            error = "Not paired — pair this car with your Homey"
            if (!pairingShown) {
                pairingShown = true
                screenManager.push(PairScreen(carContext))
            }
        } catch (e: SetupRequiredException) {
            error = "Not set up — connect this car to your Homey"
            if (!pairingShown) {
                pairingShown = true
                screenManager.push(SetupScreen(carContext))
            }
        } catch (e: Exception) {
            lastRefreshResult = e.javaClass.simpleName + (e.message?.let { ": " + it.take(40) } ?: "")
            error = "Connection lost — showing last known state"
        }
        invalidate()
    }

    private fun maybeNotifyApproach() {
        val nm = NotificationManagerCompat.from(carContext)
        val garage = snapshot?.tiles?.firstOrNull { it.type == "garage" }

        if (garage?.summary != "CLOSED" || !nearHome) nm.cancel(1001)
        if (garage?.summary != "OPEN") nm.cancel(1002)
        if (garage == null) return
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder("proximity", NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Garage alerts").build()
        )
        if (nearHome && garage.summary == "CLOSED" && !approachNotified) {
            approachNotified = true
            postGarageNotification(nm, 1001, "Near home — garage is closed",
                "Open it? One tap.", "Open garage", "homeydash://garage/open")
        }
        if (!nearHome) approachNotified = false
        if (justDeparted && garage.summary == "OPEN" && !departNotified) {
            departNotified = true
            postGarageNotification(nm, 1002, "You left — garage is still OPEN",
                "Close it? You'll confirm first.", "Close garage", "homeydash://garage/close")
        }
        if (garage.summary != "OPEN") { departNotified = false; justDeparted = false }
    }

    private fun postGarageNotification(
        nm: NotificationManagerCompat, id: Int,
        title: String, text: String, actionLabel: String, uri: String,
    ) {
        try {
            val body = android.app.PendingIntent.getActivity(
                carContext, id,
                Intent(carContext, androidx.car.app.activity.CarAppActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val action = CarPendingIntent.getCarApp(
                carContext, id,
                Intent(Intent.ACTION_VIEW)
                    .setComponent(android.content.ComponentName(carContext, CarDashboardService::class.java))
                    .setData(android.net.Uri.parse(uri)),
                0
            )
            nm.notify(id, NotificationCompat.Builder(carContext, "proximity")
                .setSmallIcon(R.drawable.ic_garage)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(body)
                .addAction(R.drawable.ic_garage_open, actionLabel, action)
                .setAutoCancel(true)
                .extend(CarAppExtender.Builder().setImportance(NotificationManagerCompat.IMPORTANCE_HIGH).build())
                .build())
        } catch (_: Exception) { }
    }

    private fun consumePendingActions() {
        val nm = NotificationManagerCompat.from(carContext)
        if (PendingActions.openGarage) {
            PendingActions.openGarage = false
            nm.cancel(1001)
            val garage = snapshot?.tiles?.firstOrNull { it.type == "garage" }
            if (garage != null && garage.summary == "CLOSED") {
                if (nearHome) {
                    CarToast.makeText(carContext, "Opening garage…", CarToast.LENGTH_SHORT).show()
                    runAction(garage.tileId, "open")
                } else screenManager.push(
                    ConfirmScreen(carContext, if (HomeyClient.demo) "Open the garage door?" else "You're away from home — open the garage door?",
                        onConfirm = { runAction(garage.tileId, "open") })
                )
            }
        }
        if (PendingActions.closeGarage) {
            PendingActions.closeGarage = false
            nm.cancel(1002)
            val garage = snapshot?.tiles?.firstOrNull { it.type == "garage" }
            if (garage != null && garage.summary == "OPEN") {
                screenManager.push(
                    ConfirmScreen(carContext, "Close the garage door?",
                        onConfirm = { runAction(garage.tileId, "close") })
                )
            }
        }
    }

    private var activeTab = "home"

    override fun onGetTemplate(): Template {
        val snap = snapshot

        fun tab(id: String, title: String, icon: Int) = Tab.Builder()
            .setContentId(id).setTitle(title)
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .build()

        val err = error
        val content = if (snap == null) {
            when {
                err != null -> MessageTemplate.Builder(err).build()
                activeTab == "home" -> GridTemplate.Builder().setLoading(true).build()
                else -> ListTemplate.Builder().setLoading(true).build()
            }
        } else when (run {
            if (resetToHome) { resetToHome = false; activeTab = "home" }
            activeTab
        }) {
            "lights" -> buildLightsTab(snap)
            "scenes" -> buildScenesTab(snap)
            "info" -> buildInfoTab(snap)
            else -> buildHomeTab(snap)
        }

        return TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                activeTab = tabContentId
                invalidate()
            }
        })
            .setHeaderAction(Action.APP_ICON)
            .addTab(tab("home", "Home", R.drawable.ic_home))
            .addTab(tab("lights", "Lights", R.drawable.ic_light))
            .addTab(tab("scenes", "Scenes", R.drawable.ic_scene))
            .addTab(tab("info", "Info", R.drawable.ic_info))
            .setTabContents(TabContents.Builder(content).build())
            .setActiveTabContentId(activeTab)
            .build()
    }

    private fun buildHomeTab(snap: HomeyClient.Snapshot): Template {
        val list = ItemList.Builder()
        snap.tiles.forEach { tile ->
            val text = when (tile.type) {
                "energy" -> listOfNotNull(
                    tile.summaryLine.ifEmpty { null },
                    tile.footer
                ).joinToString(" · ").ifEmpty { tile.heroLabel ?: tile.label }
                else -> tile.label +
                        (if (tile.type != "lights") tile.detail?.let { " · $it" } ?: "" else "") +
                        (tile.footer?.let { " · ⚠ $it" } ?: "") +
                        (if (tile.type == "garage" && tile.summary == "CLOSED" && arrivalHintActive())
                            " · Near home — tap to open" else "")
            }
            val highlight = tile.attention ||
                (tile.type == "garage" && tile.summary == "CLOSED" && arrivalHintActive())
            val textOut: CharSequence = if (highlight) {
                SpannableString(text).apply {
                    setSpan(
                        ForegroundCarColorSpan.create(CarColor.YELLOW),
                        0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
                    )
                }
            } else text
            list.addItem(
                GridItem.Builder()
                    .setImage(tileIcon(tile), GridItem.IMAGE_TYPE_LARGE)
                    .setTitle(tile.summary)
                    .setText(textOut)
                    .setOnClickListener { onTileClick(tile) }
                    .build()
            )
        }
        return GridTemplate.Builder()
            .setItemSize(GridTemplate.ITEM_SIZE_LARGE)
            .setSingleList(list.build())
            .build()
    }

    private fun buildLightsTab(snap: HomeyClient.Snapshot): Template {
        val tile = snap.tiles.firstOrNull { it.type == "lights" }
        val tpl = ListTemplate.Builder()
        val devices = tile?.devices ?: emptyList()
        if (tile == null || devices.isEmpty()) {
            return ListTemplate.Builder().setSingleList(
                ItemList.Builder().setNoItemsMessage("No lights whitelisted.").build()
            ).build()
        }
        val sections = Sections("\u2002LIGHTS")
        devices.groupBy { Pair(it.zoneOrder, it.zone.ifEmpty { "Other" }) }
            .toList().sortedBy { it.first.first }
            .forEach { (key, devs) ->
                val section = sections.section(sectionHeader(key.second, devs.size, "lights"))
                devs.sortedBy { it.name.lowercase() }.forEach { d ->
                    val on = d.on == true && d.available
                    val row = Row.Builder()
                        .setImage(
                            Badges.badge(carContext, R.drawable.ic_light,
                                if (!d.available) 0xFF565B60.toInt()
                                else if (on) Badges.AMBER else Badges.NEUTRAL),
                            Row.IMAGE_TYPE_LARGE)
                        .setTitle(d.name)
                        .addText(
                            (if (on) "ON" + (d.dim?.let { " · ${(it * 100).toInt()}%" } ?: "") else "Off") +
                            (if (!d.available) " · unreachable" else ""))
                    if (d.available) row.setOnClickListener {
                        if (d.dim != null) {
                            screenManager.push(LevelScreen(carContext, d.name, tile.tileId, d.id,
                                if (on) (d.dim * 100).toInt() else 0, isBlind = false,
                                initialOn = on,
                                onPower = {
                                    lifecycleScope.launch {
                                        val ok = try { HomeyClient.sendDeviceAction(tile.tileId, "toggleDevice", d.id) } catch (e: Exception) { false }
                                        CarToast.makeText(carContext, if (ok) "${d.name} ✓" else "Failed", CarToast.LENGTH_SHORT).show()
                                        refresh()
                                    }
                                }))
                        } else lifecycleScope.launch {
                            val ok = try { HomeyClient.sendDeviceAction(tile.tileId, "toggleDevice", d.id) } catch (e: Exception) { false }
                            CarToast.makeText(carContext, if (ok) "${d.name} ✓" else "Failed", CarToast.LENGTH_SHORT).show()
                            refresh()
                        }
                    }
                    section.add(row)
                }
            }
        val on = devices.count { it.on == true }
        return sections.applyTo(tpl, carContext, "${devices.size} lights", if (on == 0) "All off" else "$on on").build()
    }

    private fun buildScenesTab(snap: HomeyClient.Snapshot): Template {
        if (snap.scenes.isEmpty()) {
            return ListTemplate.Builder().setSingleList(ItemList.Builder()
                .setNoItemsMessage("No scenes whitelisted — add them in the Homey app settings.").build()).build()
        }
        val rows = snap.scenes.map { scene ->
            Row.Builder()
                .setImage(Badges.badge(carContext, R.drawable.ic_scene, Badges.BLUE), Row.IMAGE_TYPE_LARGE)
                .setTitle(scene.label)
                .addText("Tap to run")
                .setOnClickListener {
                    lifecycleScope.launch {
                        val ok = try { HomeyClient.runScene(scene.flowId) } catch (e: Exception) { false }
                        CarToast.makeText(carContext,
                            if (ok) "${scene.label} ✓" else "Failed to run ${scene.label}",
                            CarToast.LENGTH_SHORT).show()
                    }
                }
        }
        val list = ItemList.Builder()
        list.addItem(leadRow(carContext, rows.size + 1, "${rows.size} scenes", "Tap to run"))
        rows.forEach { list.addItem(it.build()) }
        return ListTemplate.Builder().setSingleList(list.build()).build()
    }

    private fun buildInfoTab(snap: HomeyClient.Snapshot): Template {
        val prefs = carContext.getSharedPreferences("homey", 0)
        val homeyId = (prefs.getString("baseUrl", "") ?: "")
            .removePrefix("https://").substringBefore(".")
        val pairedAt = prefs.getLong("pairedAt", 0L)
        val pairedText = if (pairedAt > 0)
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                .format(java.util.Date(pairedAt)) else "—"
        val tail = prefs.getString("tokenTail", "") ?: ""
        val gridLimit = try {
            carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
                .getContentLimit(androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_GRID)
                .toString()
        } catch (e: Exception) { "?" }
        val listLim = listLimit(carContext).let { if (it == Int.MAX_VALUE) "?" else it.toString() }
        val bgLoc = ContextCompat.checkSelfPermission(carContext,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fineLoc = ContextCompat.checkSelfPermission(carContext,
            android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val locText = when {
            bgLoc -> "Allowed all the time ✓"
            fineLoc -> "While using the app"
            else -> "Off — see car Settings"
        }

        fun info(title: String, value: String) = Row.Builder()
            .setTitle(title).addText(value)

        val tpl = ListTemplate.Builder()
        val sections = Sections("\u2002INFO")
        val status = sections.section("\u2002STATUS")
        listOf(info("Homey", if (HomeyClient.demo) "Demo home — not connected"
                else if (homeyId.length > 12)
                    homeyId.take(6) + "…" + homeyId.takeLast(6) else homeyId),
            info("Companion app",
                    snap.meta?.let { "v${it.appVersion} · Homey ${it.homeyVersion}" } ?: "—"),
            info("Paired", "$pairedText · key …$tail"),
            info("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
            info("Car App API", "${carContext.carAppApiLevel} · grid $gridLimit · list $listLim"),
            info("Location features", locText),
            info("Fence events (last 5)", run {
                    val log = prefs.getString("fenceLog", "") ?: ""
                    if (log.isBlank()) "none since install"
                    else log.split("|").joinToString("  ·  ")
                }),
            info("Last refresh", run {
                    val t = if (lastRefreshAt > 0)
                        java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM)
                            .format(java.util.Date(lastRefreshAt)) else "—"
                    "$t · $lastRefreshResult · server ${snap.timestamp.takeLast(9).removeSuffix("Z")}"
                }),
            info("Data", run {
                    val devs = snap.tiles.sumOf { it.devices.size }
                    "${snap.tiles.size} tiles · $devs devices · ${snap.scenes.size} scenes"
                }),
            info("Memory", run {
                    val am = carContext.getSystemService(android.app.ActivityManager::class.java)
                    val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                    val appMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576
                    "App $appMb MB · System " +
                        String.format("%.1f", mi.availMem / 1_073_741_824.0) + " of " +
                        String.format("%.1f", mi.totalMem / 1_073_741_824.0) + " GB free"
                })
        ).forEach { status.add(it) }
        val actions = sections.section("\u2002ACTIONS")
        actions.add(Row.Builder().setTitle("Refresh now").addText("Fetch the latest state")
            .setOnClickListener { lifecycleScope.launch { refresh() } })
        actions.add(Row.Builder().setTitle("Permission setup").addText("Location & notifications")
            .setOnClickListener { screenManager.push(PermissionScreen(carContext)) })
        actions.add(Row.Builder().setTitle("Disconnect this car").addText("Requires re-pairing to undo")
            .setOnClickListener {
                screenManager.push(ConfirmScreen(carContext,
                    "Disconnect this car from your Homey?",
                    onConfirm = { logout() },
                    detail = "You'll need the Homey ID and a new pairing code to reconnect."))
            })
        sections.applyTo(tpl, carContext, "Diagnostics", "Status and actions")
        return tpl.build()
    }

    private fun tileIcon(tile: HomeyClient.TileState): CarIcon {
        val (res, color) = when (tile.type) {
            "garage" -> {
                val attention = tile.attention ||
                    (tile.summary == "CLOSED" && arrivalHintActive())
                (if (tile.summary == "OPEN") R.drawable.ic_garage_open else R.drawable.ic_garage) to
                    (if (attention) Badges.AMBER else Badges.NEUTRAL)
            }
            "gate" ->
                R.drawable.ic_gate to (if (tile.attention) Badges.AMBER else Badges.GREEN)
            "lock" ->
                if (tile.summary == "LOCKED") R.drawable.ic_lock to Badges.GREEN
                else R.drawable.ic_lock_open to Badges.AMBER
            "lights" ->
                R.drawable.ic_light to (if (tile.attention) Badges.AMBER else Badges.NEUTRAL)
            "temperature" -> R.drawable.ic_thermo to Badges.NEUTRAL
            "blinds" ->
                (if (tile.summary.contains("OPEN")) R.drawable.ic_blinds_open else R.drawable.ic_blinds_closed) to Badges.NEUTRAL
            "contact" -> R.drawable.ic_sensor to (if (tile.attention) Badges.AMBER else Badges.NEUTRAL)
            "energy" -> R.drawable.ic_energy to Badges.BLUE
            else -> return CarIcon.APP_ICON
        }
        return Badges.badge(carContext, res, color)
    }

    private fun onTileClick(tile: HomeyClient.TileState) {
        when (tile.type) {
            "garage" -> {
                val isOpen = tile.summary == "OPEN"
                when {
                    isOpen -> screenManager.push(
                        ConfirmScreen(carContext, "Close the garage door?",
                            onConfirm = { runAction(tile.tileId, "close") })
                    )
                    nearHome -> {
                        CarToast.makeText(carContext, "Opening garage…", CarToast.LENGTH_SHORT).show()
                        runAction(tile.tileId, "open")
                    }
                    else -> screenManager.push(
                        ConfirmScreen(carContext, if (HomeyClient.demo) "Open the garage door?" else "You're away from home — open the garage door?",
                            onConfirm = { runAction(tile.tileId, "open") })
                    )
                }
            }
            "gate" ->
                runAction(tile.tileId, if (tile.summary == "UNLOCKED" || tile.summary == "OPEN") "close" else "open")
            "lock" ->
                if (tile.devices.size > 1)
                    screenManager.push(DeviceListScreen(carContext, tile.tileId, tile.label, tile.type))
                else screenManager.push(
                    ConfirmScreen(carContext, if (tile.summary == "LOCKED") "Unlock the door?" else "Lock the door?",
                        onConfirm = { runAction(tile.tileId, if (tile.summary == "LOCKED") "unlock" else "lock") })
                )
            "lights" -> { activeTab = "lights"; invalidate() }   // lights live in their tab
            "blinds", "contact", "temperature" ->
                if (tile.devices.isNotEmpty())
                    screenManager.push(DeviceListScreen(carContext, tile.tileId, tile.label, tile.type))
            "energy" -> if (tile.hasDetail) screenManager.push(EnergyDetailScreen(carContext, tile.tileId))
            else -> { }
        }
    }

    private fun logout() {
        lifecycleScope.launch {
            val revoked = HomeyClient.unpair()
            carContext.getSharedPreferences("homey", 0).edit()
                .remove("token").remove("baseUrl").remove("fenceSig")
                .remove("permOffered").commit()
            HomeyClient.token = ""
            HomeyClient.baseUrl = Config.BASE_URL
            snapshot = null
            NotificationManagerCompat.from(carContext).cancelAll()
            CarToast.makeText(carContext,
                if (revoked) "Disconnected" else "Disconnected locally — also revoke it in Homey settings",
                CarToast.LENGTH_LONG).show()
            pairingShown = true   // we push setup ourselves; don't double-push
            screenManager.push(SetupScreen(carContext))
        }
    }

    private fun runAction(tileId: String, action: String) {
        if (action == "open") arrivedAt = 0L   // arrival hint served its purpose

        lifecycleScope.launch {
            val ok = try { HomeyClient.sendAction(tileId, action) } catch (e: Exception) { false }
            CarToast.makeText(
                carContext,
                if (ok) "Done" else "Failed — try again",
                CarToast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }
}

class EnergyDetailScreen(
    carContext: CarContext,
    private val tileId: String,
) : Screen(carContext) {

    private var tile: HomeyClient.TileState? = null
    private var refreshJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refreshJob = lifecycleScope.launch {
                    while (true) {
                        try {
                            tile = HomeyClient.fetchState().tiles.firstOrNull { it.tileId == tileId }
                        } catch (_: Exception) { }
                        invalidate()
                        delay(10_000)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) { refreshJob?.cancel() }
        })
    }

    override fun onGetTemplate(): Template {
        val t = tile ?: return ListTemplate.Builder()
            .setTitle("Energy").setHeaderAction(Action.BACK).setLoading(true).build()

        val sections = Sections("\u2002ENERGY")
        val now = sections.section("\u2002NOW")
        now.add(energyRow(R.drawable.ic_home, Badges.NEUTRAL, t.summary, t.heroLabel ?: t.label))
        t.columns.forEach { col ->
            val (icon, label) = when (col.key) {
                "solar" -> R.drawable.ic_sun to "Solar"
                "battery" -> R.drawable.ic_battery to (if (col.low) "Battery · LOW" else "Battery")
                "grid" -> R.drawable.ic_grid to when (col.flow) {
                    "importing" -> "Grid · importing"
                    "exporting" -> "Grid · exporting"
                    else -> "Grid"
                }
                "ev" -> R.drawable.ic_ev to (if (col.charging) "EV charger · charging" else "EV charger · idle")
                else -> R.drawable.ic_sensor to col.key
            }
            val color = when (col.key) {
                "solar" -> if (col.producing) Badges.AMBER else Badges.NEUTRAL
                "battery" -> if (col.low) Badges.RED else Badges.GREEN
                "grid" -> when (col.flow) {
                    "importing" -> Badges.BLUE
                    "exporting" -> Badges.GREEN
                    else -> Badges.NEUTRAL
                }
                "ev" -> if (col.charging) Badges.AMBER else Badges.NEUTRAL
                else -> Badges.NEUTRAL
            }
            now.add(energyRow(icon, color, col.value, label))
        }

        val tpl = ListTemplate.Builder()
            .setTitle("${t.label}${t.footer?.let { " — $it" } ?: ""}")
            .setHeaderAction(Action.BACK)

        if (t.today.isNotEmpty()) {
            val today = sections.section("\u2002TODAY")
            t.today.forEach { r ->
                val icon = when (r.key) {
                    "solar" -> R.drawable.ic_sun
                    "battery" -> R.drawable.ic_battery
                    "grid" -> R.drawable.ic_grid
                    "ev" -> R.drawable.ic_ev
                    else -> R.drawable.ic_home
                }
                val color = when (r.tone) {
                    "amber" -> Badges.AMBER
                    "green" -> Badges.GREEN
                    "blue" -> Badges.BLUE
                    else -> Badges.NEUTRAL
                }
                today.add(energyRow(icon, color, r.value, r.text))
            }
        }
        return sections.applyTo(tpl, carContext, "Energy", if (t.today.isEmpty()) "Now" else "Now and today").build()
    }

    private fun energyRow(icon: Int, color: Int, title: String, text: String): Row.Builder =
        Row.Builder()
            .setImage(Badges.badge(carContext, icon, color), Row.IMAGE_TYPE_LARGE)
            .setTitle(title)
            .addText(text)
}

class DeviceListScreen(
    carContext: CarContext,
    private val tileId: String,
    private val title: String,
    private val type: String,
) : Screen(carContext) {

    private var devices: List<HomeyClient.DeviceState> = emptyList()
    private var refreshJob: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                refreshJob = lifecycleScope.launch {
                    while (true) {
                        try {
                            devices = HomeyClient.fetchState().tiles
                                .firstOrNull { it.tileId == tileId }?.devices ?: emptyList()
                        } catch (_: Exception) { }
                        invalidate()
                        delay(10_000)
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) { refreshJob?.cancel() }
        })
    }

    private fun isActive(d: HomeyClient.DeviceState): Boolean = when (type) {
        "lights" -> d.on == true
        "lock" -> d.locked == false
        "blinds" -> d.coverOpen == true
        "contact" -> d.contactOpen == true
        else -> false
    }

    private fun typeNoun(): String = when (type) {
        "lock" -> "locks"
        "contact", "temperature" -> "sensors"
        else -> type
    }

    private fun deviceSummary(devs: List<HomeyClient.DeviceState>): String = when (type) {
        "blinds" -> devs.count { it.coverOpen == true || (it.position ?: 0.0) > 0.01 }.let { if (it == 0) "All closed" else "$it open" }
        "contact" -> devs.count { it.contactOpen == true }.let { if (it == 0) "All closed" else "$it open" }
        "lock" -> devs.count { it.locked == true }.let { if (it == devs.size) "All locked" else "${devs.size - it} unlocked" }
        else -> "Tap for details"
    }

    private fun stateText(d: HomeyClient.DeviceState): String {
        if (!d.available) return "unreachable"
        return when (type) {
            "lights" -> if (d.on == true) "ON" else "Off"
            "blinds" -> {
                val p = d.position
                when {
                    p != null && p > 0.01 && p < 0.99 -> "${(p * 100).toInt()}% open"
                    d.coverOpen == true -> "Open"
                    d.coverOpen == false -> "Closed"
                    else -> "—"
                }
            }
            "contact" -> if (d.contactOpen == true) "OPEN" else "Closed"
            else -> ""
        }
    }

    private fun deviceIcon(d: HomeyClient.DeviceState): CarIcon {
        val (res, color) = when (type) {
            "lights" -> R.drawable.ic_light to
                (if (isActive(d) && d.available) Badges.AMBER else Badges.NEUTRAL)
            "lock" ->
                if (d.locked == false) R.drawable.ic_lock_open to Badges.AMBER
                else R.drawable.ic_lock to Badges.GREEN
            "blinds" ->
                if (d.coverOpen == true) R.drawable.ic_blinds_open to Badges.AMBER
                else R.drawable.ic_blinds_closed to Badges.NEUTRAL
            "contact" -> R.drawable.ic_sensor to
                (if (d.contactOpen == true) Badges.AMBER else Badges.NEUTRAL)
            "temperature" -> R.drawable.ic_thermo to when {
                d.available && d.temperature != null && d.temperature < 17 -> Badges.BLUE
                d.available && d.temperature != null && d.temperature > 25 -> Badges.RED
                else -> Badges.NEUTRAL
            }
            else -> R.drawable.ic_sensor to Badges.NEUTRAL
        }
        return Badges.badge(carContext, res, if (d.available) color else 0xFF565B60.toInt())
    }

    override fun onGetTemplate(): Template {
        val tpl = ListTemplate.Builder()
        val grouped = devices
            .groupBy { Triple(it.zoneOrder, it.zone.ifEmpty { "Other" }, 0) }
            .toList().sortedBy { it.first.first }
        val sections = Sections("\u2002" + type.uppercase())
        grouped.forEach { (key, devs) ->
            val section = sections.section(sectionHeader(key.second, devs.size, type))
            devs.sortedBy { it.name.lowercase() }.forEach { d ->
                val row = Row.Builder()
                    .setImage(deviceIcon(d), Row.IMAGE_TYPE_LARGE)
                val unreachable = if (!d.available) " · unreachable" else ""
                when (type) {
                    "temperature" -> {
                        val big = d.temperature?.let { String.format("%.1f°", it) } ?: "—"
                        val extra = d.humidity?.let { " · ${it.toInt()}%" } ?: ""
                        row.setTitle(d.name)
                        row.addText("$big$extra$unreachable")
                    }
                    "lock" -> {
                        row.setTitle(d.name)
                        row.addText((if (d.locked == true) "LOCKED" else "UNLOCKED") + unreachable)
                    }
                    else -> {
                        row.setTitle(d.name)
                        row.addText(stateText(d))
                    }
                }
                if (d.available) when (type) {
                    "lights" -> row.setOnClickListener {
                        if (d.dim != null) {
                            screenManager.push(LevelScreen(carContext, d.name, tileId, d.id,
                                if (d.on == true) (d.dim * 100).toInt() else 0, isBlind = false,
                                initialOn = (d.on == true),
                                onPower = { act("toggleDevice", d) }))
                        } else act("toggleDevice", d)
                    }
                    "blinds" -> row.setOnClickListener {
                        val pos = d.position
                        if (pos != null) {
                            screenManager.push(LevelScreen(carContext, d.name, tileId, d.id,
                                (pos * 100).toInt(), isBlind = true))
                        } else act(if (d.coverOpen == true) "closeDevice" else "openDevice", d)
                    }
                    "lock" -> row.setOnClickListener {
                        if (d.locked == true) {
                            screenManager.push(ConfirmScreen(carContext, "Unlock ${d.name}?",
                                onConfirm = { act("unlockDevice", d) }))
                        } else act("lockDevice", d)
                    }
                    else -> { }
                }
                section.add(row)
            }
        }
        if (devices.isEmpty()) tpl.setLoading(true)
        else sections.applyTo(tpl, carContext, "${devices.size} ${typeNoun()}", deviceSummary(devices))
        return tpl
            .setTitle(title)
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun act(action: String, d: HomeyClient.DeviceState) {
        lifecycleScope.launch {
            val ok = try { HomeyClient.sendDeviceAction(tileId, action, d.id) } catch (e: Exception) { false }
            CarToast.makeText(carContext, if (ok) "${d.name} ✓" else "Failed", CarToast.LENGTH_SHORT).show()
            try {
                devices = HomeyClient.fetchState().tiles
                    .firstOrNull { it.tileId == tileId }?.devices ?: devices
            } catch (_: Exception) {}
            invalidate()
        }
    }
}

class LevelScreen(
    carContext: CarContext,
    private val title: String,
    private val tileId: String,
    private val deviceId: String,
    initial: Int,
    private val isBlind: Boolean = false,
    initialOn: Boolean? = null,
    private val onPower: (() -> Unit)? = null,
) : Screen(carContext) {

    private var current = if (initial in 0..100) initial else 50
    private var isOn: Boolean? = initialOn
    private var lastLevel = if (initial in 1..100) initial else 100
    private var busy = false

    private fun send(newLevel: Int, popAfter: Boolean) {
        if (busy) return
        busy = true
        val clamped = newLevel.coerceIn(0, 100)
        lifecycleScope.launch {
            val ok = try { HomeyClient.setLevel(tileId, deviceId, clamped / 100.0) } catch (e: Exception) { false }
            busy = false
            if (ok) {
                current = clamped
                if (isOn != null) isOn = clamped > 0
                if (clamped > 0) lastLevel = clamped
                if (popAfter) {
                    CarToast.makeText(carContext, "$title → $clamped% ✓", CarToast.LENGTH_SHORT).show()
                    screenManager.pop()
                } else invalidate()
            } else {
                CarToast.makeText(carContext, "Failed", CarToast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val up = if (isBlind) "Open more" else "Brighter"
        val down = if (isBlind) "Close more" else "Dimmer"
        val rows = ArrayList<Row.Builder>()
        val on = isOn
        if (on != null && onPower != null) {
            rows.add(Row.Builder()
                .setTitle(if (on) "Turn off" else "Turn on")
                .setOnClickListener {
                    onPower.invoke()
                    if (on) { lastLevel = if (current > 0) current else lastLevel; current = 0 }
                    else current = lastLevel
                    isOn = !on
                    invalidate()
                })
        }
        rows.add(Row.Builder()
            .setTitle("▲ $up · +10%")
            .setOnClickListener { send(current + 10, popAfter = false) })
        rows.add(Row.Builder()
            .setTitle(if (current == 0 && !isBlind) "● Now · Off" else "● Now · $current%"))
        rows.add(Row.Builder()
            .setTitle("▼ $down · −10%")
            .setOnClickListener { send(current - 10, popAfter = false) })
        intArrayOf(100, 0, 75, 50, 25).forEach { lvl ->
            val label = when (lvl) {
                100 -> if (isBlind) "Open · 100%" else "Full · 100%"
                0 -> if (isBlind) "Closed · 0%" else "Off · 0%"
                else -> "$lvl%"
            }
            rows.add(Row.Builder()
                .setTitle(if (lvl == current) "● $label" else label)
                .setOnClickListener { send(lvl, popAfter = false) })
        }
        val list = ItemList.Builder()
        list.addItem(leadRow(carContext, rows.size + 1, if (isBlind) "Position" else "Level",
            if (isBlind) "Open · Closed · 75 · 50 · 25" else "Full · Off · 75 · 50 · 25"))
        rows.forEach { list.addItem(it.build()) }
        return ListTemplate.Builder()
            .setTitle("$title · $current%")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}

class ConfirmScreen(
    carContext: CarContext,
    private val question: String,
    private val onConfirm: () -> Unit,
    private val detail: String = "Homey will confirm once the device reports its new state.",
) : Screen(carContext) {

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(detail)
            .setTitle(question)
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder()
                    .setTitle("Yes")
                    .setBackgroundColor(CarColor.YELLOW)
                    .setOnClickListener {
                        onConfirm()
                        screenManager.pop()
                    }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle("Cancel")
                    .setOnClickListener { screenManager.pop() }
                    .build()
            )
            .build()
}

class ScenesScreen(
    carContext: CarContext,
    private val scenes: List<HomeyClient.Scene>,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        scenes.forEach { scene ->
            list.addItem(
                Row.Builder()
                    .setTitle(scene.label)
                    .setOnClickListener { run(scene) }
                    .build()
            )
        }
        if (scenes.isEmpty()) {
            list.setNoItemsMessage("No scenes whitelisted — add them in the Homey app settings.")
        }
        return ListTemplate.Builder()
            .setTitle("Scenes")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun run(scene: HomeyClient.Scene) {
        lifecycleScope.launch {
            val ok = try { HomeyClient.runScene(scene.flowId) } catch (e: Exception) { false }
            CarToast.makeText(
                carContext,
                if (ok) "${scene.label} ✓" else "Failed to run ${scene.label}",
                CarToast.LENGTH_SHORT
            ).show()
        }
    }
}
