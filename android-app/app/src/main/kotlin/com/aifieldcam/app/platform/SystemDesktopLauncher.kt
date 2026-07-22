package com.aifieldcam.app.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * 专机软锁维保：尝试离开本 App 进入其它系统桌面（或桌面设置）。
 */
object SystemDesktopLauncher {
    private const val TAG = "SystemDesktop"

    fun open(context: Context): Boolean {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val others = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo }
            .filter { it.packageName != context.packageName }

        for (info in others) {
            val launch = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClassName(info.packageName, info.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(launch)
                true
            } catch (e: Exception) {
                Log.w(TAG, "launch home ${info.packageName} failed: ${e.message}")
                continue
            }
        }

        return try {
            context.startActivity(
                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "HOME_SETTINGS failed: ${e.message}")
            try {
                context.startActivity(
                    Intent.createChooser(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null),
                )
                true
            } catch (e2: Exception) {
                Log.e(TAG, "open system desktop failed: ${e2.message}")
                false
            }
        }
    }
}
