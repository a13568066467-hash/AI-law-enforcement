package com.aifieldcam.app.data

import android.content.Context

/** 三步验证进度（设置页引导） */
object VerificationStateStore {

    private const val PREFS = "verify_steps"
    private const val KEY_STEP1 = "step1_ok"
    private const val KEY_STEP2 = "step2_ok"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_VERIFY_TOKEN = "verify_token"
    private const val KEY_DEV_CODE = "dev_code"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class State(
        val step1Ok: Boolean,
        val step2Ok: Boolean,
        val sessionId: String,
        val verifyToken: String,
        val devCode: String,
    )

    fun load(): State {
        val p = prefs()
        return State(
            step1Ok = p.getBoolean(KEY_STEP1, false),
            step2Ok = p.getBoolean(KEY_STEP2, false),
            sessionId = p.getString(KEY_SESSION_ID, "").orEmpty(),
            verifyToken = p.getString(KEY_VERIFY_TOKEN, "").orEmpty(),
            devCode = p.getString(KEY_DEV_CODE, "").orEmpty(),
        )
    }

    fun markStep1(sessionId: String) {
        prefs().edit()
            .putBoolean(KEY_STEP1, true)
            .putBoolean(KEY_STEP2, false)
            .putString(KEY_SESSION_ID, sessionId)
            .remove(KEY_VERIFY_TOKEN)
            .remove(KEY_DEV_CODE)
            .apply()
    }

    fun markStep2(verifyToken: String) {
        prefs().edit()
            .putBoolean(KEY_STEP2, true)
            .putString(KEY_VERIFY_TOKEN, verifyToken)
            .apply()
    }

    fun saveDevCode(code: String) {
        prefs().edit().putString(KEY_DEV_CODE, code).apply()
    }

    fun clear() {
        prefs().edit().clear().apply()
    }

    fun stepSummary(): String {
        val s = load()
        val s1 = if (s.step1Ok) "✓" else "○"
        val s2 = if (s.step2Ok) "✓" else "○"
        val s3 = if (s.step2Ok) "○" else "—"
        return "①人员$s1  ②电话$s2  ③人脸$s3"
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
