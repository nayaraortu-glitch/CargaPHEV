package com.cargaphev.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
    val lon: Double,
    val colorHex: Int,
    val fuente: String
)

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    // Colores de disponibilidad
    private val COLOR_VERDE = Color.parseColor("#2E7D32")   // Libre / Operativo
    private val COLOR_AMBAR = Color.parseColor("#F57C00")   // Parcialmente libre
    private val COLOR_ROJO = Color.parseColor("#D32F2F")    // Ocupado / Fuera de servicio
    private val COLOR_GRIS = Color.parseColor("#757575")    // Sin información / Desconocido

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
        val arrowBitmap = crearIconoFlechaNavegacion(this)
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), map).apply {
            enableMyLocation()
            enableFollowLocation()
            setPersonIcon(arrowBitmap)
            setDirectionIcon(arrowBitmap)
            setPersonAnchor(0.5f, 0.5f)
            setDirectionAnchor(0.5f, 0.5f)
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

            // 1. Red OpenChargeMap API (Estabanell, EVCharge, Zunder, Endesa, etc. con estado)
            try {
                val ocmUrl = "https://api.openchargemap.io/v3/poi/?output=json&latitude=$lat&longitude=$lon&distance=35&maxresults=250&key=1393eb51-fb18-49ee-8951-e945e2270d65"
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
                        var title = addressInfo.optString("Title", "Punto de Carga")
                        val itemLat = addressInfo.optDouble("Latitude", 0.0)
                        val itemLon = addressInfo.optDouble("Longitude", 0.0)

                        val operatorInfo = item.optJSONObject("OperatorInfo")
                        val operatorName = operatorInfo?.optString("Title") ?: ""
                        if (operatorName.isNotEmpty() && !title.contains(operatorName, true) && operatorName != "(Unknown Operator)") {
                            title = "$operatorName - $title"
                        }

                        val connections = item.optJSONArray("Connections")
                        var totalTomas = 0
                        var tomasOperativas = 0
                        val tipoDetalles = mutableListOf<String>()

                        if (connections != null && connections.length() > 0) {
                            for (c in 0 until connections.length()) {
                                val connObj = connections.getJSONObject(c)
                                val qty = connObj.optInt("Quantity", 1).coerceAtLeast(1)
                                val connType = connObj.optJSONObject("ConnectionType")?.optString("Title", "Tipo 2") ?: "Tipo 2"
                                val power = connObj.optDouble("PowerKW", 0.0)

                                totalTomas += qty
                                val connStatus = connObj.optJSONObject("StatusType")
                                val connStatusId = connStatus?.optInt("ID", 50) ?: 50
                                if (connStatusId == 50) {
                                    tomasOperativas += qty
                                }

                                val powerStr = if (power > 0) "${power.toInt()}kW" else ""
                                tipoDetalles.add("$connType $powerStr".trim())
                            }
                        }

                        val statusObj = item.optJSONObject("StatusType")
                        val statusId = statusObj?.optInt("ID", 0) ?: 0
                        val isOperational = statusObj?.optBoolean("IsOperational", true) ?: true

                        val (color, estadoText) = when {
                            !isOperational || statusId == 100 -> Pair(COLOR_ROJO, "🔴 Fuera de servicio / Ocupado")
                            statusId == 75 -> Pair(COLOR_AMBAR, "🟠 Parcialmente libre ($tomasOperativas/$totalTomas tomas)")
                            totalTomas > 0 && tomasOperativas == totalTomas -> Pair(COLOR_VERDE, "🟢 Disponible ($tomasOperativas/$totalTomas tomas libres)")
                            totalTomas > 0 && tomasOperativas in 1 until totalTomas -> Pair(COLOR_AMBAR, "🟠 Parcialmente libre ($tomasOperativas/$totalTomas tomas)")
                            totalTomas > 0 && tomasOperativas == 0 -> Pair(COLOR_ROJO, "🔴 Totalmente ocupado")
                            statusId == 50 -> Pair(COLOR_VERDE, "🟢 Disponible / Operativo")
                            else -> Pair(COLOR_GRIS, "⚪ Sin información de estado")
                        }

                        val snippet = "$estadoText\n${tipoDetalles.distinct().joinToString(" | ")}"
                        listaCargadores.add(CargadorPoint(title, snippet, itemLat, itemLon, color, "OCM"))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Red Overpass API (OSM nodes + ways para garantizar cobertura)
            try {
                val query = "[out:json];(node[\"amenity\"=\"charging_station\"](around:25000,$lat,$lon);way[\"amenity\"=\"charging_station\"](around:25000,$lat,$lon););out center;"
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val overpassUrl = "https://overpass-api.de/api/interpreter?data=$encodedQuery"
                val conn = URL(overpassUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "CargaPHEV/1.0")
                conn.connectTimeout = 12000
                conn.readTimeout = 12000

                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val elements = json.optJSONArray("elements")
                    if (elements != null) {
                        for (i in 0 until elements.length()) {
                            val elem = elements.getJSONObject(i)
                            
                            val eLat = if (elem.has("center")) elem.getJSONObject("center").optDouble("lat") else elem.optDouble("lat")
                            val eLon = if (elem.has("center")) elem.getJSONObject("center").optDouble("lon") else elem.optDouble("lon")
                            
                            if (eLat == 0.0 || eLon == 0.0) continue

                            val tags = elem.optJSONObject("tags")
                            val operator = tags?.optString("operator") ?: tags?.optString("network") ?: tags?.optString("brand") ?: ""
                            val name = tags?.optString("name") ?: tags?.optString("ref") ?: "Punto de Carga"

                            val title = when {
                                operator.isNotEmpty() && !name.contains(operator, true) -> "$operator - $name"
                                else -> name
                            }

                            val capacity = tags?.optString("capacity") ?: ""
                            val socketType2 = tags?.optString("socket:type2")
                            val socketCCS = tags?.optString("socket:type2_combo") ?: tags?.optString("socket:ccs")

                            var infoSocket = "Tipo 2 / PHEV"
                            if (socketType2 != null) infoSocket = "Tipo 2 ($socketType2)"
                            if (socketCCS != null) infoSocket += " | CCS ($socketCCS)"

                            val opStatus = tags?.optString("operational_status") ?: ""
                            val (color, estadoText) = when {
                                opStatus == "broken" || opStatus == "out_of_order" -> Pair(COLOR_ROJO, "🔴 Fuera de servicio")
                                capacity.isNotEmpty() -> Pair(COLOR_VERDE, "🟢 Operativo ($capacity tomas)")
                                else -> Pair(COLOR_GRIS, "⚪ Sin verificación en tiempo real")
                            }

                            val snippet = "$estadoText\n$infoSocket"
                            listaCargadores.add(CargadorPoint(title, snippet, eLat, eLon, color, "OSM"))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Actualizar interfaz con los nuevos marcadores
            withContext(Dispatchers.Main) {
                val markersToRemove = map.overlays.filterIsInstance<Marker>()
                map.overlays.removeAll(markersToRemove)

                val puntosAgregados = mutableListOf<GeoPoint>()
                for (cargador in listaCargadores) {
                    val geo = GeoPoint(cargador.lat, cargador.lon)
                    if (puntosAgregados.none { it.distanceToAsDouble(geo) < 35.0 }) {
                        puntosAgregados.add(geo)

                        val marker = Marker(map)
                        marker.position = geo
                        marker.title = cargador.title
                        marker.snippet = cargador.snippet
                        marker.icon = crearIconoEnchufeEV(this@MainActivity, cargador.colorHex)
                        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

                        map.overlays.add(marker)
                    }
                }
                map.invalidate()
            }
        }
    }

    // Dibuja el marcador de enchufe EV
    private fun crearIconoEnchufeEV(context: Context, colorInt: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (36 * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sombra
        paint.color = Color.parseColor("#40000000")
        canvas.drawCircle(sizePx / 2f, sizePx / 2f + 2f, sizePx / 2f - 2f, paint)

        // Círculo base con el color de disponibilidad
        paint.color = colorInt
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 3f, paint)

        // Borde interior blanco
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, paint)

        // Dibujo del enchufe EV en blanco
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE

        val cx = sizePx / 2f
        val cy = sizePx / 2f

        val rectPlug = RectF(cx - 5f * density, cy - 3f * density, cx + 5f * density, cy + 6f * density)
        canvas.drawRoundRect(rectPlug, 2f * density, 2f * density, paint)

        // Clavijas de conexión
        canvas.drawRect(cx - 3.5f * density, cy - 7.5f * density, cx - 1.5f * density, cy - 3f * density, paint)
        canvas.drawRect(cx + 1.5f * density, cy - 7.5f * density, cx + 3.5f * density, cy + -3f * density, paint)

        // Cable inferior
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = Color.WHITE
        val pathCable = Path()
        pathCable.moveTo(cx, cy + 6f * density)
        pathCable.cubicTo(cx, cy + 10f * density, cx + 5f * density, cy + 10f * density, cx + 5f * density, cy + 13f * density)
        canvas.drawPath(pathCable, paint)

        return BitmapDrawable(context.resources, bitmap)
    }

    // Dibuja la flecha azul de navegación para la ubicación GPS
    private fun crearIconoFlechaNavegacion(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (38 * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sombra de la flecha
        paint.color = Color.parseColor("#40000000")
        val shadowPath = Path()
        shadowPath.moveTo(sizePx / 2f, 4f * density + 2f)
        shadowPath.lineTo(sizePx - 6f * density, sizePx - 6f * density + 2f)
        shadowPath.lineTo(sizePx / 2f, sizePx - 11f * density + 2f)
        shadowPath.lineTo(6f * density, sizePx - 6f * density + 2f)
        shadowPath.close()
        canvas.drawPath(shadowPath, paint)

        // Flecha azul de navegación
        paint.color = Color.parseColor("#1976D2")
        val arrowPath = Path()
        arrowPath.moveTo(sizePx / 2f, 4f * density)
        arrowPath.lineTo(sizePx - 6f * density, sizePx - 6f * density)
        arrowPath.lineTo(sizePx / 2f, sizePx - 11f * density)
        arrowPath.lineTo(6f * density, sizePx - 6f * density)
        arrowPath.close()
        canvas.drawPath(arrowPath, paint)

        // Contorno blanco
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * density
        canvas.drawPath(arrowPath, paint)

        return bitmap
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
