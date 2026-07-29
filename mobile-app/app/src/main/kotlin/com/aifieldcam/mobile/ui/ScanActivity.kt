package com.aifieldcam.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aifieldcam.mobile.R
import com.aifieldcam.mobile.util.BindQrParser
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 使用 Google Play 服务自带的 Code Scanner（系统侧扫码 UI + 识别），
 * 不再自建 CameraX 预览与应用内 ML Kit 分析管线。
 */
class ScanActivity : AppCompatActivity() {

    private val handled = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        ensureScannerModuleThenStart()
    }

    private fun ensureScannerModuleThenStart() {
        val options = scannerOptions()
        val scanner = GmsBarcodeScanning.getClient(this, options)
        val moduleInstall = ModuleInstall.getClient(this)
        moduleInstall
            .areModulesAvailable(scanner)
            .addOnSuccessListener { response ->
                if (response.areModulesAvailable()) {
                    startSystemScan(scanner)
                    return@addOnSuccessListener
                }
                moduleInstall
                    .installModules(
                        ModuleInstallRequest.newBuilder().addApi(scanner).build(),
                    )
                    .addOnSuccessListener { startSystemScan(scanner) }
                    .addOnFailureListener { failAndFinish(it.message) }
            }
            .addOnFailureListener {
                // 部分机型模块检查失败时仍直接尝试拉起扫码
                startSystemScan(scanner)
            }
    }

    private fun startSystemScan(scanner: com.google.mlkit.vision.codescanner.GmsBarcodeScanner) {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                if (!handled.compareAndSet(false, true)) return@addOnSuccessListener
                val raw = barcode.rawValue?.trim().orEmpty()
                val payload = BindQrParser.parse(raw)
                if (payload == null) {
                    Toast.makeText(this, R.string.scan_invalid_qr, Toast.LENGTH_SHORT).show()
                    finish()
                    return@addOnSuccessListener
                }
                openConfirm(payload.deviceId, payload.token)
            }
            .addOnCanceledListener { finish() }
            .addOnFailureListener { failAndFinish(it.message) }
    }

    private fun scannerOptions(): GmsBarcodeScannerOptions =
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

    private fun openConfirm(deviceId: String, token: String) {
        startActivity(
            Intent(this, BindConfirmActivity::class.java)
                .putExtra(BindConfirmActivity.EXTRA_DEVICE_ID, deviceId)
                .putExtra(BindConfirmActivity.EXTRA_BIND_TOKEN, token),
        )
        finish()
    }

    private fun failAndFinish(message: String?) {
        val text = message?.takeIf { it.isNotBlank() } ?: getString(R.string.scan_failed)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        finish()
    }
}
