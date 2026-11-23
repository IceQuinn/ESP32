// ESP_ERROR_CHECK 用于检查初始化结果，失败时输出错误并终止当前正常启动流程。
#include "esp_err.h"

// 蓝牙模块负责 BLE 广播、手机连接以及 GATT 灯控指令的处理。
#include "bluetooth.h"
// LED 模块负责 GPIO 初始化、开关控制和逻辑状态记录。
#include "led.h"

// ESP-IDF 启动后会在 main 任务中调用此入口，无需自行创建主任务。
void app_main(void)
{
    // 先初始化 LED 并默认关灯，保证手机连接前 GPIO 已处于可控状态。
    ESP_ERROR_CHECK(led_init());
    // 初始化蓝牙并创建独立 Host 任务，广播会在协议栈同步完成后启动。
    ESP_ERROR_CHECK(bluetooth_init());
    // 此处不启动闪灯任务，避免周期性 GPIO 操作覆盖手机下发的开关状态。
    // app_main 返回后，蓝牙 Host 任务仍持续运行并响应手机请求。
}
