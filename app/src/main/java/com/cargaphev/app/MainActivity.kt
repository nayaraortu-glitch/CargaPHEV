package com.cargaphev.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private val allChargers = mutableListOf<ChargerInfo>()
    private val activeMarkers = mutableListOf<Marker>()
    private var selectedCharger: ChargerInfo? = null
    private var myLocationMarker: Marker? = null
    private var locationProvider: org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider? = null
    private var isFirstLocationUpdate = true
    private var isRefreshing = false

    enum class AvailabilityStatus {
        ALL_AVAILABLE,      // Verde: Todas las tomas libres
        PARTIALLY_AVAILABLE,// Amarillo: Alguna toma ocupada
        FULLY_OCCUPIED,     // Rojo: Todas las tomas ocupadas
        OUT_OF_SERVICE,     // Negro: Fuera de servicio / Averiado
        STATIC_CARCASA      // Azul: Punto físico verificado (sin tiempo real)
    }

    data class ChargerInfo(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val isFree: Boolean = true,
        val isType2: Boolean = true,
        val totalSockets: Int = 2,
        var availableSockets: Int = 2,
        var status: AvailabilityStatus = AvailabilityStatus.STATIC_CARCASA,
        val powerKw: String = "22 kW",
        val pricePerKwh: String = "Gratis",
        val operatorName: String = "Red Publica",
        val officialAppPackage: String = "com.electromaps.app",
        var isFavorite: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        showWelcomeSplashOverlay()

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true

        val mapController = map.controller
        mapController.setZoom(13.0)

        val defaultCenter = GeoPoint(41.5463, 2.1086)
        mapController.setCenter(defaultCenter)

        setupCompass()
        checkLocationPermissions()

        val btnLocation: FloatingActionButton? = findViewById(R.id.btnCenterLocation)
        btnLocation?.setOnClickListener {
            val center = myLocationMarker?.position ?: map.mapCenter as? GeoPoint ?: defaultCenter
            mapController.animateTo(center)
            mapController.setZoom(15.0)
            Toast.makeText(this, "Centrado en tu ubicacion", Toast.LENGTH_SHORT).show()
        }

        val btnRefreshId = resources.getIdentifier("btnRefresh", "id", packageName)
        if (btnRefreshId != 0) {
            findViewById<View>(btnRefreshId)?.setOnClickListener {
                loadCataloniaOfficialChargers()
            }
        } else {
            btnLocation?.setOnLongClickListener {
                loadCataloniaOfficialChargers()
                true
            }
        }

        val chipGratuitos: Chip? = findViewById(R.id.chipGratuitos)
        val chipTipo2: Chip? = findViewById(R.id.chipTipo2)
        val chipFavoritos: Chip? = findViewById(resources.getIdentifier("chipFavoritos", "id", packageName))

        chipGratuitos?.setOnCheckedChangeListener { _, _ -> updateMarkers() }
        chipTipo2?.setOnCheckedChangeListener { _, _ -> updateMarkers() }
        chipFavoritos?.setOnCheckedChangeListener { _, _ -> updateMarkers() }

        val btnNavegarId = resources.getIdentifier("btnNavegar", "id", packageName)
        if (btnNavegarId != 0) {
            findViewById<View>(btnNavegarId)?.setOnClickListener { showChargerActionMenu() }
        }

        // 1. CARGA INSTANTÁNEA LOCAL
        loadFallbackDirectly()
        updateMarkers()

        // 2. ACTUALIZACIÓN EN SEGUNDO PLANO
        loadCataloniaOfficialChargers()
    }

    private fun showWelcomeSplashOverlay() {
        val rootLayout = findViewById<View>(android.R.id.content) as? android.view.ViewGroup ?: return
        
        val splashView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            isClickable = true
            isFocusable = true
            setPadding(60, 60, 60, 60)

            addView(TextView(context).apply {
                text = "⚡ CargaPHEV Espana & Catalunya"
                textSize = 24f
                setTextColor(Color.parseColor("#1976D2"))
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })

            addView(TextView(context).apply {
                text = "Mapa Inteligente con Enlace a Apps Oficiales y Favoritos"
                textSize = 14f
                setTextColor(Color.parseColor("#555555"))
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 25)
            })

            addView(TextView(context).apply {
                text = "📖 GUIA DE ESTADOS Y COLORES:"
                textSize = 13f
                setTextColor(Color.parseColor("#333333"))
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, 10)
            })

            val legends = listOf(
                "🟢 VERDE: Todas las tomas libres.",
                "🟡 AMARILLO: Ocupacion parcial.",
                "🔴 ROJO: Todas las tomas ocupadas.",
                "⚫ NEGRO: Fuera de servicio / Averiado.",
                "🔵 AZUL: Punto fisico verificado (Usa la App Oficial para ver estado en vivo)."
            )

            for (legend in legends) {
                addView(TextView(context).apply {
                    text = legend
                    textSize = 12f
                    setTextColor(Color.parseColor("#444444"))
                    setPadding(0, 4, 0, 4)
                })
            }

            addView(TextView(context).apply {
                text = "\n👉 Toca en cualquier lugar para comenzar"
                textSize = 14f
                setTextColor(Color.parseColor("#2E7D32"))
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 30, 0, 0)
            })

            setOnClickListener {
                rootLayout.removeView(this)
            }
        }

        rootLayout.addView(splashView, android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }

    private fun setupCompass() {
        try {
            val rotationGestureOverlay = org.osmdroid.views.overlay.gestures.RotationGestureOverlay(map)
            rotationGestureOverlay.isEnabled = true
            map.setMultiTouchControls(true)
            map.overlays.add(rotationGestureOverlay)

            val btnCompass = findViewById<FloatingActionButton>(resources.getIdentifier("btnCompass", "id", packageName))
            btnCompass?.setOnClickListener {
                map.controller.animateTo(map.mapCenter)
                map.setMapOrientation(0f)
                Toast.makeText(this, "Mapa orientado al Norte", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkLocationPermissions() {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)

        if (fineLocation != PackageManager.PERMISSION_GRANTED || coarseLocation != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            startCustomLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCustomLocationUpdates()
        }
    }

    private fun startCustomLocationUpdates() {
        try {
            locationProvider = org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider(this)
            locationProvider?.startLocationProvider { location, _ ->
                if (location != null) {
                    val userPoint = GeoPoint(location.latitude, location.longitude)
                    updateUserMarker(userPoint)

                    if (isFirstLocationUpdate) {
                        isFirstLocationUpdate = false
                        map.controller.animateTo(userPoint)
                        map.controller.setZoom(15.0)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateUserMarker(point: GeoPoint) {
        if (myLocationMarker == null) {
            myLocationMarker = Marker(map)
            myLocationMarker?.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            myLocationMarker?.title = "Tu coche (Ubicacion actual)"
            myLocationMarker?.icon = createCustomUserPin()
            map.overlays.add(myLocationMarker)
        }
        myLocationMarker?.position = point
        map.invalidate()
    }

    private fun loadCataloniaOfficialChargers() {
        if (isRefreshing) {
            Toast.makeText(this, "Actualizacion ya en curso...", Toast.LENGTH_SHORT).show()
            return
        }
        isRefreshing = true
        Toast.makeText(this, "Actualizando cargadores online...", Toast.LENGTH_SHORT).show()

        GlobalScope.launch(Dispatchers.IO) {
            val fetchedList = mutableListOf<ChargerInfo>()
            var success = false
            var connection: HttpURLConnection? = null
            try {
                val apiUrl = "https://overpass-api.de/api/interpreter?data=" +
                        "[out:json][timeout:25];" +
                        "node[\"amenity\"=\"charging_station\"](36.0,-9.3,43.8,4.5);" +
                        "out%20body%20300;"

                connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 12000
                connection.readTimeout = 12000
                connection.setRequestProperty("User-Agent", "CargaPHEV-AppSmartphone")
                connection.setRequestProperty("Cache-Control", "no-cache")

                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(responseText)
                    val elements = root.optJSONArray("elements") ?: JSONArray()

                    for (i in 0 until elements.length()) {
                        val node = elements.getJSONObject(i)
                        val lat = node.optDouble("lat", 0.0)
                        val lon = node.optDouble("lon", 0.0)
                        val tags = node.optJSONObject("tags") ?: JSONObject()

                        val name = tags.optString("name", tags.optString("operator", "Punto Recarga Publico"))
                        val operator = tags.optString("operator", "Red Publica").lowercase()
                        val capacity = tags.optString("capacity", "2").toIntOrNull() ?: 2
                        
                        val isFree = operator.contains("ajuntament") || 
                                     operator.contains("municipal") || 
                                     operator.contains("estabanell") ||
                                     tags.optString("fee", "") == "no"

                        val appPackage = when {
                            operator.contains("bcn") || operator.contains("smou") || operator.contains("bsm") -> "cat.bcn.smou"
                            operator.contains("iberdrola") -> "com.iberdrola.recargapublica"
                            operator.contains("endesa") -> "com.enelx.juicypass"
                            operator.contains("wenea") -> "com.wenea.evcharging"
                            else -> "com.electromaps.app"
                        }

                        if (lat != 0.0 && lon != 0.0) {
                            fetchedList.add(
                                ChargerInfo(
                                    name = name,
                                    address = "Operador: $operator",
                                    latitude = lat,
                                    longitude = lon,
                                    isFree = isFree,
                                    totalSockets = capacity,
                                    availableSockets = capacity,
                                    status = AvailabilityStatus.STATIC_CARCASA,
                                    powerKw = tags.optString("socket:type2:output", "22 kW"),
                                    pricePerKwh = if (isFree) "Gratis" else "De pago",
                                    operatorName = tags.optString("operator", "Red Publica"),
                                    officialAppPackage = appPackage
                                )
                            )
                        }
                    }
                    if (fetchedList.isNotEmpty()) {
                        success = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                connection?.disconnect()
                isRefreshing = false
            }

            withContext(Dispatchers.Main) {
                if (success && fetchedList.isNotEmpty()) {
                    var addedCount = 0
                    for (newCharger in fetchedList) {
                        val existing = allChargers.find { 
                            Math.abs(it.latitude - newCharger.latitude) < 0.0002 && 
                            Math.abs(it.longitude - newCharger.longitude) < 0.0002 
                        }
                        if (existing != null) {
                            existing.status = newCharger.status
                            existing.availableSockets = newCharger.availableSockets
                        } else {
                            allChargers.add(newCharger)
                            addedCount++
                        }
                    }
                    updateMarkers()
                    Toast.makeText(this@MainActivity, "Actualizado: $addedCount puntos nuevos en Espana", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "No se pudieron actualizar los puntos (sin conexion)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadFallbackDirectly() {
        val fallback = listOf(
            // --- CANOVELLES ---
            ChargerInfo(name = "Ajuntament Canovelles - Plaza", address = "Plaza del Ayuntamiento, Canovelles", latitude = 41.6152, longitude = 2.2798, operatorName = "Ajuntament de Canovelles", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Carretera Ribes", address = "Ctra. de Ribes, Canovelles", latitude = 41.6163, longitude = 2.2789, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Pabellon Canovelles", address = "Paseo de la Ribera, Canovelles", latitude = 41.6118, longitude = 2.2818, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Municipal - Calle Pilar", address = "Calle Verge del Pilar, Canovelles", latitude = 41.6141, longitude = 2.2765, operatorName = "Ajuntament", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Ajuntament Canovelles - Calle Rec", address = "Calle del Rec, Canovelles", latitude = 41.6158, longitude = 2.2774, operatorName = "Ajuntament", officialAppPackage = "com.electromaps.app"),

            // --- GRANOLLERS & VALLES ---
            ChargerInfo(name = "Estabanell - Calle Rec Granollers", address = "Calle del Rec 26, Granollers", latitude = 41.6082, longitude = 2.2885, operatorName = "Estabanell", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Municipal - Josep Umbert", address = "Calle Josep Umbert, Granollers", latitude = 41.6095, longitude = 2.2890, operatorName = "Ajuntament de Granollers", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Estacio Publica - Porxada", address = "Plaza de la Porxada, Granollers", latitude = 41.6063, longitude = 2.2878, operatorName = "Ajuntament de Granollers", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Recarga Rambla Granollers", address = "Rambla Josep Tarradellas, Granollers", latitude = 41.6050, longitude = 2.2872, operatorName = "Red Publica", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Eix Macia Sabadell", address = "Av. Francesc Macia, Sabadell", latitude = 41.5518, longitude = 2.0998, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Estabanell - Mollet del Valles", address = "Av. Llibertat, Mollet del Valles", latitude = 41.5398, longitude = 2.2132, operatorName = "Estabanell", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Sant Cugat Volpelleres", address = "Estacio FGC Volpelleres, Sant Cugat", latitude = 41.4812, longitude = 2.0715, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Ajuntament Terrassa - Rambla Egara", address = "Rambla Egara, Terrassa", latitude = 41.5621, longitude = 2.0084, operatorName = "Ajuntament de Terrassa", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Carga Parc Valles", address = "Av. Textil, Terrassa", latitude = 41.5530, longitude = 2.0315, totalSockets = 4, availableSockets = 4, operatorName = "Parc Valles", officialAppPackage = "com.electromaps.app"),

            // --- VIC & OSONA ---
            ChargerInfo(name = "Estabanell - Rambla Hospital Vic", address = "Ramb
