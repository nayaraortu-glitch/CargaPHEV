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
import java.util.Locale

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
        ALL_AVAILABLE,
        PARTIALLY_AVAILABLE,
        FULLY_OCCUPIED,
        OUT_OF_SERVICE,
        STATIC_CARCASA
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
        mapController.setZoom(15.0)

        // Ubicación exacta inicial en Granollers
        val defaultCenter = GeoPoint(41.6080, 2.2880)
        mapController.setCenter(defaultCenter)

        setupCompass()
        checkLocationPermissions()

        val btnLocation: FloatingActionButton? = findViewById(R.id.btnCenterLocation)
        btnLocation?.setOnClickListener {
            val center = myLocationMarker?.position ?: map.mapCenter as? GeoPoint ?: defaultCenter
            mapController.animateTo(center)
            mapController.setZoom(16.0)
            Toast.makeText(this, "Centrado en tu ubicación", Toast.LENGTH_SHORT).show()
        }

        // Mantener pulsado el botón de ubicación para buscar en la zona visible actual ("Buscar en esta zona")
        btnLocation?.setOnLongClickListener {
            loadChargersForCurrentMapBoundingBox()
            true
        }

        val btnRefreshId = resources.getIdentifier("btnRefresh", "id", packageName)
        if (btnRefreshId != 0) {
            findViewById<View>(btnRefreshId)?.setOnClickListener {
                loadChargersForCurrentMapBoundingBox()
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

        loadFallbackDirectly()
        updateMarkers()
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
                text = "⚡ CargaPHEV - Mapa Inteligente"
                textSize = 24f
                setTextColor(Color.parseColor("#1976D2"))
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })

            addView(TextView(context).apply {
                text = "Mantén pulsado el botón de ubicación para buscar cargadores en esta zona"
                textSize = 14f
                setTextColor(Color.parseColor("#555555"))
                gravity = Gravity.CENTER
                setPadding(0, 10, 0, 25)
            })

            addView(TextView(context).apply {
                text = "👉 Toca para comenzar"
                textSize = 14f
                setTextColor(Color.parseColor("#2E7D32"))
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
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
                        map.controller.setZoom(16.0)
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
            myLocationMarker?.title = "Tu Toyota C-HR"
            myLocationMarker?.icon = createCustomUserPin()
            map.overlays.add(myLocationMarker)
        }
        myLocationMarker?.position = point
        map.invalidate()
    }

    private fun loadChargersForCurrentMapBoundingBox() {
        if (isRefreshing) {
            Toast.makeText(this, "Búsqueda en curso...", Toast.LENGTH_SHORT).show()
            return
        }
        isRefreshing = true
        Toast.makeText(this, "Buscando cargadores en esta zona...", Toast.LENGTH_SHORT).show()

        val boundingBox = map.boundingBox
        // Usamos Locale.US para asegurar el punto decimal (.) en lugar de coma (,)
        val south = String.format(Locale.US, "%.4f", boundingBox.latSouth - 0.03)
        val north = String.format(Locale.US, "%.4f", boundingBox.latNorth + 0.03)
        val west = String.format(Locale.US, "%.4f", boundingBox.lonWest - 0.03)
        val east = String.format(Locale.US, "%.4f", boundingBox.lonEast + 0.03)

        GlobalScope.launch(Dispatchers.IO) {
            val fetchedList = mutableListOf<ChargerInfo>()
            var success = false
            var connection: HttpURLConnection? = null
            try {
                val apiUrl = "https://overpass-api.de/api/interpreter?data=" +
                        "[out:json][timeout:25];" +
                        "node[\"amenity\"=\"charging_station\"]($south,$west,$north,$east);" +
                        "out%20body%20300;"

                connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "CargaPHEV-AppSmartphone")

                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(responseText)
                    val elements = root.optJSONArray("elements") ?: JSONArray()

                    for (i in 0 until elements.length()) {
                        val node = elements.getJSONObject(i)
                        val lat = node.optDouble("lat", 0.0)
                        val lon = node.optDouble("lon", 0.0)
                        val tags = node.optJSONObject("tags") ?: JSONObject()

                        val name = tags.optString("name", tags.optString("operator", "Punto Recarga"))
                        val operator = tags.optString("operator", "Red Publica")
                        val operatorLower = operator.lowercase()
                        val capacity = tags.optString("capacity", "2").toIntOrNull() ?: 2
                        
                        val isFree = operatorLower.contains("ajuntament") || 
                                     operatorLower.contains("municipal") || 
                                     operatorLower.contains("estabanell") ||
                                     tags.optString("fee", "") == "no"

                        val appPackage = when {
                            operatorLower.contains("bcn") || operatorLower.contains("smou") || operatorLower.contains("bsm") -> "cat.bcn.smoubcn"
                            operatorLower.contains("estabanell") -> "Estabanell Energia"
                            operatorLower.contains("iberdrola") -> "com.iberdrola.recargapublica"
                            operatorLower.contains("endesa") -> "com.enelx.juicypass"
                            operatorLower.contains("wenea") -> "com.wenea.evcharging"
                            operatorLower.contains("evcharge") -> "com.EVcharge.app"
                            else -> operator
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
                                    operatorName = operator,
                                    officialAppPackage = appPackage
                                )
                            )
                        }
                    }
                    if (fetchedList.isNotEmpty()) success = true
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
                            Math.abs(it.latitude - newCharger.latitude) < 0.0001 && 
                            Math.abs(it.longitude - newCharger.longitude) < 0.0001 
                        }
                        if (existing == null) {
                            allChargers.add(newCharger)
                            addedCount++
                        }
                    }
                    updateMarkers()
                    Toast.makeText(this@MainActivity, "¡$addedCount nuevos puntos encontrados en esta zona!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "No hay nuevos puntos en esta vista del mapa", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadFallbackDirectly() {
        val fallback = listOf(
            // --- CANOVELLES (Coordenadas exactas y verificadas) ---
            ChargerInfo(name = "Ajuntament Canovelles - Plaza", address = "Plaça de l'Ajuntament, Canovelles", latitude = 41.61525, longitude = 2.27985, operatorName = "Ajuntament de Canovelles", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "EVcharge - Carretera Ribes", address = "Ctra. de Ribes, Canovelles", latitude = 41.61630, longitude = 2.27890, operatorName = "EVcharge", officialAppPackage = "com.EVcharge.app"),
            ChargerInfo(name = "EVcharge - Pabellon Canovelles", address = "Passeig de la Ribera, Canovelles", latitude = 41.61180, longitude = 2.28180, operatorName = "EVcharge", officialAppPackage = "com.EVcharge.app"),
            ChargerInfo(name = "Punt Municipal - Calle Pilar", address = "Carrer Verge del Pilar, Canovelles", latitude = 41.61410, longitude = 2.27650, operatorName = "Ajuntament", officialAppPackage = "com.electromaps.app"),

            // --- GRANOLLERS (Coordenadas exactas y verificadas) ---
            ChargerInfo(name = "Estabanell - Calle Rec Granollers", address = "Carrer del Rec, 26, Granollers", latitude = 41.60820, longitude = 2.28850, operatorName = "Estabanell", officialAppPackage = "Estabanell Energia"),
            ChargerInfo(name = "Punt Municipal - Josep Umbert", address = "Carrer de Josep Umbert, Granollers", latitude = 41.60950, longitude = 2.28900, operatorName = "Ajuntament de Granollers", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Estacio Publica - Porxada", address = "Plaça de la Porxada, Granollers", latitude = 41.60630, longitude = 2.28780, operatorName = "Ajuntament de Granollers", officialAppPackage = "com.electromaps.app"),
            ChargerInfo(name = "Recarga Rambla Granollers", address = "Rambla d'en Josep Tarradellas, Granollers", latitude = 41.60500, longitude = 2.28720, operatorName = "Red Publica", officialAppPackage = "com.electromaps.app"),

            // --- BARCELONA Y OTROS ---
            ChargerInfo(name = "BSM - Plaza Catalunya", address = "Plaça Catalunya, Barcelona", latitude = 41.38700, longitude = 2.17000, totalSockets = 4, availableSockets = 4, pricePerKwh = "Gratis / BSM", operatorName = "BSM Endolla", officialAppPackage = "cat.bcn.smoubcn"),
            ChargerInfo(name = "EVcharge - Cornella Centre", address = "Av. del Parc, Cornellà", latitude = 41.35780, longitude = 2.07120, operatorName = "EVcharge", officialAppPackage = "com.EVcharge.app")
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
                AvailabilityStatus.ALL_AVAILABLE -> Color.parseColor("#2E7D32")
                AvailabilityStatus.PARTIALLY_AVAILABLE -> Color.parseColor("#FFB300")
                AvailabilityStatus.FULLY_OCCUPIED -> Color.parseColor("#D32F2F")
                AvailabilityStatus.OUT_OF_SERVICE -> Color.parseColor("#212121")
                AvailabilityStatus.STATIC_CARCASA -> Color.parseColor("#1565C0")
            }

            marker.icon = createCustomPinIcon(markerColor)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            marker.setOnMarkerClickListener { m, _ ->
                selectedCharger = charger

                val favIcon = if (charger.isFavorite) "⭐ [Favorito]" else "☆ [Añadir a Favoritos]"
                tvNombre?.text = "${charger.name} ${if (charger.isFavorite) "⭐" else ""}"
                tvDireccion?.text = "Operador: ${charger.operatorName}\n${charger.address}\nPunto Oficial (Ver en App)\n${charger.pricePerKwh} - ${charger.powerKw}\n\n$favIcon"

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
            Toast.makeText(this, "Selecciona un cargador primero", Toast.LENGTH_SHORT).show()
            return
        }

        val favText = if (charger.isFavorite) "Quitar de Favoritos" else "Añadir a Favoritos"
        val options = arrayOf("Navegar con Google Maps", "Navegar con Waze", "Abrir App Oficial", favText)
        
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
        Toast.makeText(this, if (charger.isFavorite) "Añadido a favoritos" else "Eliminado", Toast.LENGTH_SHORT).show()
        updateMarkers()
    }

    private fun openOfficialApp(appPkgOrName: String) {
        try {
            if (appPkgOrName.contains(".")) {
                val intent = packageManager.getLaunchIntentForPackage(appPkgOrName)
                if (intent != null) {
                    startActivity(intent)
                    return
                }
            }
            val storeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$appPkgOrName"))
            startActivity(storeIntent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$appPkgOrName&c=apps")))
            } catch (ex: Exception) {
                Toast.makeText(this, "No se pudo abrir la tienda de aplicaciones", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openGoogleMapsNavigation(charger: ChargerInfo) {
        val gmmIntentUri = Uri.parse("google.navigation:q=${charger.latitude},${charger.longitude}")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
        mapIntent.setPackage("com.google.android.apps.maps")
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${charger.latitude},${charger.longitude}")))
        }
    }

    private fun openWazeNavigation(charger: ChargerInfo) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://waze.com/ul?ll=${charger.latitude},${charger.longitude}&navigate=yes")))
        } catch (e: Exception) {
            Toast.makeText(this, "Waze no está instalado", Toast.LENGTH_SHORT).show()
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
        paint.strokeWidth = 2.5f * density
        val path = Path()
        path.moveTo(28 * density, 26 * density)
        path.lineTo(34 * density, 26 * density)
        path.lineTo(34 * density, 16 * density)
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.FILL
        val plugRect = RectF(30 * density, 8 * density, 38 * density, 16 * density)
        canvas.drawRoundRect(plugRect, 2 * density, 2 * density, paint)

        paint.color = Color.WHITE
        paint.textSize = 11 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("E", 18 * density, 21 * density, paint)
        canvas.drawText("V", 18 * density, 36 * density, paint)

        return BitmapDrawable(resources, bitmap)
    }

    // Diseño vectorial detallado en Canvas para el Toyota C-HR (Vista Cenital: carrocería blanca, techo y lunas negras, pilotos traseros LED)
    private fun createCustomUserPin(): Drawable {
        val density = resources.displayMetrics.density
        val width = (38 * density).toInt()
        val height = (64 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 1. Espejos retrovisores laterales
        paint.color = Color.parseColor("#1A1A1A")
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(1 * density, 22 * density, 7 * density, 28 * density), 2 * density, 2 * density, paint)
        canvas.drawRoundRect(RectF(31 * density, 22 * density, 37 * density, 28 * density), 2 * density, 2 * density, paint)

        // 2. Carrocería principal (Blanco C-HR)
        paint.color = Color.parseColor("#FAFAFA")
        val carPath = Path().apply {
            moveTo(19 * density, 2 * density)
            cubicTo(11 * density, 4 * density, 6 * density, 12 * density, 6 * density, 22 * density)
            cubicTo(5 * density, 36 * density, 6 * density, 52 * density, 9 * density, 60 * density)
            lineTo(29 * density, 60 * density)
            cubicTo(32 * density, 52 * density, 33 * density, 36 * density, 32 * density, 22 * density)
            cubicTo(32 * density, 12 * density, 27 * density, 4 * density, 19 * density, 2 * density)
            close()
        }
        canvas.drawPath(carPath, paint)

        // Contorno de la carrocería
        paint.style = Paint.Style.STROKE
        paint.color = Color.parseColor("#B0BEC5")
        paint.strokeWidth = 1.2f * density
        canvas.drawPath(carPath, paint)

        // 3. Techo bitono negro y parabrisas panorámico
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#121212")
        val roofPath = Path().apply {
            moveTo(19 * density, 14 * density)
            cubicTo(13 * density, 16 * density, 10 * density, 22 * density, 10 * density, 30 * density)
            lineTo(10 * density, 50 * density)
            lineTo(28 * density, 50 * density)
            lineTo(28 * density, 30 * density)
            cubicTo(28 * density, 22 * density, 25 * density, 16 * density, 19 * density, 14 * density)
            close()
        }
        canvas.drawPath(roofPath, paint)

        // 4. Barra de luces traseras LED rojas (Firma C-HR)
        paint.color = Color.parseColor("#D32F2F")
        canvas.drawRect(10 * density, 58 * density, 28 * density, 60 * density, paint)

        return BitmapDrawable(resources, bitmap)
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause(); locationProvider?.stopLocationProvider() }
}
