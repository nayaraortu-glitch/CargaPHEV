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
import kotlinx.coroutines.CoroutineScope
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

        val defaultPoint = GeoPoint(41.5463, 2.1086) // Sabadell por defecto
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
                loadChargersSilent()
            }
        }
    }

    private fun loadChargers() {
        GlobalScope.launch(Dispatchers.IO) {
            fetchChargerData()
        }
    }

    private fun loadChargersSilent() {
        fetchChargerData()
    }

    private fun fetchChargerData() {
        val loadedList = mutableListOf<ChargerInfo>()

        // Base amplia comarcal inicial de respaldo
        loadedList.add(ChargerInfo("Punt Càrrega Pl. del Gas", "Plaza del Gas, Sabadell", 41.5458, 2.1080, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Cargador Can Gambús", "Parque Can Gambús, Sabadell", 41.5490, 2.0950, true, true, 4, 2, AvailabilityStatus.PARTIALLY_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Endesa X Way - Fira Sabadell", "Plaça de la Fira, Sabadell", 41.5430, 2.1020, false, true, 2, 0, AvailabilityStatus.FULLY_OCCUPIED, "50 kW", "0,45 €/kWh"))
        loadedList.add(ChargerInfo("Electrolinera E.Leclerc", "Av. de Barberà, Sabadell", 41.5320, 2.1150, false, true, 2, 1, AvailabilityStatus.PARTIALLY_AVAILABLE, "22 kW", "0,35 €/kWh"))
        loadedList.add(ChargerInfo("Punt Ajuntament Salut", "Carrer de la Salut, Sabadell", 41.5482, 2.1121, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Iberdrola Recharge Macià", "Av. Francesc Macià, Sabadell", 41.5550, 2.0990, false, true, 4, 0, AvailabilityStatus.FULLY_OCCUPIED, "50 kW", "0,50 €/kWh"))
        loadedList.add(ChargerInfo("Tesla Supercharger Sabadell", "Via de Massagué, Sabadell", 41.5505, 2.1065, false, true, 8, 5, AvailabilityStatus.PARTIALLY_AVAILABLE, "150 kW", "0,40 €/kWh"))
        
        // Terrassa
        loadedList.add(ChargerInfo("Electrolinera Ajuntament Terrassa", "Poblenou / Rambla, Terrassa", 41.5619, 2.0099, true, true, 4, 3, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Endesa X Parc Vallès", "Parc Vallès, Terrassa", 41.5720, 2.0210, false, true, 6, 2, AvailabilityStatus.PARTIALLY_AVAILABLE, "50 kW", "0,42 €/kWh"))
        loadedList.add(ChargerInfo("Tesla Supercharger Terrassa", "Carretera de Rubí, Terrassa", 41.5530, 2.0150, false, true, 10, 8, AvailabilityStatus.PARTIALLY_AVAILABLE, "150 kW", "0,40 €/kWh"))

        // Sant Cugat del Vallès
        loadedList.add(ChargerInfo("Punt Càrrega Monestir", "Plaça del Monestir, Sant Cugat", 41.4745, 2.0851, true, true, 2, 1, AvailabilityStatus.PARTIALLY_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Iberdrola Estació Sant Cugat", "Av. de Gràcia, Sant Cugat", 41.4690, 2.0830, false, true, 4, 4, AvailabilityStatus.ALL_AVAILABLE, "50 kW", "0,45 €/kWh"))

        // Barberà y Cerdanyola
        loadedList.add(ChargerInfo("Punt Barberà Centre", "Passeig del Doctor Moragas, Barberà del Vallès", 41.5160, 2.1220, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Electrolinera Cerdanyola Universitat", "Campus UAB, Cerdanyola", 41.4980, 2.1100, true, true, 4, 2, AvailabilityStatus.PARTIALLY_AVAILABLE, "22 kW", "Gratis"))

        // Mollet y Granollers
        loadedList.add(ChargerInfo("Punt Ajuntament Mollet", "Plaça Major, Mollet del Vallès", 41.5435, 2.2136, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Endesa X Granollers Centre", "Passeig de la Muntanya, Granollers", 41.6083, 2.2878, false, true, 4, 1, AvailabilityStatus.PARTIALLY_AVAILABLE, "50 kW", "0,45 €/kWh"))

        // Rubí y Castellar
        loadedList.add(ChargerInfo("Punt Rubí Mercat", "Carrer de Montserrat, Rubí", 41.4929, 2.0323, true, true, 2, 0, AvailabilityStatus.FULLY_OCCUPIED, "22 kW", "Gratis"))
        loadedList.add(ChargerInfo("Punt Castellar del Vallès", "Passeig de Tolrà, Castellar", 41.6183, 2.0883, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW", "Gratis"))

        // Obtener centro actual del mapa dinámicamente
        val centerLat = if (map.mapCenter.latitude != 0.0) map.mapCenter.latitude else 41.5463
        val centerLon = if (map.mapCenter.longitude != 0.0) map.mapCenter.longitude else 2.1086

        val minLat = centerLat - 0.6
        val maxLat = centerLat + 0.6
        val minLon = centerLon - 0.6
        val maxLon = centerLon + 0.6

        val sMinLat = String.format(Locale.US, "%.4f", minLat)
        val sMinLon = String.format(Locale.US, "%.4f", minLon)
        val sMaxLat = String.format(Locale.US, "%.4f", maxLat)
        val sMaxLon = String.format(Locale.US, "%.4f", maxLon)

        try {
            val overpassUrl = "https://overpass-api.de/api/interpreter?data=" +
                    "[out:json][timeout:10];" +
                    "node[\"amenity\"=\"charging_station\"]($sMinLat,$sMinLon,$sMaxLat,$sMaxLon);" +
                    "out%20body;"

            val connection = URL(overpassUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                val elements = root.optJSONArray("elements") ?: JSONArray()

                for (i in 0 until elements.length()) {
                    val node = elements.getJSONObject(i)
                    val lat = node.optDouble("lat", 0.0)
                    val lon = node.optDouble("lon", 0.0)
                    val tags = node.optJSONObject("tags") ?: JSONObject()

                    val name = tags.optString("name", tags.optString("operator", "Cargador EV Público"))
                    val fee = tags.optString("fee", "no")
                    val isFree = fee.equals("no", ignoreCase = true) || fee.isEmpty()
                    val capacity = tags.optString("capacity", "2").toIntOrNull() ?: 2

                    // Simulación dinámica de estado para pruebas en tiempo real
                    val status = when ((i + System.currentTimeMillis() / 30000).toInt() % 3) {
                        0 -> AvailabilityStatus.ALL_AVAILABLE
                        1 -> AvailabilityStatus.PARTIALLY_AVAILABLE
                        else -> AvailabilityStatus.FULLY_OCCUPIED
                    }

                    val availSockets = when (status) {
                        AvailabilityStatus.ALL_AVAILABLE -> capacity
                        AvailabilityStatus.PARTIALLY_AVAILABLE -> maxOf(1, capacity / 2)
                        else -> 0
                    }

                    val price = if (isFree) "Gratis" else "0,38 €/kWh"

                    if (lat != 0.0 && lon != 0.0) {
                        loadedList.add(
                            ChargerInfo(
                                name = name,
                                address = "Red Pública Local",
                                latitude = lat,
                                longitude = lon,
                                isFree = isFree,
                                isType2 = true,
                                totalSockets = capacity,
                                availableSockets = availSockets,
                                status = status,
                                powerKw = "22 kW",
                                pricePerKwh = price
                            )
                        )
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
        startPeriodicRefresh() // Inicia el bucle de actualización al abrir la app
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        refreshJob?.cancel() // Detiene el bucle al salir de la app para ahorrar batería
    }
}
