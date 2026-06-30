package com.aifieldcam.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficerProfileTest {

    @Test
    fun completeForRegister_requiresAllFields() {
        val ok = OfficerProfile(
            phone = "13158606008",
            name = "张三",
            employeeId = "123456",
            department = "巡查部",
            deviceId = "DSJ-test001",
            idCard = "110101199001011234",
            company = "测试公司",
            position = "巡查员",
        )
        assertTrue(ok.isCompleteForRegister())

        assertFalse(ok.copy(phone = "131").isCompleteForRegister())
        assertFalse(ok.copy(idCard = "").isCompleteForRegister())
        assertFalse(ok.copy(company = "").isCompleteForRegister())
    }
}
