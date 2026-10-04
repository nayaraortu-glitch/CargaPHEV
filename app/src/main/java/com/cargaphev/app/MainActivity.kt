package com.cargaphev.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
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
import java.net.URLEncoder

data class CargadorPoint(
    val title: String,
    val snippet: String,
    val lat: Double,
    val lon: Double
)

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

        // Punto por defecto (Sabadell / Vallès)
        val defaultPoint = GeoPoint(41.545, 2.108)
        map.controller.setZoom(14.0)
        map.controller.setCenter(defaultPoint)

        val btnCenterLocation = findViewById<FloatingActionButton>(R.id.btnCenterLocation)
        btnCenterLocation.setOnClickListener {
            val myLoc = myLocationOverlay?.myLocation
            if (myLoc != null) {
                map.controller.animateTo(myLoc)
                map.controller.setZoom(16.0)
                cargarPuntosDeCarga(myLoc.latitude, myLoc.longitude)
            } else {
                Toast.makeText(this, "Obteniendo posición GPS...", Toast.LENGTH_SHORT).show()
            }
        }

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
            runOnFirstFix {
                val loc = myLocation
                if (loc != null) {
                    runOnUiThread {
                        map.controller.animateTo(loc)
                        cargarPuntosDeCarga(loc.latitude, loc.longitude)
                    }
                }
            }
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
            val listaCargadores = mutableListOf<CargadorPoint>()

            // 1. Red Overpass API (Estabanell, EVCharge, Endesa, etc.)
            try {
                val query = "[out:json];node[\"amenity\"=\"charging_station\"](around:15000,$lat,$lon);out;"
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val overpassUrl = "https://overpass-api.de/api/interpreter?data=$encodedQuery"
                val conn = URL(overpassUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "CargaPHEV/1.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val elements = json.optJSONArray("elements")
                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val elem = elements.getJSONObject(i)
                            val eLat = elem.optDouble("lat")
                            val eLon = elem.optDouble("lon")
                            val tags = elem.optJSONObject("tags")
                            val name = tags?.optString("name") ?: tags?.optString("operator") ?: "Punto de Carga"
                            val operator = tags?.optString("operator") ?: ""
                            val title = if (operator.isNotEmpty() && !name.contains(operator, true)) "$operator - $name" else name

                            var info = "Cargador EV / PHEV"
                            val socket2 = tags?.optString("socket:type2")
                            if (socket2 != null) info += " | Tipo 2 ($socket2)"

                            listaCargadores.add(CargadorPoint(title, info, eLat, eLon))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Red OpenChargeMap API
            try {
                val ocmUrl = "https://api.openchargemap.io/v3/poi/?output=json&latitude=$lat&longitude=$lon&distance=25&maxresults=150&key=1393eb51-fb18-49ee-8951-e945e2270d65"
                val conn = URL(ocmUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "CargaPHEV/1.0")
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == 200) {
                    val jsonArray = JSONArray(conn.inputStream.bufferedReader().readText())
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val addressInfo = item.optJSONObject("AddressInfo") ?: continue
                        val title = addressInfo.optString("Title", "Punto de Carga")
                        val itemLat = addressInfo.optDouble("Latitude", 0.0)
                        val itemLon = addressInfo.optDouble("Longitude", 0.0)

                        val connections = item.optJSONArray("Connections")
                        var info = "Cargador PHEV"
                        if (connections != null && connections.length() > 0) {
                            val connObj = connections.getJSONObject(0)
                            val power = connObj.optDouble("PowerKW", 0.0)
                            val connType = connObj.optJSONObject("ConnectionType")?.optString("Title", "Tipo 2") ?: "Tipo 2"
                            info = "$connType | ${power}kW"
                        }

                        listaCargadores.add(CargadorPoint(title, info, itemLat, itemLon))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Mostrar marcadores
            withContext(Dispatchers.Main) {
                val puntosAgregados = mutableListOf<GeoPoint>()
                for (cargador in listaCargadores) {
                    val geo = GeoPoint(cargador.lat, cargador.lon)
                    if (puntosAgregados.none { it.distanceToAsDouble(geo) < 25.0 }) {
                        puntosAgregados.add(geo)
                        val marker = Marker(map)
                        marker.position = geo
                        marker.title = cargador.title
                        marker.snippet = cargador.snippet
                        map.overlays.add(marker)
                    }
                }
                map.invalidate()
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
