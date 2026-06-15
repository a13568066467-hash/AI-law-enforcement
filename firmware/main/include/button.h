#ifndef BUTTON_H
#define BUTTON_H

#include "app_fsm.h"

/** 初始化三键扫描任务，检测到动作后调用 app_fsm_handle_event */
void button_init(void);

#endif /* BUTTON_H */
