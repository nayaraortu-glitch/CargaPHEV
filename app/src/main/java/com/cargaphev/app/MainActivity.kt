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
        val pricePerKwh: String = "Consulta en destino"
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

        // Centro inicial por defecto: Sabadell
        val defaultCenter = GeoPoint(41.5463, 2.1086)
        mapController.setCenter(defaultCenter)

        checkLocationPermissions()

        // Botón de centrado en ubicación
        val btnLocation: FloatingActionButton? = findViewById(R.id.btnCenterLocation)
        btnLocation?.setOnClickListener {
            val center = myLocationMarker?.position ?: map.mapCenter as? GeoPoint ?: defaultCenter
            mapController.animateTo(center)
            mapController.setZoom(15.0)
            Toast.makeText(this, "Centrado en tu ubicación", Toast.LENGTH_SHORT).show()
        }

        // --- BOTÓN DE ACTUALIZACIÓN INTELIGENTE ---
        val btnRefreshId = resources.getIdentifier("btnRefresh", "id", packageName)
        if (btnRefreshId != 0) {
            findViewById<View>(btnRefreshId)?.setOnClickListener {
                val currentCenter = map.mapCenter as? GeoPoint ?: defaultCenter
                loadChargersFromOpenChargeMap(currentCenter.latitude, currentCenter.longitude)
            }
        } else {
            // Si no hay botón físico de refresco, un toque largo en el botón de ubicación actualiza la zona
            btnLocation?.setOnLongClickListener {
                val currentCenter = map.mapCenter as? GeoPoint ?: defaultCenter
                loadChargersFromOpenChargeMap(currentCenter.latitude, currentCenter.longitude)
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

        // Carga inicial basada en la posición predeterminada
        loadChargersFromOpenChargeMap(defaultCenter.latitude, defaultCenter.longitude)
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

    // --- CARGA DINÁMICA SEGURA MEDIANTE OPEN CHARGE MAP ---
    private fun loadChargersFromOpenChargeMap(lat: Double, lon: Double) {
        Toast.makeText(this, "Buscando cargadores cercanos...", Toast.LENGTH_SHORT).show()

        GlobalScope.launch(Dispatchers.IO) {
            val liveList = mutableListOf<ChargerInfo>()
            try {
                // Consultamos un radio de 30 km alrededor de la posición actual (máximo 80 resultados para cero sobrecarga)
                val urlString = "https://api.openchargemap.io/v3/poi/?output=json&countrycode=ES&maxresults=80&latitude=$lat&longitude=$lon&distance=30&distanceunit=KM&compact=true"

                val connection = URL(urlString).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "CargaPHEV-AndroidApp")

                if (connection.responseCode == 200) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(responseText)

                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val addressInfo = item.optJSONObject("AddressInfo") ?: continue

                        val name = addressInfo.optString("Title", "Punto de Recarga")
                        val address = addressInfo.optString("AddressLine1", "Ubicación en zona")
                        val latitude = addressInfo.optDouble("Latitude", 0.0)
                        val longitude = addressInfo.optDouble("Longitude", 0.0)

                        // Estado operativo
                        val statusType = item.optJSONObject("StatusType")
                        val isOperational = statusType?.optBoolean("IsOperational", true) ?: true

                        // Conectores y potencia
                        val connections = item.optJSONArray("Connections")
                        var totalSockets = 2
                        var isType2 = false
                        var powerKw = "22 kW"

                        if (connections != null && connections.length() > 0) {
                            val conn = connections.getJSONObject(0)
                            totalSockets = conn.optInt("Quantity", 2)
                            val powerKWVal = conn.optDouble("PowerKW", 22.0)
                            if (powerKWVal > 0) powerKw = "${powerKWVal.toInt()} kW"
                            
                            // Comprobar si es Tipo 2 u otro compatible
                            val connType = conn.optJSONObject("ConnectionType")?.optString("Title", "").orEmpty()
                            isType2 = connType.contains("Type 2") || connType.contains("Mennekes") || connType.isEmpty()
                        } else {
                            isType2 = true
                        }

                        val status = if (isOperational) AvailabilityStatus.ALL_AVAILABLE else AvailabilityStatus.OUT_OF_SERVICE
                        val available = if (isOperational) totalSockets else 0

                        if (latitude != 0.0 && longitude != 0.0) {
                            liveList.add(
                                ChargerInfo(
                                    name = name,
                                    address = address,
                                    latitude = latitude,
                                    longitude = longitude,
                                    isFree = true, // Enfocado a híbridos en búsqueda de puntos accesibles
                                    isType2 = isType2,
                                    totalSockets = totalSockets,
                                    availableSockets = available,
                                    status = status,
                                    powerKw = powerKw,
                                    pricePerKwh = "Red Oficial OCM"
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
                        Toast.makeText(this@MainActivity, "¡${liveList.size} cargadores cargados en esta zona!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "No hay cargadores registrados en este radio", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error de red al actualizar la zona", Toast.LENGTH_SHORT).show()
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
                    AvailabilityStatus.ALL_AVAILABLE -> "🟢 Operativo / Libre (${charger.availableSockets}/${charger.totalSockets} tomas)"
                    AvailabilityStatus.PARTIALLY_AVAILABLE -> "🟡 Ocupación parcial"
                    AvailabilityStatus.FULLY_OCCUPIED -> "🔴 Ocupado"
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
        } catch (e:Exception) {
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
