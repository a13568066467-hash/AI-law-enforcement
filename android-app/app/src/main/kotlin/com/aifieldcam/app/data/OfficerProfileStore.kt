package com.aifieldcam.app.data

import android.content.Context
import org.json.JSONObject

object OfficerProfileStore {

    private const val PREFS_NAME = "officer_profile"
    private const val KEY_PHONE = "phone"
    private const val KEY_NAME = "name"
    private const val KEY_EMPLOYEE_ID = "employee_id"
    private const val KEY_DEPARTMENT = "department"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FACE_FP = "face_fingerprint"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun load(): OfficerProfile? {
        val p = prefs()
        val phone = p.getString(KEY_PHONE, "").orEmpty()
        if (phone.isEmpty()) return null
        return OfficerProfile(
            phone = phone,
            name = p.getString(KEY_NAME, "").orEmpty(),
            employeeId = p.getString(KEY_EMPLOYEE_ID, "").orEmpty(),
            department = p.getString(KEY_DEPARTMENT, "").orEmpty(),
            deviceId = p.getString(KEY_DEVICE_ID, "").orEmpty(),
            faceFingerprint = p.getString(KEY_FACE_FP, "").orEmpty(),
        )
    }

    fun saveDraft(profile: OfficerProfile) {
        prefs().edit()
            .putString(KEY_PHONE, profile.phone)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_EMPLOYEE_ID, profile.employeeId)
            .putString(KEY_DEPARTMENT, profile.department)
            .putString(KEY_DEVICE_ID, profile.deviceId)
            .apply()
    }

    fun saveRegistered(profile: OfficerProfile, faceFingerprint: String) {
        prefs().edit()
            .putString(KEY_PHONE, profile.phone)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_EMPLOYEE_ID, profile.employeeId)
            .putString(KEY_DEPARTMENT, profile.department)
            .putString(KEY_DEVICE_ID, profile.deviceId)
            .putString(KEY_FACE_FP, faceFingerprint)
            .apply()
    }

    fun clear() {
        prefs().edit().clear().apply()
    }

    fun loadDraftJson(): JSONObject {
        val profile = load()
        return JSONObject()
            .put(KEY_PHONE, profile?.phone.orEmpty())
            .put(KEY_NAME, profile?.name.orEmpty())
            .put(KEY_EMPLOYEE_ID, profile?.employeeId.orEmpty())
            .put(KEY_DEPARTMENT, profile?.department.orEmpty())
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
