# Keep BLE GATT callbacks
-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback {
    public *;
}
