package com.aifieldcam.app

import android.app.Application
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.NightVisionController

class AiFieldCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiConfig.init(this)
        SessionManager.getInstance(this)
        if (DeviceProfile.isDsjZecn6a1) {
            NightVisionController.startAmbientMonitoring()
        }
    }
}
