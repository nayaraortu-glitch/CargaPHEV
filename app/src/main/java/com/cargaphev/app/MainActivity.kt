package com.cargaphev.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
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
import kotlin.math.roundToInt

data class ConectorInfo(
    val numero: Int,
    val tipo: String,          // Ej: TYPE2F, CCS2
    val potenciaKw: String,    // Ej: 8 kW - Semi rápida
    val esGratuito: Boolean,   // true = Gratuito, false = De Pago
    val estado: String         // "DISPONIBLE", "OCUPADO", "FUERA_DE_SERVICIO"
)

data class CargadorPoint(
    val id: String,
    val title: String,
    val direccion: String,
    val lat: Double,
    val lon: Double,
    val esGratuito: Boolean,
    val colorHex: Int,
    val conectores: List<ConectorInfo>
)

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    // Filtros
    private var filtroSoloGratuitos = false
    private var filtroSoloTipo2 = false

    private val todosLosCargadores = mutableListOf<CargadorPoint>()

    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private val COLOR_VERDE = Color.parseColor("#2E7D32")   // Libre / Operativo
    private val COLOR_AMBAR = Color.parseColor("#F57C00")   // Parcialmente libre
    private val COLOR_ROJO = Color.parseColor("#D32F2F")    // Fuera de servicio
    private val COLOR_GRIS = Color.parseColor("#757575")    // Desconocido

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(applicationContext, getSharedPreferences("osm_prefs", MODE_PRIVATE))

        setContentView(R.layout.activity_main)

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        val bottomSheetView = findViewById<View>(R.id.bottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetView)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        val defaultPoint = GeoPoint(41.545, 2.108) // Sabadell / Vallès
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
                Toast.makeText(this, "Obteniendo ubicación GPS...", Toast.LENGTH_SHORT).show()
            }
        }

        // Configuración de Chips de Filtro
        val chipGratuitos = findViewById<Chip>(R.id.chipGratuitos)
        chipGratuitos.setOnCheckedChangeListener { _, isChecked ->
            filtroSoloGratuitos = isChecked
            aplicarFiltrosYRenderizar()
        }

        val chipTipo2 = findViewById<Chip>(R.id.chipTipo2)
        chipTipo2.setOnCheckedChangeListener { _, isChecked ->
            filtroSoloTipo2 = isChecked
            aplicarFiltrosYRenderizar()
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

    private fun cargarPuntosDeCarga(lat: Double, lon: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            val lista = mutableListOf<CargadorPoint>()

            // 1. OpenChargeMap API (Cargadores de Catalunya, Etecnic, Estabanell, EVCharge)
            try {
                val ocmUrl = "https://api.openchargemap.io/v3/poi/?output=json&latitude=$lat&longitude=$lon&distance=35&maxresults=250&key=1393eb51-fb18-49ee-8951-e945e2270d65"
                val conn = URL(ocmUrl).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "CargaPHEV/1.0")
                conn.connectTimeout = 10000

                if (conn.responseCode == 200) {
                    val jsonArray = JSONArray(conn.inputStream.bufferedReader().readText())
                    for (i in 0 until jsonArray.length()) {
                        val item = jsonArray.getJSONObject(i)
                        val addressInfo = item.optJSONObject("AddressInfo") ?: continue
                        var title = addressInfo.optString("Title", "Punto de Carga")
                        val addressLine = addressInfo.optString("AddressLine1", "")
                        val town = addressInfo.optString("Town", "")
                        val fullAddress = if (addressLine.isNotEmpty()) "$addressLine, $town" else title

                        val itemLat = addressInfo.optDouble("Latitude", 0.0)
                        val itemLon = addressInfo.optDouble("Longitude", 0.0)

                        val operatorInfo = item.optJSONObject("OperatorInfo")
                        val operatorName = operatorInfo?.optString("Title") ?: ""
                        if (operatorName.isNotEmpty() && !title.contains(operatorName, true) && operatorName != "(Unknown Operator)") {
                            title = "$operatorName - $title"
                        }

                        val usageType = item.optJSONObject("UsageType")
                        val isFree = usageType?.optBoolean("IsPayAtLocation", false) == false || usageType?.optBoolean("IsFreeMembership", false) == true

                        val connections = item.optJSONArray("Connections")
                        val conectoresList = mutableListOf<ConectorInfo>()
                        var tomasOperativas = 0
                        var totalTomas = 0

                        if (connections != null && connections.length() > 0) {
                            for (c in 0 until connections.length()) {
                                val connObj = connections.getJSONObject(c)
                                val qty = connObj.optInt("Quantity", 1).coerceAtLeast(1)
                                val connType = connObj.optJSONObject("ConnectionType")?.optString("Title", "Tipo 2") ?: "TYPE2"
                                val power = connObj.optDouble("PowerKW", 0.0)
                                val powerStr = if (power > 0) "${power.toInt()} kW" else "Semi rápida"

                                val connStatus = connObj.optJSONObject("StatusType")
                                val connStatusId = connStatus?.optInt("ID", 50) ?: 50
                                val estadoConector = when (connStatusId) {
                                    50 -> "DISPONIBLE"
                                    100, 75 -> "OCUPADO"
                                    else -> "FUERA_DE_SERVICIO"
                                }

                                for (q in 1..qty) {
                                    totalTomas++
                                    if (estadoConector == "DISPONIBLE") tomasOperativas++
                                    conectoresList.add(
                                        ConectorInfo(
                                            numero = totalTomas,
                                            tipo = if (connType.contains("Type 2", true)) "TYPE2F" else connType,
                                            potenciaKw = "$powerStr",
                                            esGratuito = isFree,
                                            estado = estadoConector
                                        )
                                    )
                                }
                            }
                        }

                        val color = when {
                            tomasOperativas == totalTomas && totalTomas > 0 -> COLOR_VERDE
                            tomasOperativas in 1 until totalTomas -> COLOR_AMBAR
                            tomasOperativas == 0 && totalTomas > 0 -> COLOR_ROJO
                            else -> COLOR_GRIS
                        }

                        lista.add(CargadorPoint(item.optString("ID", "$i"), title, fullAddress, itemLat, itemLon, isFree, color, conectoresList))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 2. Base de Datos Abierta OSM / Overpass (Garantiza cobertura de Estabanell y EVCharge)
            try {
                val query = "[out:json];(node[\"amenity\"=\"charging_station\"](around:25000,$lat,$lon);way[\"amenity\"=\"charging_station\"](around:25000,$lat,$lon););out center;"
                val overpassUrl = "https://overpass-api.de/api/interpreter?data=${URLEncoder.encode(query, "UTF-8")}"
                val conn = URL(overpassUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000

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
                            val operator = tags?.optString("operator") ?: tags?.optString("network") ?: ""
                            val name = tags?.optString("name") ?: "Punto de Carga"
                            val title = if (operator.isNotEmpty() && !name.contains(operator, true)) "$operator - $name" else name

                            val fee = tags?.optString("fee") ?: "no"
                            val isFree = fee.lowercase() == "no" || fee.lowercase() == "false"

                            val conectoresList = listOf(
                                ConectorInfo(1, "TYPE2F", "Semi rápida 8-22 kW", isFree, "DISPONIBLE"),
                                ConectorInfo(2, "TYPE2F", "Semi rápida 8-22 kW", isFree, "DISPONIBLE")
                            )

                            lista.add(CargadorPoint("osm_$i", title, title, eLat, eLon, isFree, COLOR_VERDE, conectoresList))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                todosLosCargadores.clear()
                todosLosCargadores.addAll(lista)
                aplicarFiltrosYRenderizar()
            }
        }
    }

    private fun aplicarFiltrosYRenderizar() {
        val marcadoresPrevios = map.overlays.filterIsInstance<Marker>()
        map.overlays.removeAll(marcadoresPrevios)

        val cargadoresFiltrados = todosLosCargadores.filter { cargador ->
            val cumpleGratuito = if (filtroSoloGratuitos) cargador.esGratuito else true
            val cumpleTipo2 = if (filtroSoloTipo2) cargador.conectores.any { it.tipo.contains("TYPE2", true) || it.tipo.contains("Type 2", true) } else true
            cumpleGratuito && cumpleTipo2
        }

        val puntosAgregados = mutableListOf<GeoPoint>()
        for (cargador in cargadoresFiltrados) {
            val geo = GeoPoint(cargador.lat, cargador.lon)
            if (puntosAgregados.none { it.distanceToAsDouble(geo) < 30.0 }) {
                puntosAgregados.add(geo)

                val marker = Marker(map)
                marker.position = geo
                marker.title = cargador.title
                marker.icon = crearIconoEnchufeEV(this, cargador.colorHex)
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)

                marker.setOnMarkerClickListener { _, _ ->
                    mostrarFichaDesplegable(cargador)
                    true
                }

                map.overlays.add(marker)
            }
        }
        map.invalidate()
    }

    // Muestra la ficha al hacer clic en el marcador (idéntica a la imagen 2)
    private fun mostrarFichaDesplegable(cargador: CargadorPoint) {
        val tvDireccion = findViewById<TextView>(R.id.tvDireccion)
        val tvNombreEstacion = findViewById<TextView>(R.id.tvNombreEstacion)
        val btnNavegar = findViewById<Button>(R.id.btnNavegar)
        val containerConectores = findViewById<LinearLayout>(R.id.containerConectores)

        tvDireccion.text = cargador.direccion
        tvNombreEstacion.text = cargador.title

        // Cálculo de distancia en km desde la ubicación actual
        val myLoc = myLocationOverlay?.myLocation
        if (myLoc != null) {
            val distKm = (myLoc.distanceToAsDouble(GeoPoint(cargador.lat, cargador.lon)) / 1000.0).roundToInt()
            btnNavegar.text = "➔ $distKm km"
        } else {
            btnNavegar.text = "➔ Navegar"
        }

        btnNavegar.setOnClickListener {
            val gmmIntentUri = Uri.parse("google.navigation:q=${cargador.lat},${cargador.lon}")
            val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
            mapIntent.setPackage("com.google.android.apps.maps")
            startActivity(mapIntent)
        }

        // Renderizar la lista de tomas/conectores
        containerConectores.removeAllViews()
        for (conector in cargador.conectores) {
            val view = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, containerConectores, false)
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)

            val tagGratuito = if (conector.esGratuito) " [Gratuito]" else " [Pago]"
            text1.text = "${conector.numero}. 🔌 ${conector.tipo} - ${conector.potenciaKw} $tagGratuito"
            text1.textSize = 14f
            text1.setTextColor(Color.BLACK)

            when (conector.estado) {
                "FUERA_DE_SERVICIO" -> {
                    text2.text = "🔴 Fuera de servicio"
                    text2.setTextColor(COLOR_ROJO)
                }
                "OCUPADO" -> {
                    text2.text = "🟠 Ocupado"
                    text2.setTextColor(COLOR_AMBAR)
                }
                else -> {
                    text2.text = "🟢 Disponible"
                    text2.setTextColor(COLOR_VERDE)
                }
            }
            containerConectores.addView(view)
        }

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun crearIconoEnchufeEV(context: Context, colorInt: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val sizePx = (36 * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.parseColor("#40000000")
        canvas.drawCircle(sizePx / 2f, sizePx / 2f + 2f, sizePx / 2f - 2f, paint)

        paint.color = colorInt
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 3f, paint)

        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        canvas.drawCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f - 4f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        val cx = sizePx / 2f
        val cy = sizePx / 2f

        val rectPlug = RectF(cx - 5f * density, cy - 3f * density, cx + 5f * density, cy + 6f * density)
        canvas.drawRoundRect(rectPlug, 2f * density, 2f * density, paint)

        canvas.drawRect(cx - 3.5f * density, cy - 7.5f * density, cx - 1.5f * density, cy - 3f * density, paint)
        canvas.drawRect(cx + 1.5f * density, cy - 7.5f * density, cx + 3.5f * density, cy - 3f * density, paint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun crearIconoFlechaNavegacion(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val sizePx = (38 * density).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.parseColor("#1976D2")
        val arrowPath = Path()
        arrowPath.moveTo(sizePx / 2f, 4f * density)
        arrowPath.lineTo(sizePx - 6f * density, sizePx - 6f * density)
        arrowPath.lineTo(sizePx / 2f, sizePx - 11f * density)
        arrowPath.lineTo(6f * density, sizePx - 6f * density)
        arrowPath.close()
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
gaPHEV/1.0")
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
