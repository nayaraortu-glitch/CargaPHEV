package com.tuapp.cargaphev.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class CargaCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // En desarrollo y uso privado, permitimos conexiones seguras estándar
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return CargaSession()
    }
}

class CargaSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): androidx.car.app.Screen {
        return CargaMapScreen(carContext)
    }
}
