#ifndef BLE_SERVICE_H
#define BLE_SERVICE_H

#include <stddef.h>
#include <stdint.h>

#include "app_fsm.h"

/** NimBLE 外设：Service A001，广播名 AI-FieldCam */
void ble_service_init(void);
bool ble_service_is_connected(void);

void ble_notify_low_battery(uint8_t percent);
void ble_notify_sensor_state(fsm_sensor_state_t state, uint8_t battery_percent, uint8_t flags);
void ble_notify_event(uint8_t evt_id, const uint8_t *payload, size_t payload_len);

/** 状态变化时立即上报 SENSOR（电量/充电由 power_mgr 周期补发） */
void ble_service_publish_sensor_state(void);

/** CAPTURE_DONE + IMAGE_TX 分片 */
void ble_send_jpeg(const uint8_t *data, size_t len);

#endif /* BLE_SERVICE_H */
