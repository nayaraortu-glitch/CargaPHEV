package com.cargaphev.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(applicationContext, getSharedPreferences("osm_prefs", MODE_PRIVATE))

        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // Centrado inicial por defecto
        val mapController = map.controller
        mapController.setZoom(14.0)
        val defaultPoint = GeoPoint(41.599, 2.289) // Zona Vallès / Barcelona
        mapController.setCenter(defaultPoint)

        solicitarPermisosUbicacion()
        cargarPuntosDeCarga(defaultPoint.latitude, defaultPoint.longitude)
    }

    private fun solicitarPermisosUbicacion() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            activarUbicacion()
        }
    }

    private fun activarUbicacion() {
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply {
            enableMyLocation()
            enableFollowLocation()
        }
        map.overlays.add(myLocationOverlay)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            activarUbicacion()
        }
    }

    private fun cargarPuntosDeCarga(lat: Double, lon: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val urlStr = "https://api.openchargemap.io/v3/poi/?output=json&latitude=$lat&longitude=$lon&distance=25&maxresults=30&key=1393eb51-fb18-49ee-8951-e945e2270d65"
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == 200) {
                    val stream = connection.inputStream.bufferedReader().readText()
                    val jsonArray = JSONArray(stream)

                    withContext(Dispatchers.Main) {
                        for (i in 0 until jsonArray.length()) {
                            val item = jsonArray.getJSONObject(i)
                            val addressInfo = item.optJSONObject("AddressInfo") ?: continue
                            val title = addressInfo.optString("Title", "Punto de Carga")
                            val itemLat = addressInfo.optDouble("Latitude", 0.0)
                            val itemLon = addressInfo.optDouble("Longitude", 0.0)

                            val connections = item.optJSONArray("Connections")
                            var infoEnchufe = "Cargador PHEV"
                            if (connections != null && connections.length() > 0) {
                                val conn = connections.getJSONObject(0)
                                val power = conn.optDouble("PowerKW", 0.0)
                                val connType = conn.optJSONObject("ConnectionType")?.optString("Title", "Tipo 2 (Mennekes)") ?: "Tipo 2"
                                infoEnchufe = "$connType | ${power}kW"
                            }

                            if (itemLat != 0.0 && itemLon != 0.0) {
                                val marker = Marker(map)
                                marker.position = GeoPoint(itemLat, itemLon)
                                marker.title = title
                                marker.snippet = infoEnchufe
                                map.overlays.add(marker)
                            }
                        }
                        map.invalidate()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
}
