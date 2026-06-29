package com.aifieldcam.app.data

data class OfficerProfile(
    val phone: String,
    val name: String,
    val employeeId: String,
    val department: String,
    val deviceId: String,
    val faceFingerprint: String = "",
) {
    fun isCompleteForRegister(): Boolean {
        return phone.length == 11 &&
            name.isNotBlank() &&
            employeeId.isNotBlank() &&
            department.isNotBlank()
    }
}
