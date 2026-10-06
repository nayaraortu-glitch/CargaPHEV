package com.cargaphev.app.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*

class CargaMapScreen(carContext: CarContext) : Screen(carContext) {

    private var mostrarSoloGratis = false

    override fun onGetTemplate(): Template {
        // Botón superior para refrescar / alternar el filtro manualmente como te gusta
        val actionRefrescarFiltro = Action.Builder()
            .setTitle(if (mostrarSoloGratis) "Ver Todos" else "Solo Gratis")
            .setOnClickListener {
                mostrarSoloGratis = !mostrarSoloGratis
                // Fuerza la recarga manual de la lista al pulsar el botón
                invalidate() 
            }
            .build()

        val itemListBuilder = ItemList.Builder()

        // Lista de cargadores gestionada de forma directa y estable
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

            if (mostrarSoloGratis && !desc.contains("Gratis")) continue

            val row = Row.Builder()
                .setTitle(nombre)
                .addText(desc)
                .setOnClickListener {
                    val lat = coords.first
                    val lon = coords.second
                    
                    // Lanzar Waze directamente al pulsar el cargador en el coche
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

        return ListTemplate.Builder()
            .setTitle("Cargadores PHEV Catalunya")
            .setSingleList(itemListBuilder.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(actionRefrescarFiltro)
                    .build()
            )
            .build()
    }
}
