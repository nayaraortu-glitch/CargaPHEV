package com.cargaphev.app.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.navigation.model.PlaceListMapTemplate

class CargaMapScreen(carContext: CarContext) : Screen(carContext) {

    private var mostrarSoloGratis = false

    override fun onGetTemplate(): Template {
        // Botón de acción superior para alternar el filtro
        val actionFiltro = Action.Builder()
            .setTitle(if (mostrarSoloGratis) "Ver Todos" else "Solo Gratis")
            .setOnClickListener {
                mostrarSoloGratis = !mostrarSoloGratis
                invalidate() // Refresca la pantalla al pulsar el botón
            }
            .build()

        val builder = PlaceListMapTemplate.Builder()
            .setTitle("Cargadores PHEV Catalunya")
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(actionFiltro)
                    .build()
            )

        val itemListBuilder = ItemList.Builder()

        // Lista de ejemplo con los puntos de recarga y sus estados
        val cargadoresCoche = listOf(
            Triple("🟢 EVcharge - Eix Macià", "2/2 tomas libres • Gratis • 22 kW (1.2 km)", Pair(41.5518, 2.0998)),
            Triple("🟡 CAP Canovelles", "1/2 tomas libres • Gratis • 22 kW (3.5 km)", Pair(41.6163, 2.2789)),
            Triple("🔴 Pabellón Canovelles", "0/2 tomas ocupadas • Gratis • 22 kW (4.1 km)", Pair(41.6118, 2.2818)),
            Triple("🔵 Recarga Rambla (Estática)", "Punto físico sin tiempo real • Gratis • 22 kW (5.0 km)", Pair(41.5475, 2.1051))
        )

        for (item in cargadoresCoche) {
            val nombre = item.first
            val desc = item.second
            val coords = item.third

            // Si el filtro de solo gratuitos está activo, omitimos los que no lo sean
            if (mostrarSoloGratis && !desc.contains("Gratis")) continue

            val row = Row.Builder()
                .setTitle(nombre)
                .addText(desc)
                .setOnClickListener {
                    val lat = coords.first
                    val lon = coords.second
                    
                    // Lanza Waze directamente en la pantalla de Android Auto
                    val wazeUri = Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes")
                    val intent = Intent(Intent.ACTION_VIEW, wazeUri)
                    intent.setPackage("com.waze")
                    
                    try {
                        carContext.startCarApp(intent)
                    } catch (e: Exception) {
                        val fallbackUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon($nombre)")
                        carContext.startCarApp(Intent(Intent.ACTION_VIEW, fallbackUri))
                    }
                }
                .build()

            itemListBuilder.addItem(row)
        }

        builder.setItemList(itemListBuilder.build())
        builder.setLoading(false)

        return builder.build()
    }
}
