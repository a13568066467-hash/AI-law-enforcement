package com.aifieldcam.app

import android.app.Application
import com.aifieldcam.app.data.ApiConfig
import com.aifieldcam.app.data.AuthConfig
import com.aifieldcam.app.data.BackendDiscovery
import com.aifieldcam.app.data.BindBootMarker
import com.aifieldcam.app.data.MqttConfig
import com.aifieldcam.app.data.OfficerProfileStore
import com.aifieldcam.app.data.VerificationStateStore
import com.aifieldcam.app.data.SessionManager
import com.aifieldcam.app.platform.DeviceProfile
import com.aifieldcam.app.platform.MqttClient
import com.aifieldcam.app.platform.NativeAudioRecorder
import com.aifieldcam.app.platform.NativeRecorder
import com.aifieldcam.app.platform.Ze69PlatformBootstrap
import com.aifieldcam.app.platform.commandcall.CommandCallRoom
import com.aifieldcam.app.platform.commandcall.TrtcCommandCallRoomAdapter
import com.aifieldcam.app.util.FaceAvatarStore
import com.aifieldcam.app.util.PhoneCameraHelper
import com.aifieldcam.app.util.ProfileAvatarStore
import com.aifieldcam.app.util.TtsSpeaker

class AiFieldCamApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 生产默认真 TRTC；单测经 CommandCallController.resetForTests → resetToFake()
        CommandCallRoom.use(TrtcCommandCallRoomAdapter(this))
        ApiConfig.init(this)
        AuthConfig.init(this)
        MqttConfig.init(this)
        OfficerProfileStore.init(this)
        VerificationStateStore.init(this)
        FaceAvatarStore.init(this)
        ProfileAvatarStore.init(this)
        BackendDiscovery.init(this)
        BindBootMarker.prepareBoot(this)
        val session = SessionManager.getInstance(this)

        // MQTT 信令通道回调接线
        MqttClient.addOnConnected { session.onMqttConnected() }
        MqttClient.addOnDisconnected { session.onMqttDisconnected() }

        if (DeviceProfile.isDsjZecn6a1) {
            android.util.Log.i(
                "AiFieldCam",
                "存储: ${PhoneCameraHelper.storageSummary(this)}",
            )
        }
        BackendDiscovery.ensureReachable()
        Ze69PlatformBootstrap.onApplicationCreate(this)
        TtsSpeaker.init(this)

        BindBootMarker.notifyCloudAfterReboot(this)

        // 尝试连接 MQTT（若未配置则自动跳过）
        MqttClient.connect()
    }

    override fun onTerminate() {
        TtsSpeaker.shutdown()
        MqttClient.shutdown()
        Ze69PlatformBootstrap.onApplicationTerminate()
        NativeRecorder.release()
        NativeAudioRecorder.release()
        super.onTerminate()
    }
}
