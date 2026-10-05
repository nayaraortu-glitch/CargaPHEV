package com.cargaphev.app

import android.Manifest
import android.content.pm.PackageManager
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

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var locationOverlay: MyLocationNewOverlay? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private val allChargers = mutableListOf<ChargerInfo>()
    private val activeMarkers = mutableListOf<Marker>()

    data class ChargerInfo(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
        val isFree: Boolean,
        val isType2: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configuración de OSM
        Configuration.getInstance().userAgentValue = packageName
        setContentView(R.layout.activity_main)

        // Inicializar mapa
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        val mapController = map.controller
        mapController.setZoom(14.0)

        // Ubicación por defecto
        val defaultPoint = GeoPoint(41.5463, 2.1086)
        mapController.setCenter(defaultPoint)

        // Configurar capa de mi ubicación
        setupLocationOverlay()

        // Solicitar permisos de GPS en tiempo real
        checkLocationPermissions()

        // Botón flotante para centrar la ubicación del usuario
        val btnLocation: FloatingActionButton? = findViewById(R.id.btnCenterLocation)
        btnLocation?.setOnClickListener {
            val myLoc = locationOverlay?.myLocation
            if (myLoc != null) {
                mapController.animateTo(myLoc)
            } else {
                mapController.animateTo(defaultPoint)
                Toast.makeText(this, "Obteniendo posición GPS...", Toast.LENGTH_SHORT).show()
            }
        }

        // Configurar Filtros (Chips)
        val chipGratuitos: Chip? = findViewById(R.id.chipGratuitos)
        val chipTipo2: Chip? = findViewById(R.id.chipTipo2)

        chipGratuitos?.setOnCheckedChangeListener { _, _ -> updateMarkers() }
        chipTipo2?.setOnCheckedChangeListener { _, _ -> updateMarkers() }

        // Cargar puntos de recarga
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                locationOverlay?.enableMyLocation()
            } else {
                Toast.makeText(this, "Permiso de ubicación denegado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadChargers() {
        GlobalScope.launch(Dispatchers.IO) {
            val loadedList = fetchChargersData()
            withContext(Dispatchers.Main) {
                allChargers.clear()
                allChargers.addAll(loadedList)
                updateMarkers()
            }
        }
    }

    private fun fetchChargersData(): List<ChargerInfo> {
        val list = mutableListOf<ChargerInfo>()
        try {
            // Puntos de prueba/locales para visualización inmediata
            list.add(ChargerInfo("Cargador Sabadell Centre", "Plaza del Gas, Sabadell", 41.5458, 2.1080, isFree = true, isType2 = true))
            list.add(ChargerInfo("Punto PHEV Can Gambús", "Parque Can Gambús, Sabadell", 41.5490, 2.0950, isFree = true, isType2 = true))
            list.add(ChargerInfo("Electrolinera E.Leclerc", "Av. de Barberà, Sabadell", 41.5320, 2.1150, isFree = false, isType2 = true))
            list.add(ChargerInfo("Cargador Rápido N-150", "Carretera de Terrassa, Sabadell", 41.5520, 2.0880, isFree = false, isType2 = false))
            list.add(ChargerInfo("Punt de Càrrega Ajuntament", "Carrer de la Salut, Sabadell", 41.5482, 2.1121, isFree = true, isType2 = true))
            list.add(ChargerInfo("Endesa X Way - Fira", "Plaça de la Fira, Sabadell", 41.5430, 2.1020, isFree = false, isType2 = true))
            list.add(ChargerInfo("Iberdrola Recharge", "Av. Francesc Macià, Sabadell", 41.5550, 2.0990, isFree = false, isType2 = true))
            list.add(ChargerInfo("Tesla Supercharger / Tipo 2", "Via de Massagué, Sabadell", 41.5505, 2.1065, isFree = false, isType2 = true))

            // Intento de conexión con la red pública de cargadores
            val url = URL("https://datos.gob.es/apigateway/miteco/puntos-recarga")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            if (connection.responseCode == 200) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonText)
                val items = root.optJSONArray("listaPuntos") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val lat = item.optDouble("latitud", 0.0)
                    val lng = item.optDouble("longitud", 0.0)
                    val name = item.optString("nombre", "Punto de Carga")
                    val addr = item.optString("direccion", "Sin dirección")
                    val esGratis = item.optBoolean("gratuito", false)
                    val esTipo2 = item.optString("tipoConector", "").contains("Tipo 2", ignoreCase = true)
                    if (lat != 0.0 && lng != 0.0) {
                        list.add(ChargerInfo(name, addr, lat, lng, esGratis, esTipo2))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun updateMarkers() {
        val chipGratuitos: Chip? = findViewById(R.id.chipGratuitos)
        val chipTipo2: Chip? = findViewById(R.id.chipTipo2)

        val onlyFree = chipGratuitos?.isChecked ?: false
        val onlyType2 = chipTipo2?.isChecked ?: false

        // Limpiar marcadores activos
        for (marker in activeMarkers) {
            map.overlays.remove(marker)
        }
        activeMarkers.clear()

        val bottomSheet: View? = findViewById(R.id.bottomSheet)
        val tvNombre: TextView? = findViewById(R.id.tvNombreEstacion)
        val tvDireccion: TextView? = findViewById(R.id.tvDireccion)

        // Dibujar los marcadores filtrados
        for (charger in allChargers) {
            if (onlyFree && !charger.isFree) continue
            if (onlyType2 && !charger.isType2) continue

            val marker = Marker(map)
            marker.position = GeoPoint(charger.latitude, charger.longitude)
            marker.title = charger.name
            marker.snippet = charger.address
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

            marker.setOnMarkerClickListener { m, _ ->
                tvNombre?.text = charger.name
                tvDireccion?.text = charger.address
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

        map.invalidate() // Forzar redibujado del mapa
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
