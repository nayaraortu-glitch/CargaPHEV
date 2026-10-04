package com.cargaphev.app

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configuración requerida por OpenStreetMap antes de inflar la vista
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_main)

        // Configuración del Mapa
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        // Centrar por defecto en España (Madrid)
        val mapController = map.controller
        mapController.setZoom(6.0)
        val startPoint = GeoPoint(40.416775, -3.703790)
        mapController.setCenter(startPoint)

        // Configurar BottomSheet
        val bottomSheet: View? = findViewById(R.id.bottomSheet)
        if (bottomSheet != null) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        // Configurar botón de centrar ubicación
        val btnLocation: FloatingActionButton? = findViewById(R.id.btnCenterLocation)
        btnLocation?.setOnClickListener {
            mapController.animateTo(startPoint)
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
oConector == "DISPONIBLE") tomasOperativas++
                                    conectoresList.add(
                                        ConectorInfo(
                                            numero = totalTomas,
                                            tipo = if (connType.contains("Type 2", true)) "TYPE2F" else connType,
                                            potenciaKw = powerStr,
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

            // 2. Base de Datos Abierta OSM / Overpass (Garantiza cobertura local)
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

    private fun mostrarFichaDesplegable(cargador: CargadorPoint) {
        val tvDireccion = findViewById<TextView>(R.id.tvDireccion)
        val tvNombreEstacion = findViewById<TextView>(R.id.tvNombreEstacion)
        val btnNavegar = findViewById<Button>(R.id.btnNavegar)
        val containerConectores = findViewById<LinearLayout>(R.id.containerConectores)

        tvDireccion.text = cargador.direccion
        tvNombreEstacion.text = cargador.title

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
