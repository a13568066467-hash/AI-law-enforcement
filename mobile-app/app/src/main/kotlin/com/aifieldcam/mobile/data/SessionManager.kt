package com.aifieldcam.mobile.data

import android.content.Context

data class MobileUser(
    val token: String,
    val phone: String,
    val name: String,
    val employeeId: String,
    val department: String,
)

object SessionManager {

    private const val PREFS = "mobile_session"
    private const val KEY_TOKEN = "token"
    private const val KEY_PHONE = "phone"
    private const val KEY_NAME = "name"
    private const val KEY_EMPLOYEE_ID = "employee_id"
    private const val KEY_DEPARTMENT = "department"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isLoggedIn(): Boolean = getToken().isNotEmpty()

    fun getToken(): String = prefs().getString(KEY_TOKEN, "").orEmpty()

    fun getUser(): MobileUser? {
        val token = getToken()
        if (token.isEmpty()) return null
        return MobileUser(
            token = token,
            phone = prefs().getString(KEY_PHONE, "").orEmpty(),
            name = prefs().getString(KEY_NAME, "").orEmpty(),
            employeeId = prefs().getString(KEY_EMPLOYEE_ID, "").orEmpty(),
            department = prefs().getString(KEY_DEPARTMENT, "").orEmpty(),
        )
    }

    fun saveSession(
        token: String,
        phone: String,
        name: String,
        employeeId: String,
        department: String,
    ) {
        prefs().edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_PHONE, phone)
            .putString(KEY_NAME, name)
            .putString(KEY_EMPLOYEE_ID, employeeId)
            .putString(KEY_DEPARTMENT, department)
            .apply()
    }

    fun clear() {
        prefs().edit().clear().apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
