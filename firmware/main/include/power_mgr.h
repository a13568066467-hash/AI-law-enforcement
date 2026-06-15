/**

 * 电池与充电：ADC 低电策略 + 边充边录

 */

#ifndef POWER_MGR_H

#define POWER_MGR_H



#include <stdbool.h>

#include <stdint.h>



/** 与 BLE协议.md SENSOR FLAGS bit0 一致 */

#define SENSOR_FLAG_CHARGING  0x01

#define SENSOR_FLAG_MOUNTED   0x02



void power_mgr_init(void);



uint8_t power_mgr_get_percent(void);

bool power_mgr_is_charging(void);

uint8_t power_mgr_get_sensor_flags(void);



/** 充电中（若启用）可绕过低电禁录 */

bool power_mgr_can_record(void);

bool power_mgr_can_use_ai(void);



#endif /* POWER_MGR_H */


