package com.aifieldcam.app

import android.app.Application
import com.aifieldcam.app.data.SessionManager

class AiFieldCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SessionManager.getInstance(this)
    }
}
