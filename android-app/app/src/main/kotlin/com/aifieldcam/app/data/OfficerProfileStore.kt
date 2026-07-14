package com.aifieldcam.app.data

import android.content.Context
import org.json.JSONObject

object OfficerProfileStore {

    private const val PREFS_NAME = "officer_profile"
    private const val KEY_PHONE = "phone"
    private const val KEY_NAME = "name"
    private const val KEY_GENDER = "gender"
    private const val KEY_EMPLOYEE_ID = "employee_id"
    private const val KEY_DEPARTMENT = "department"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FACE_FP = "face_fingerprint"
    private const val KEY_ID_CARD = "id_card"
    private const val KEY_COMPANY = "company"
    private const val KEY_POSITION = "position"

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
            gender = p.getString(KEY_GENDER, "未知").orEmpty().ifBlank { "未知" },
            employeeId = p.getString(KEY_EMPLOYEE_ID, "").orEmpty(),
            department = p.getString(KEY_DEPARTMENT, "").orEmpty(),
            deviceId = p.getString(KEY_DEVICE_ID, "").orEmpty(),
            faceFingerprint = p.getString(KEY_FACE_FP, "").orEmpty(),
            idCard = p.getString(KEY_ID_CARD, "").orEmpty(),
            company = p.getString(KEY_COMPANY, "").orEmpty(),
            position = p.getString(KEY_POSITION, "").orEmpty(),
        )
    }

    fun isRegisteredLocally(): Boolean = isBoundLocally()

    /** 扫码绑定成功后的本机人员摘要是否可用。 */
    fun isBoundLocally(): Boolean {
        val profile = load() ?: return false
        return profile.name.isNotBlank() &&
            (profile.employeeId.isNotBlank() || profile.phone.length == 11)
    }

    fun saveBound(profile: OfficerProfile) {
        prefs().edit()
            .putString(KEY_PHONE, profile.phone)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_GENDER, profile.gender)
            .putString(KEY_EMPLOYEE_ID, profile.employeeId)
            .putString(KEY_DEPARTMENT, profile.department)
            .putString(KEY_DEVICE_ID, profile.deviceId)
            .putString(KEY_FACE_FP, profile.faceFingerprint)
            .putString(KEY_ID_CARD, profile.idCard)
            .putString(KEY_COMPANY, profile.company)
            .putString(KEY_POSITION, profile.position)
            .apply()
    }

    fun saveDraft(profile: OfficerProfile) {
        prefs().edit()
            .putString(KEY_PHONE, profile.phone)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_GENDER, profile.gender)
            .putString(KEY_EMPLOYEE_ID, profile.employeeId)
            .putString(KEY_DEPARTMENT, profile.department)
            .putString(KEY_DEVICE_ID, profile.deviceId)
            .putString(KEY_ID_CARD, profile.idCard)
            .putString(KEY_COMPANY, profile.company)
            .putString(KEY_POSITION, profile.position)
            .apply()
    }

    fun saveRegistered(profile: OfficerProfile, faceFingerprint: String) {
        prefs().edit()
            .putString(KEY_PHONE, profile.phone)
            .putString(KEY_NAME, profile.name)
            .putString(KEY_GENDER, profile.gender)
            .putString(KEY_EMPLOYEE_ID, profile.employeeId)
            .putString(KEY_DEPARTMENT, profile.department)
            .putString(KEY_DEVICE_ID, profile.deviceId)
            .putString(KEY_FACE_FP, faceFingerprint)
            .putString(KEY_ID_CARD, profile.idCard)
            .putString(KEY_COMPANY, profile.company)
            .putString(KEY_POSITION, profile.position)
            .apply()
    }

    fun clear() {
        prefs().edit().clear().apply()
        com.aifieldcam.app.util.FaceAvatarStore.delete()
        com.aifieldcam.app.util.ProfileAvatarStore.delete()
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

