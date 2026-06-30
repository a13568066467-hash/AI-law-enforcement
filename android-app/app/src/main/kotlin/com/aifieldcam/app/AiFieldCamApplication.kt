package com.aifieldcam.app

import android.app.Application
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.OfficerProfileStore
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.NightVisionController
import com.aifieldcam.app.util.FaceAvatarStore
import com.aifieldcam.app.util.ProfileAvatarStore

class AiFieldCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiConfig.init(this)
        AuthConfig.init(this)
        OfficerProfileStore.init(this)
        VerificationStateStore.init(this)
        FaceAvatarStore.init(this)
        ProfileAvatarStore.init(this)
        BackendDiscovery.init(this)
        SessionManager.getInstance(this)
        BackendDiscovery.ensureReachable()
        if (DeviceProfile.isDsjZecn6a1) {
            NightVisionController.startAmbientMonitoring()
        }
    }
}
