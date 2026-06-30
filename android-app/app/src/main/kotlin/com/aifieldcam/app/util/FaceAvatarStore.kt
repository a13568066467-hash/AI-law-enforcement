package com.aifieldcam.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.io.File

/** 保存/展示注册或登录时采集的人脸头像 */
object FaceAvatarStore {

    private const val FILE_NAME = "officer_face_avatar.jpg"

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

    fun bindTo(imageView: ImageView, letterView: android.view.View) {
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
