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

        // Configuración requerida por OpenStreetMap
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
 2f - 2f, paint)

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
