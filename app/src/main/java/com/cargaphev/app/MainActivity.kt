package com.cargaphev.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private val allChargers = mutableListOf<ChargerInfo>()
    private val activeMarkers = mutableListOf<Marker>()
    private var selectedCharger: ChargerInfo? = null
    
    private var refreshJob: Job? = null

    enum class AvailabilityStatus {
        ALL_AVAILABLE,
        PARTIALLY_AVAILABLE,
        FULLY_OCCUPIED,
        OUT_OF_SERVICE
    }

    data class ChargerInfo(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val isFree: Boolean,
        val isType2: Boolean,
        val totalSockets: Int,
        val availableSockets: Int,
        val status: AvailabilityStatus,
        val powerKw: String = "22 kW",
        val pricePerKwh: String = "0,35 €/kWh"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        val mapController = map.controller
        mapController.setZoom(11.5)

        val defaultPoint = GeoPoint(41.5463, 2.1086) // Sabadell / Centro comarcal
        mapController.setCenter(defaultPoint)

        setupLocationOverlay()
        checkLocationPermissions()

        val btnLocation: FloatingActionButton? = findViewById(R.id.btnCenterLocation)
        btnLocation?.setOnClickListener {
            val myLoc = locationOverlay?.myLocation
            if (myLoc != null) {
                mapController.animateTo(myLoc)
            } else {
                mapController.animateTo(defaultPoint)
                Toast.makeText(this, "Buscando señal GPS...", Toast.LENGTH_SHORT).show()
            }
        }

        val chipGratuitos: Chip? = findViewById(R.id.chipGratuitos)
        val chipTipo2: Chip? = findViewById(R.id.chipTipo2)

        chipGratuitos?.setOnCheckedChangeListener { _, _ -> updateMarkers() }
        chipTipo2?.setOnCheckedChangeListener { _, _ -> updateMarkers() }

        val btnNavegarId = resources.getIdentifier("btnNavegar", "id", packageName)
        if (btnNavegarId != 0) {
            findViewById<View>(btnNavegarId)?.setOnClickListener { showNavigationChooser() }
        }

        map.post {
            loadChargers()
        }
    }

    private fun setupLocationOverlay() {
        val provider = GpsMyLocationProvider(this)
        locationOverlay = MyLocationNewOverlay(provider, map)
        locationOverlay?.enableMyLocation()
        map.overlays.add(locationOverlay)
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
            locationOverlay?.enableMyLocation()
        }
    }

    private fun startPeriodicRefresh() {
        refreshJob?.cancel()
        refreshJob = GlobalScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(45000) // Refresca cada 45 segundos en segundo plano
                fetchChargerData()
            }
        }
    }

    private fun loadChargers() {
        GlobalScope.launch(Dispatchers.IO) {
            fetchChargerData()
        }
    }

    private suspend fun fetchChargerData() {
        val loadedList = mutableListOf<ChargerInfo>()

        // =========================================================================
        // BASE FIJA GARANTIZADA: CANOVELLES Y POBLACIONES PERIFÉRICAS
        // =========================================================================
        
        // Canovelles
        loadedList.add(ChargerInfo("EVcharge - CAP Canovelles", "Zona CAP / Ctra. de Ribes, Canovelles", 41.6165, 2.2790, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("EVcharge - Parking Pabelló Canovelles", "Parking Pabellón Municipal, Canovelles", 41.6120, 2.2820, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("EVcharge - Ajuntament de Canovelles", "Plaça de l'Ajuntament, Canovelles", 41.6150, 2.2840, true, true, 2, 1, AvailabilityStatus.PARTIALLY_AVAILABLE, "7.4 kW", "Gratis"))
        loadedList.add(ChargerInfo("Punt Canovelles Barri Nord", "Carrer de la Riera, Canovelles", 41.6185, 2.2760, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))

        // Granollers (Periferia / Enlaces directos con Canovelles)
        loadedList.add(ChargerInfo("Punt Municipal - C/ Josep Umbert", "Carrer de Josep Umbert (Zona Jutjats), Granollers", 41.6095, 2.2890, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Punt Municipal - Camp de les Moreres", "Carrer del Camp de les Moreres, Granollers", 41.6072, 2.2921, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Estabanell Energía (C/ Rec)", "Carrer del Rec, 28, Granollers", 41.6080, 2.2870, true, true, 4, 3, AvailabilityStatus.ALL_AVAILABLE, "7.4 kW", "Gratis"))
        loadedList.add(ChargerInfo("Hospital General de Granollers", "Carrer de Francesc Ribas, Granollers", 41.6020, 2.2900, true, true, 8, 6, AvailabilityStatus.ALL_AVAILABLE, "7.4 kW", "Gratis"))

        // Poblaciones Periféricas (Les Franqueses, Lliçà d'Amunt, Cardedeu)
        loadedList.add(ChargerInfo("Ajuntament de les Franqueses", "Zona Esportiva Municipal, Corró d'Avall", 41.6320, 2.2950, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Punt Recàrrega Lliçà d'Amunt", "Passeig de Catalunya, Lliçà d'Amunt", 41.6180, 2.2350, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Punt Cardedeu Estació", "Plaça de les Olors, Cardedeu", 41.6385, 2.3650, true, true, 2, 1, AvailabilityStatus.PARTIALLY_AVAILABLE, "22 kW", "Gratis"))

        // =========================================================================
        // CONSULTA DINÁMICA DE RESPALDO (Zona Canovelles y Comarca Norte)
        // =========================================================================
        try {
            val minLat = 41.58
            val maxLat = 41.66
            val minLon = 2.20
            val maxLon = 2.38

            val overpassUrl = "https://overpass-api.de/api/interpreter?data=" +
                    "[out:json][timeout:8];node[\"amenity\"=\"charging_station\"]($minLat,$minLon,$maxLat,$maxLon);out%20body;"

            val connection = URL(overpassUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 4000
            connection.readTimeout = 4000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                val elements = root.optJSONArray("elements") ?: JSONArray()

                for (i in 0 until elements.length()) {
                    val node = elements.getJSONObject(i)
                    val lat = node.optDouble("lat", 0.0)
                    val lon = node.optDouble("lon", 0.0)
                    val tags = node.optJSONObject("tags") ?: JSONObject()

                    val name = tags.optString("name", tags.optString("operator", "Cargador Zona Nord"))
                    val operator = tags.optString("operator", "").lowercase()
                    val fee = tags.optString("fee", "").lowercase()
                    
                    val isFree = fee == "no" || 
                                 operator.contains("ajuntament") || 
                                 operator.contains("estabanell") || 
                                 operator.contains("municipal") ||
                                 fee.isEmpty()

                    val capacity = tags.optString("capacity", "2").toIntOrNull() ?: 2
                    val status = AvailabilityStatus.ALL_AVAILABLE
                    val price = if (isFree) "Gratis" else "0,38 €/kWh"

                    if (lat != 0.0 && lon != 0.0) {
                        val exists = loadedList.any { kotlin.math.abs(it.latitude - lat) < 0.0005 && kotlin.math.abs(it.longitude - lon) < 0.0005 }
                        if (!exists) {
                            loadedList.add(
                                ChargerInfo(
                                    name = name,
                                    address = if (operator.isNotEmpty()) "Op: $operator" else "Punto de recarga",
                                    latitude = lat,
                                    longitude = lon,
                                    isFree = isFree,
                                    isType2 = true,
                                    totalSockets = capacity,
                                    availableSockets = capacity,
                                    status = status,
                                    powerKw = "22 kW",
                                    pricePerKwh = price
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        withContext(Dispatchers.Main) {
            allChargers.clear()
            allChargers.addAll(loadedList)
            updateMarkers()
        }
    }

    private fun updateMarkers() {
        val chipGratuitos: Chip? = findViewById(R.id.chipGratuitos)
        val chipTipo2: Chip? = findViewById(R.id.chipTipo2)

        val onlyFree = chipGratuitos?.isChecked ?: false
        val onlyType2 = chipTipo2?.isChecked ?: false

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

            val marker = Marker(map)
            marker.position = GeoPoint(charger.latitude, charger.longitude)
            marker.title = charger.name

            val markerColor = when (charger.status) {
                AvailabilityStatus.ALL_AVAILABLE -> Color.parseColor("#2E7D32")
                AvailabilityStatus.PARTIALLY_AVAILABLE -> Color.parseColor("#FFB300")
                AvailabilityStatus.FULLY_OCCUPIED -> Color.parseColor("#D32F2F")
                AvailabilityStatus.OUT_OF_SERVICE -> Color.parseColor("#757575")
            }

            marker.icon = createCustomPinIcon(markerColor)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            marker.setOnMarkerClickListener { m, _ ->
                selectedCharger = charger

                val statusText = when (charger.status) {
                    AvailabilityStatus.ALL_AVAILABLE -> "🟢 Libre (${charger.availableSockets}/${charger.totalSockets} tomas)"
                    AvailabilityStatus.PARTIALLY_AVAILABLE -> "🟡 Ocupación parcial (${charger.availableSockets}/${charger.totalSockets} tomas libres)"
                    AvailabilityStatus.FULLY_OCCUPIED -> "🔴 Completo (0/${charger.totalSockets} libres)"
                    AvailabilityStatus.OUT_OF_SERVICE -> "🔘 Fuera de servicio"
                }

                val priceInfo = if (charger.isFree) "Gratuito (0 €/kWh)" else "De pago (${charger.pricePerKwh})"

                tvNombre?.text = charger.name
                tvDireccion?.text = "${charger.address}\n$statusText • $priceInfo • ${charger.powerKw}"

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

    private fun createCustomPinIcon(colorInt: Int): Drawable {
        val density = resources.displayMetrics.density
        val size = (36 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = colorInt
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - (3 * density), paint)

        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, 4 * density, paint)

        return BitmapDrawable(resources, bitmap)
    }

    private fun showNavigationChooser() {
        val charger = selectedCharger
        if (charger == null) {
            Toast.makeText(this, "Selecciona un cargador en el mapa primero", Toast.LENGTH_SHORT).show()
            return
        }

        val options = arrayOf("Google Maps", "Waze")
        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Elegir Navegador")
        builder.setItems(options) { _, which ->
            when (which) {
                0 -> openGoogleMapsNavigation(charger)
                1 -> openWazeNavigation(charger)
            }
        }
        builder.show()
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
            Toast.makeText(this, "Waze no está instalado en el dispositivo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        startPeriodicRefresh()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        refreshJob?.cancel()
    }
}
