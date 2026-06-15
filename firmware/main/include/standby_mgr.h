/**

 * 待机续航：AI 聆听超时关麦、空闲轻睡眠、IMU 静置（占位）

 */

#ifndef STANDBY_MGR_H

#define STANDBY_MGR_H



#include "app_fsm.h"



typedef enum {

    STANDBY_PROFILE_ACTIVE = 0,

    STANDBY_PROFILE_AI_LISTEN,

    STANDBY_PROFILE_IDLE_WAIT,

    STANDBY_PROFILE_LIGHT_SLEEP,

} standby_profile_t;



void standby_mgr_init(void);



/** 按键、BLE、FSM 状态变化等用户/业务活动 */

void standby_mgr_notify_activity(void);



/** FSM 进入新状态时调用（在 enter_state 内） */

void standby_mgr_on_state_changed(glass_state_t state);



standby_profile_t standby_mgr_get_profile(void);



#endif /* STANDBY_MGR_H */


