package com.aifieldcam.mobile

import android.app.Application
import com.aifieldcam.mobile.data.ApiConfig
import com.aifieldcam.mobile.data.SessionManager

class MobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiConfig.init(this)
        SessionManager.init(this)
    }
}
