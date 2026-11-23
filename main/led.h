// 防止同一个头文件被重复包含后产生重复声明问题。
#ifndef LED_H
#define LED_H

// 为 LED 开关接口提供 bool、true 和 false 类型及常量。
#include <stdbool.h>

// 使用 ESP-IDF 统一的 esp_err_t 错误码类型。
#include "esp_err.h"

// 配置 LED 引脚为输出并默认关灯；成功返回 ESP_OK，失败返回 GPIO 驱动错误码。
esp_err_t led_init(void);
// 设置 LED 的逻辑状态：true 为开灯，false 为关灯；必须先调用 led_init。
void led_set(bool on);
// 返回软件记录的最近一次开关状态，不代表通过传感器检测到的实际发光状态。
bool led_is_on(void);

#endif
