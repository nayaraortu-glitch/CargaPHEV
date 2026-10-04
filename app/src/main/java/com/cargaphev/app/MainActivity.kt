package com.cargaphev.app

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializamos un visor web a pantalla completa nativo libre de cuelgues
        val webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        // Código HTML del mapa Waze Oscuro interactivo
        val htmlMapContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <link rel="stylesheet" href="https://unpkg.com" />
                <script src="https://unpkg.com"></script>
                <style>
                    html, body, #map { height: 100%; margin: 0; padding: 0; background-color: #1a1a1a; }
                    .leaflet-popup-content-wrapper { background: #2b2b2b; color: #fff; border-radius: 8px; font-family: sans-serif; }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <script>
                    // Centramos el mapa inicialmente en España
                    var map = L.map('map').setView([40.4167, -3.7037], 6);
                    L.tileLayer('https://{s}://{z}/{x}/{y}{r}.png').addTo(map);

                    // Cargamos los datos iniciales de los cargadores
                    fetch('https://transparenciacatalunya.cat')
                        .then(res => res.json())
                        .then(data => {
                            data.forEach(item => {
                                var preu = item.preu ? item.preu.toLowerCase() : '';
                                if ((preu.includes('gratis') || preu.includes('0') || preu.length === 0) && item.latitud && item.longitud) {
                                    var promotor = item.promotor ? item.promotor.toUpperCase() : '';
                                    if (promotor.includes('ESTABANELL') || promotor.includes('EVCHARGE') || promotor.includes('ETECNIC')) {
                                        var color = promotor.includes('ESTABANELL') ? '#FF8C00' : '#00BFFF';
                                        L.circleMarker([parseFloat(item.latitud), parseFloat(item.longitud)], {
                                            radius: 8, fillColor: color, color: '#fff', weight: 2, fillOpacity: 0.9
                                        }).addTo(map).bindPopup("<b>" + item.promotor + "</b><br>" + item.municipi + "<br>" + item.adre_a);
                                    }
                                }
                            });
                        });
                </script>
            </body>
            </html>
        """.trimIndent()

        // Cargamos los datos con una URL base segura simulada para evitar bloqueos del sistema de Android
        webView.loadDataWithBaseURL("https://google.com", htmlMapContent, "text/html", "UTF-8", null)
    }
}
