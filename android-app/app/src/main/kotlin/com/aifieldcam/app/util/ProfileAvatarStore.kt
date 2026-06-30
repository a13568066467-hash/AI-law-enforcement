package com.aifieldcam.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/** 注册/资料页展示用头像（与人脸模板 JPEG 分开存储） */
object ProfileAvatarStore {

    private const val FILE_NAME = "officer_profile_avatar.jpg"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun avatarFile(): File = File(appContext.filesDir, FILE_NAME)

    fun saveFromJpeg(jpeg: ByteArray) {
        if (jpeg.isEmpty()) return
        avatarFile().writeBytes(jpeg)
    }

    fun delete() {
        avatarFile().delete()
    }

    fun hasAvatar(): Boolean {
        val f = avatarFile()
        return f.exists() && f.length() > 0
    }

    fun bindEmployeePhoto(imageView: ImageView, letterView: TextView, displayName: String) {
        val letter = displayName.firstOrNull()?.uppercaseChar()?.toString().orEmpty().ifBlank { "?" }
        letterView.text = letter
        when {
            hasAvatar() -> bindTo(imageView, letterView)
            FaceAvatarStore.hasAvatar() -> FaceAvatarStore.bindTo(imageView, letterView)
            else -> {
                imageView.visibility = android.view.View.GONE
                letterView.visibility = android.view.View.VISIBLE
            }
        }
    }

    fun bindTo(imageView: ImageView, letterView: TextView) {
        val file = avatarFile()
        if (file.exists() && file.length() > 0) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                imageView.setImageBitmap(bmp)
                imageView.visibility = android.view.View.VISIBLE
                letterView.visibility = android.view.View.GONE
                return
            }
        }
        imageView.visibility = android.view.View.GONE
        letterView.visibility = android.view.View.VISIBLE
    }
}
