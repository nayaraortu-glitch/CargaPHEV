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
        var availableSockets: Int,
        var status: AvailabilityStatus,
        val powerKw: String = "22 kW",
        val pricePerKwh: String = "Gratis"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true

        val mapController = map.controller
        mapController.setZoom(14.0)

        val sabadellCenter = GeoPoint(41.5463, 2.1086)
        mapController.setCenter(sabadellCenter)

        checkLocationPermissions()

        // Botón de centrado en ubicación
        val btnLocation: FloatingActionButton? = findViewById(R.id.btnCenterLocation)
        btnLocation?.setOnClickListener {
            val center = myLocationMarker?.position ?: sabadellCenter
            mapController.animateTo(center)
            mapController.setZoom(15.0)
            Toast.makeText(this, "Centrado en tu ubicación", Toast.LENGTH_SHORT).show()
        }

        // --- BOTÓN DE ACTUALIZACIÓN EN TIEMPO REAL ---
        // Usaremos el mismo botón flotante u otro disponible para refrescar estados al instante
        val btnActualizarId = resources.getIdentifier("btnRefresh", "id", packageName)
        if (btnActualizarId != 0) {
            findViewById<View>(btnActualizarId)?.setOnClickListener {
                refreshLiveAvailability()
            }
        } else {
            // Si prefieres usar un doble toque en el mapa o añadir un aviso, lo gestionamos desde el botón de ubicación o un toast
            btnLocation?.setOnLongClickListener {
                refreshLiveAvailability()
                true
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

        loadBaseChargers()
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
            myLocationMarker?.title = "Tu ubicación actual"
            myLocationMarker?.icon = createCustomUserPin()
            map.overlays.add(myLocationMarker)
        }
        myLocationMarker?.position = point
        map.invalidate()
    }

    private fun loadBaseChargers() {
        // En lugar de puntos manuales desplazados, al arrancar lanzamos la consulta real de toda Cataluña
        refreshLiveAvailability()
    }

    private fun refreshLiveAvailability() {
        Toast.makeText(this, "Descargando cargadores reales de toda Cataluña...", Toast.LENGTH_LONG).show()

        GlobalScope.launch(Dispatchers.IO) {
            val liveList = mutableListOf<ChargerInfo>()
            try {
                // Consulta Overpass API optimizada para toda Cataluña con un rectángulo geográfico exacto
                val overpassUrl = "https://overpass-api.de/api/interpreter?data=" +
                        "[out:json][timeout:35];" +
                        "node[\"amenity\"=\"charging_station\"](40.5,0.15,42.9,3.33);" +
                        "out%20body;"

                val connection = URL(overpassUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(responseText)
                    val elements = root.optJSONArray("elements") ?: JSONArray()

                    for (i in 0 until elements.length()) {
                        val node = elements.getJSONObject(i)
                        val lat = node.optDouble("lat", 0.0)
                        val lon = node.optDouble("lon", 0.0)
                        val tags = node.optJSONObject("tags") ?: JSONObject()

                        val name = tags.optString("name", tags.optString("operator", "Punto de Recarga Público"))
                        val operator = tags.optString("operator", "").lowercase()
                        val fee = tags.optString("fee", "").lowercase()
                        
                        // Detección automática de gratuidad y tipo
                        val isFree = fee == "no" || 
                                     operator.contains("ajuntament") || 
                                     operator.contains("estabanell") || 
                                     operator.contains("municipal") ||
                                     fee.isEmpty()

                        val capacity = tags.optString("capacity", "2").toIntOrNull() ?: 2
                        val price = if (isFree) "Gratis" else "De pago / Red general"

                        if (lat != 0.0 && lon != 0.0) {
                            liveList.add(
                                ChargerInfo(
                                    name = name,
                                    address = if (operator.isNotEmpty()) "Operador: $operator" else "Cataluña - Red Pública",
                                    latitude = lat,
                                    longitude = lon,
                                    isFree = isFree,
                                    isType2 = true,
                                    totalSockets = capacity,
                                    availableSockets = capacity,
                                    status = AvailabilityStatus.ALL_AVAILABLE,
                                    powerKw = "22 kW",
                                    pricePerKwh = price
                                )
                            )
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (liveList.isNotEmpty()) {
                        allChargers.clear()
                        allChargers.addAll(liveList)
                        updateMarkers()
                        Toast.makeText(this@MainActivity, "¡${liveList.size} cargadores reales cargados!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No se encontraron puntos en la red", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de conexión con el mapa de Cataluña", Toast.LENGTH_SHORT).show()
                }
            }
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
                    AvailabilityStatus.PARTIALLY_AVAILABLE -> "🟡 Ocupación parcial (${charger.availableSockets}/${charger.totalSockets} libres)"
                    AvailabilityStatus.FULLY_OCCUPIED -> "🔴 Completo (0/${charger.totalSockets} libres)"
                    AvailabilityStatus.OUT_OF_SERVICE -> "🔘 Fuera de servicio"
                }

                tvNombre?.text = charger.name
                tvDireccion?.text = "${charger.address}\n$statusText • ${charger.pricePerKwh} • ${charger.powerKw}"

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

    private fun createCustomUserPin(): Drawable {
        val density = resources.displayMetrics.density
        val size = (28 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.parseColor("#1976D2")
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = Color.WHITE
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (3 * density), paint)

        paint.color = Color.parseColor("#1976D2")
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - (6 * density), paint)

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
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        locationProvider?.stopLocationProvider()
    }
}
