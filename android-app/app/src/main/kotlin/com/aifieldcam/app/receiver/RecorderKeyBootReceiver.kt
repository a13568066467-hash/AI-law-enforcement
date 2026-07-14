package com.aifieldcam.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aifieldcam.app.platform.RecorderKeyAccessibility

/** 开机完成后静默开启侧键无障碍，不弹窗。 */
class RecorderKeyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        RecorderKeyAccessibility.ensureEnabledSilently(context.applicationContext)
    }
}
