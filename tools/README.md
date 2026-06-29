# 开发与测试工具

主机侧脚本，无需烧录固件或连接真机即可做逻辑回归。

```
tools/
└── tests/
    ├── fsm_host_test.py           # 固件 FSM 状态机回归
    ├── app_ble_logic_test.py      # BLE 图像拼包逻辑
    ├── app_session_logic_test.py  # Session 编排逻辑
    └── app_api_test.py            # 后端 API 冒烟（需 backend 运行）
```

## 运行

```bash
# 项目根目录
python tools/tests/fsm_host_test.py
python tools/tests/app_ble_logic_test.py
python tools/tests/app_session_logic_test.py
python tools/tests/app_api_test.py
```
