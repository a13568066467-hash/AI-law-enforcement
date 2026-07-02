package com.aifieldcam.app

import android.app.Application
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.OfficerProfileStore
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.NativeAudioRecorder
import com.aifieldcam.app.platform.NativeRecorder
import com.aifieldcam.app.platform.Ze69PlatformBootstrap
import com.aifieldcam.app.util.TtsSpeaker
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
        Ze69PlatformBootstrap.onApplicationCreate(this)
        TtsSpeaker.init(this)
    }

    override fun onTerminate() {
        TtsSpeaker.shutdown()
        Ze69PlatformBootstrap.onApplicationTerminate()
        NativeRecorder.release()
        NativeAudioRecorder.release()
        super.onTerminate()
    }
}
