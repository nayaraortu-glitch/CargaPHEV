package com.cargaphev.app.car

import android.content.Intent
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.HeaderAction
import androidx.car.app.model.ItemList
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

class CargaMapScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()

        // Punto de carga 1 de prueba
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Cargador PHEV Rápido - Centro")
                .addText("Disponible • 22 kW AC")
                .setOnClickListener {
                    abrirNavegacion(40.416775, -3.703790, "Cargador PHEV Centro")
                }
                .build()
        )

        // Punto de carga 2 de prueba
        listBuilder.addItem(
            Row.Builder()
                .setTitle("Estación de Carga Gratuita")
                .addText("Disponible • 11 kW")
                .setOnClickListener {
                    abrirNavegacion(40.420000, -3.700000, "Estación de Carga Gratis")
                }
                .build()
        )

        // Retornamos la plantilla de mapa con lista lateral oficial de Android Auto
        return PlaceListMapTemplate.Builder()
            .setTitle("CargaPHEV - Estaciones")
            .setHeaderAction(HeaderAction.APP_ICON)
            .setItemList(listBuilder.build())
            .build()
    }

    private fun abrirNavegacion(lat: Double, lng: Double, etiqueta: String) {
        // Abre la navegación en la app predeterminada (Waze o Google Maps)
        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng($etiqueta)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        carContext.startCarApp(intent)
    }
}
