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
            ChargerInfo(name = "Estabanell - Rambla Hospital Vic", address = "Rambla Hospital, Vic", latitude = 41.9298, longitude = 2.2530, operatorName = "Estabanell", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Municipal - Sucre Vic", address = "Paseo Generalitat, Vic", latitude = 41.9245, longitude = 2.2482, operatorName = "Ajuntament de Vic", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Vic Estacion Autobuses", address = "Ctra. de Ribes, Vic", latitude = 41.9331, longitude = 2.2580, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Public - Manlleu", address = "Paseo Sant Joan, Manlleu", latitude = 42.0012, longitude = 2.2855, operatorName = "Ajuntament de Manlleu", officialAppPackage = "com.electromaps.app"),

            // --- MATARO & MARESME ---
            ChargerInfo(name = "Punt Municipal - Paseo Maritimo Mataro", address = "Paseo Maritimo, Mataro", latitude = 41.5332, longitude = 2.4450, operatorName = "Ajuntament de Mataro", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Mataro Parc", address = "Cami Ral Merce, Mataro", latitude = 41.5450, longitude = 2.4312, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Public - Ayuntamiento Mataro", address = "Plaza Ayuntamiento, Mataro", latitude = 41.5391, longitude = 2.4445, operatorName = "Ajuntament de Mataro", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Argentona Centre", address = "Plaza Nova, Argentona", latitude = 41.5542, longitude = 2.4045, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Municipal - Vilassar de Mar", address = "Paseo del Mar, Vilassar de Mar", latitude = 41.5032, longitude = 2.3891, operatorName = "Ajuntament", officialAppPackage = "com.electromaps.app"),

            // --- BARCELONA Y OTROS ---
            ChargerInfo(name = "BSM - Plaza Catalunya", address = "Plaza Catalunya, Barcelona", latitude = 41.3870, longitude = 2.1700, totalSockets = 4, availableSockets = 4, pricePerKwh = "Gratis / BSM", operatorName = "BSM Endolla", officialAppPackage = "cat.bcn.smou"),
            ChargerInfo(name = "BSM - Sagrada Familia", address = "Calle Mallorca, Barcelona", latitude = 41.4036, longitude = 2.1744, pricePerKwh = "Gratis / BSM", operatorName = "BSM Endolla", officialAppPackage = "cat.bcn.smou"),
            ChargerInfo(name = "Punt Recarga Glorias", address = "Av. Diagonal, Barcelona", latitude = 41.4025, longitude = 2.1895, operatorName = "Ajuntament de Barcelona", officialAppPackage = "cat.bcn.smou"),
            ChargerInfo(name = "EVcharge - Cornella Centre", address = "Av. del Parc, Cornella", latitude = 41.3578, longitude = 2.0712, operatorName = "EVcharge", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Recarga Castelldefels", address = "Av. de la Platja, Castelldefels", latitude = 41.2685, longitude = 1.9805, operatorName = "Ajuntament", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Punt Recarga Manresa", address = "Paseo Pere III, Manresa", latitude = 41.7265, longitude = 1.8260, operatorName = "Ajuntament de Manresa", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Ajuntament Girona - Devesa", address = "Paseo de la Devesa, Girona", latitude = 41.9852, longitude = 2.8185, operatorName = "Ajuntament de Girona", officialAppPackage = "com.electromaps.app")
        )

        for (item in fallback) {
            if (!allChargers.any { Math.abs(it.latitude - item.latitude) < 0.0001 && Math.abs(it.longitude - item.longitude) < 0.0001 }) {
                allChargers.add(item)
            }
        }
    }

    private fun updateMarkers() {
        val chipGratuitos: Chip? = findViewById(R.id.chipGratuitos)
        val chipTipo2: Chip? = findViewById(R.id.chipTipo2)
        val chipFavoritos: Chip? = findViewById(resources.getIdentifier("chipFavoritos", "id", packageName))

        val onlyFree = chipGratuitos?.isChecked ?: false
        val onlyType2 = chipTipo2?.isChecked ?: false
        val onlyFavorites = chipFavoritos?.isChecked ?: false

        for (marker in activeMarkers) {
            map.overlays.remove(marker)
        }
        activeMarkers.clear()

        val bottomSheet: View? = findViewById(R.id.bottomSheet)
        val tvNombre: TextView? = findViewById(R.id.tvNombreEstacion)
        val tvDireccion: TextView? = findViewById(R.id.tvDireccion)

        for (charger in allChargers) {
            if (onlyFree && !charger.isFree) continue
            if (onlyType2 && !charger.isType2) continue
            if (onlyFavorites && !charger.isFavorite) continue

            val marker = Marker(map)
            marker.position = GeoPoint(charger.latitude, charger.longitude)
            marker.title = charger.name

            val markerColor = when (charger.status) {
                AvailabilityStatus.ALL_AVAILABLE -> Color.parseColor("#2E7D32")       // Verde
                AvailabilityStatus.PARTIALLY_AVAILABLE -> Color.parseColor("#FFB300") // Amarillo
                AvailabilityStatus.FULLY_OCCUPIED -> Color.parseColor("#D32F2F")     // Rojo
                AvailabilityStatus.OUT_OF_SERVICE -> Color.parseColor("#212121")     // Negro
                AvailabilityStatus.STATIC_CARCASA -> Color.parseColor("#1565C0")     // Azul
            }

            marker.icon = createCustomPinIcon(markerColor)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            marker.setOnMarkerClickListener { m, _ ->
                selectedCharger = charger

                val favIcon = if (charger.isFavorite) "Favorito [Favorito]" else "Anadir a Favoritos"
                val statusText = "Punto Fisico / Carcasa Estatica (Ver en App Oficial)"

                tvNombre?.text = "${charger.name} ${if (charger.isFavorite) "Favorito" else ""}"
                tvDireccion?.text = "Operador: ${charger.operatorName}\n${charger.address}\n$statusText\n${charger.pricePerKwh} - ${charger.powerKw}\n\n$favIcon"

                if (bottomSheet != null) {
                    val behavior = BottomSheetBehavior.from(bottomSheet)
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
                m.showInfoWindow()
                true
            }

            map.overlays.add(marker)
            activeMarkers.add(marker)
        }

        map.invalidate()
    }

    private fun showChargerActionMenu() {
        val charger = selectedCharger
        if (charger == null) {
            Toast.makeText(this, "Selecciona un cargador en el mapa primero", Toast.LENGTH_SHORT).show()
            return
        }

        val favText = if (charger.isFavorite) "Quitar de Favoritos" else "Anadir a Favoritos"
        val options = arrayOf("Navegar con Google Maps", "Navegar con Waze", "Abrir App Oficial del Operador", favText)
        
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle(charger.name)
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> openGoogleMapsNavigation(charger)
                1 -> openWazeNavigation(charger)
                2 -> openOfficialApp(charger.officialAppPackage)
                3 -> toggleFavorite(charger)
            }
        }
        builder.show()
    }

    private fun toggleFavorite(charger: ChargerInfo) {
        charger.isFavorite = !charger.isFavorite
        val msg = if (charger.isFavorite) "Anadido a favoritos" else "Eliminado de favoritos"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        updateMarkers()
    }

    private fun openOfficialApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
                } catch (e: Exception) {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la aplicacion oficial", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGoogleMapsNavigation(charger: ChargerInfo) {
        val gmmIntentUri = Uri.parse("google.navigation:q=${charger.latitude},${charger.longitude}")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${charger.latitude},${charger.longitude}"))
            startActivity(browserIntent)
        }
    }

    private fun openWazeNavigation(charger: ChargerInfo) {
        try {
            val wazeUri = Uri.parse("https://waze.com/ul?ll=${charger.latitude},${charger.longitude}&navigate=yes")
            val wazeIntent = Intent(Intent.ACTION_VIEW, wazeUri)
            startActivity(wazeIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Waze no esta instalado", Toast.LENGTH_SHORT).show()
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=${charger.latitude},${charger.longitude}&navigate=yes"))
            startActivity(browserIntent)
        }
    }

    private fun createCustomPinIcon(colorInt: Int): Drawable {
        val density = resources.displayMetrics.density
        val width = (42 * density).toInt()
        val height = (50 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = colorInt

        val baseRect = RectF(6 * density, 44 * density, 36 * density, 48 * density)
        canvas.drawRoundRect(baseRect, 2 * density, 2 * density, paint)

        val bodyRect = RectF(8 * density, 6 * density, 28 * density, 44 * density)
        canvas.drawRoundRect(bodyRect, 4 * density, 4 * density, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * density
        val path = Path()
        path.moveTo(28 * density, 26 * density)
        path.lineTo(34 * density, 26 * density)
        path.lineTo(34 * density, 16 * density)
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        val plugRect = RectF(30 * density, 8 * density, 38 * density, 16 * density)
        canvas.drawRoundRect(plugRect, 2 * density, 2 * density, paint)
        canvas.drawRect(32 * density, 3 * density, 34 * density, 8 * density, paint)
        canvas.drawRect(36 * density, 3 * density, 38 * density, 8 * density, paint)

        paint.color = Color.WHITE
        paint.textSize = 12 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("E", 18 * density, 21 * density, paint)
        canvas.drawText("V", 18 * density, 36 * density, paint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun createCustomUserPin(): Drawable {
        val density = resources.displayMetrics.density
        val width = (36 * density).toInt()
        val height = (56 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        paint.style = Paint.Style.FILL
        val bodyRect = RectF(6 * density, 4 * density, 30 * density, 52 * density)
        canvas.drawRoundRect(bodyRect, 10 * density, 12 * density, paint)

        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#212121")
        paint.strokeWidth = 1.8f * density
        canvas.drawRoundRect(bodyRect, 10 * density, 12 * density, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#333333")
        canvas.drawRoundRect(RectF(2 * density, 18 * density, 6 * density, 24 * density), 2 * density, 2 * density, paint)
        canvas.drawRoundRect(RectF(30 * density, 18 * density, 34 * density, 24 * density), 2 * density, 2 * density, paint)

        paint.color = Color.parseColor("#1A1A1A")
        val windshieldRect = RectF(10 * density, 14 * density, 26 * density, 24 * density)
        canvas.drawRoundRect(windshieldRect, 4 * density, 4 * density, paint)

        val roofRect = RectF(9 * density, 22 * density, 27 * density, 44 * density)
        canvas.drawRoundRect(roofRect, 3 * density, 3 * density, paint)

        paint.color = Color.parseColor("#D32F2F")
        canvas.drawRect(8 * density, 49 * density, 13 * density, 51 * density, paint)
        canvas.drawRect(23 * density, 49 * density, 28 * density, 51 * density, paint)

        return BitmapDrawable(resources, bitmap)
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        locationProvider?.stopLocationProvider()
    }
