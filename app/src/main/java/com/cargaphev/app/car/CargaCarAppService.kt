package com.cargaphev.app.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CargaCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        // Obligatorio para desarrollo: permite que Android Auto ejecute la app sin verificar certificados de Google Play
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                return CargaMapScreen(carContext)
            }
        }
    }
}
