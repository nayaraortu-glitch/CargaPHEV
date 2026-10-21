package com.cargaphev.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Comprobar si se abrió por comando de voz o enlace externo
        handleVoiceOrDeepLinkIntent(intent)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)

            addView(TextView(context).apply {
                text = "⚡ CargaPHEV Voice"
                textSize = 24f
                setPadding(0, 0, 0, 15)
            })

            addView(TextView(context).apply {
                text = "Buscar cargadores GRATUITOS y LIBRES en tiempo real:"
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 40)
            })

            addView(Button(context).apply {
                text = "🗺️ Google Maps"
                setOnClickListener { searchRealtimeInGoogleMaps() }
            })

            addView(Button(context).apply {
                text = "🚗 Waze"
                setOnClickListener { searchRealtimeInWaze() }
            })

            addView(Button(context).apply {
                text = "📡 TomTom"
                setOnClickListener { searchRealtimeInTomTom() }
            })
        }

        setContentView(layout)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceOrDeepLinkIntent(intent)
    }

    private fun handleVoiceOrDeepLinkIntent(intent: Intent?) {
        if (intent == null) return

        // 1. Obtención del parámetro desde el intent de voz o URI
        val voiceParam = intent.getStringExtra("navApp")?.lowercase()
            ?: intent.data?.getQueryParameter("app")?.lowercase()

        when {
            voiceParam?.contains("waze") == true -> {
                Toast.makeText(this, "Ejecutando CargaPHEV en Waze...", Toast.LENGTH_SHORT).show()
                searchRealtimeInWaze()
            }
            voiceParam?.contains("maps") == true || voiceParam?.contains("google") == true -> {
                Toast.makeText(this, "Ejecutando CargaPHEV en Google Maps...", Toast.LENGTH_SHORT).show()
                searchRealtimeInGoogleMaps()
            }
            voiceParam?.contains("tom") == true || voiceParam?.contains("tomtom") == true -> {
                Toast.makeText(this, "Ejecutando CargaPHEV en TomTom...", Toast.LENGTH_SHORT).show()
                searchRealtimeInTomTom()
            }
        }
    }

    private fun searchRealtimeInGoogleMaps() {
        val uri = Uri.parse("geo:0,0?q=cargadores+coches+electricos+gratuitos+libres")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/cargadores+coches+electricos+gratuitos+libres")))
        }
    }

    private fun searchRealtimeInWaze() {
        val uri = Uri.parse("https://waze.com/ul?q=cargadores%20electricos%20gratis%20disponibles&navigate=yes")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Waze no está instalado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun searchRealtimeInTomTom() {
        val uri = Uri.parse("geo:0,0?q=puntos+recarga+gratis+disponibles")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.tomtom.speedcams.android.map")
        }
        try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "TomTom no está instalado", Toast.LENGTH_SHORT).show()
        }
    }
}
