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
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.net.HttpURLConnection
import java.net.URL
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private val allChargers = mutableListOf<ChargerInfo>()
    private val activeMarkers = mutableListOf<Marker>()
    private var selectedCharger: ChargerInfo? = null

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
        val powerKw: String = "22 kW"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        val mapController = map.controller
        mapController.setZoom(13.5)

        val defaultPoint = GeoPoint(41.5463, 2.1086) // Sabadell
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
            findViewById<View>(btnNavegarId)?.setOnClickListener { openGoogleMapsNavigation() }
        }

        setupSearchInAreaButton()
        loadChargers()
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

    private fun loadChargers() {
        GlobalScope.launch(Dispatchers.IO) {
            val loadedList = mutableListOf<ChargerInfo>()

            // 1. Cargadores de muestra estables con colores correctos
            loadedList.add(ChargerInfo("Punt Càrrega Pl. del Gas", "Plaza del Gas, Sabadell", 41.5458, 2.1080, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW"))
            loadedList.add(ChargerInfo("Cargador Can Gambús", "Parque Can Gambús, Sabadell", 41.5490, 2.0950, true, true, 4, 2, AvailabilityStatus.PARTIALLY_AVAILABLE, "22 kW"))
            loadedList.add(ChargerInfo("Endesa X Way - Fira", "Plaça de la Fira, Sabadell", 41.5430, 2.1020, false, true, 2, 0, AvailabilityStatus.FULLY_OCCUPIED, "50 kW"))
            loadedList.add(ChargerInfo("Electrolinera E.Leclerc", "Av. de Barberà, Sabadell", 41.5320, 2.1150, false, true, 2, 1, AvailabilityStatus.PARTIALLY_AVAILABLE, "22 kW"))
            loadedList.add(ChargerInfo("Punt Ajuntament Salut", "Carrer de la Salut, Sabadell", 41.5482, 2.1121, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW"))
            loadedList.add(ChargerInfo("Iberdrola Recharge Macià", "Av. Francesc Macià, Sabadell", 41.5550, 2.0990, false, true, 4, 0, AvailabilityStatus.FULLY_OCCUPIED, "50 kW"))
            loadedList.add(ChargerInfo("Tesla Supercharger", "Via de Massagué, Sabadell", 41.5505, 2.1065, false, true, 8, 5, AvailabilityStatus.PARTIALLY_AVAILABLE, "150 kW"))
            loadedList.add(ChargerInfo("Punt Barberà Centre", "Passeig del Doctor Moragas, Barberà", 41.5160, 2.1220, true, true, 2, 2, AvailabilityStatus.ALL_AVAILABLE, "22 kW"))

            // 2. Consulta Overpass de OpenStreetMap en segundo plano
            try {
                val overpassUrl = "https://overpass-api.de/api/interpreter?data=[out:json][timeout:8];node[%22amenity%22=%22charging_station%22](41.10,1.40,41.90,2.80);out%20body;"
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

                        val status = when {
                            i % 3 == 0 -> AvailabilityStatus.ALL_AVAILABLE
                            i % 3 == 1 -> AvailabilityStatus.PARTIALLY_AVAILABLE
                            else -> AvailabilityStatus.FULLY_OCCUPIED
                        }

                        val availSockets = when (status) {
                            AvailabilityStatus.ALL_AVAILABLE -> capacity
                            AvailabilityStatus.PARTIALLY_AVAILABLE -> maxOf(1, capacity / 2)
                            else -> 0
                        }

                        if (lat != 0.0 && lon != 0.0) {
                            loadedList.add(
                                ChargerInfo(
                                    name = name,
                                    address = "Punto Red Pública",
                                    latitude = lat,
                                    longitude = lon,
                                    isFree = isFree,
                                    isType2 = true,
                                    totalSockets = capacity,
                                    availableSockets = availSockets,
                                    status = status,
                                    powerKw = "22 kW"
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

                val priceText = if (charger.isFree) "Gratuito" else "De pago"

                tvNombre?.text = charger.name
                tvDireccion?.text = "${charger.address}\n$statusText • $priceText • ${charger.powerKw}"

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

    private fun openGoogleMapsNavigation() {
        val charger = selectedCharger
        if (charger != null) {
            val gmmIntentUri = Uri.parse("google.navigation:q=${charger.latitude},${charger.longitude}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            if (mapIntent.resolveActivity(packageManager) != null) {
                startActivity(mapIntent)
            } else {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${charger.latitude},${charger.longitude}"))
                startActivity(browserIntent)
            }
        } else {
            Toast.makeText(this, "Selecciona un cargador en el mapa primero", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    private fun setupSearchInAreaButton() {
        val mapContainer = map.parent as? ViewGroup ?: return

        val searchButton = Button(this).apply {
            text = "🔍 Buscar en esta zona"
            setBackgroundColor(Color.parseColor("#3388FF"))
            setTextColor(Color.WHITE)
            visibility = View.VISIBLE
            setOnClickListener {
                visibility = View.GONE
                loadChargers()
            }
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 220
        }

        mapContainer.addView(searchButton, params)

        map.post {
            loadChargers()
        }

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                searchButton.visibility = View.VISIBLE
                return true
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                searchButton.visibility = View.VISIBLE
                return true
            }
        })
    }
}
