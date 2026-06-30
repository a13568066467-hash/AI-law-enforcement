package com.aifieldcam.app.data

data class OfficerProfile(
    val phone: String,
    val name: String,
    val employeeId: String,
    val department: String,
    val deviceId: String,
    val faceFingerprint: String = "",
    val idCard: String = "",
    val company: String = "",
    val position: String = "",
) {
    fun isCompleteForRegister(): Boolean {
        return phone.length == 11 &&
            name.isNotBlank() &&
            employeeId.isNotBlank() &&
            department.isNotBlank() &&
            company.isNotBlank() &&
            position.isNotBlank() &&
            idCard.length == 18
    }
}
