package com.tuapp.cargaphev.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.car.app.navigation.model.PlaceListMapTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
// Importa aquí tu modelo de datos o base de datos local de cargadores

class CargaMapScreen(carContext: CarContext) : Screen(carContext) {

    private var mostrarSoloGratis = false

    override fun onGetTemplate(): Template {
        // 1. Filtrar tu lista de cargadores (todos o solo gratuitos)
        // val listaFiltrada = obtenerCargadoresLocales().filter { !mostrarSoloGratis || it.esGratis }
        
        val builder = PlaceListMapTemplate.Builder()
            .setTitle("Cargadores PHEV Catalunya")
            .setHeader(
                Header.Builder()
                    .setTitle("Cargadores PHEV Catalunya")
                    .addAction(
                        Action.Builder()
                            .setTitle(if (mostrarSoloGratis) "Ver Todos" "Solo Gratis")
                            .setOnClickListener {
                                mostrarSoloGratis = !mostrarSoloGratis
                                invalidate() // Refresca la pantalla al pulsar el botón
                            }
                            .build()
                    )
                    .build()
            )

        val itemListBuilder = ItemList.Builder()

        /* 
           Simulamos los cargadores ordenados por proximidad en tu ruta por Catalunya.
           Aquí recorrerías tu lista ordenada por distancia GPS:
        */
        
        // Ejemplo de elemento para la lista del coche:
        val row = Row.Builder()
            .setTitle("🟢 EVcharge - Eix Macià (1.2 km)")
            .addText("2/2 tomas libres • Gratis • 22 kW")
            .setBrowsable(false)
            .setOnClickListener {
                // Acción de Navegación: Lanza Waze o el navegador predeterminado del coche
                // Coordenadas de ejemplo del punto:
                val lat = 41.5463
                val lon = 2.1086
                val title = "EVcharge - Eix Macià"
                
                val uri = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon($title)")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                // Forzar preferentemente Waze si está instalado en el móvil
                intent.setPackage("com.waze")
                
                try {
                    carContext.startCarApp(intent)
                } catch (e: Exception) {
                    // Si Waze no saltase directamente, abre el navegador genérico de Android Auto
                    carContext.startCarApp(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, 
                        android.net.Uri.parse("geo:0,0?q=$lat,$lon($title)"))
                    )
                }
            }
            .build()

        itemListBuilder.addItem(row)
        builder.setItemList(itemListBuilder.build())
        builder.setLoading(false)

        return builder.build()
    }
}
