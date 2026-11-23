// 头文件保护宏，允许多个源文件安全地包含本接口。
#ifndef BLUETOOTH_H
#define BLUETOOTH_H

#include "esp_err.h"

// 自定义 LED 服务 UUID，Android 扫描过滤和服务发现必须使用相同值。
#define LED_SERVICE_UUID "12345678-1234-5678-1234-56789abcdef0"
// LED 状态特征 UUID，支持读取当前状态和带响应写入单字节开关指令。
#define LED_CHARACTERISTIC_UUID "12345678-1234-5678-1234-56789abcdef1"

// 协议值为二进制单字节：0x00 关灯、0x01 开灯，不是 ASCII 字符串 "00" 或 "01"。
// 初始化 NVS、NimBLE、GAP 和 GATT，并创建 Host 任务；设计为启动时调用一次。
// 返回 ESP_OK 只表示初始化和任务创建成功，广播是否成功需查看异步日志。
// 本版未要求配对、加密或身份认证，附近可连接的 BLE 客户端均可访问灯控服务。
esp_err_t bluetooth_init(void);

#endif
