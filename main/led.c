// 引入 LED 模块对外声明，保持实现与调用方接口一致。
#include "led.h"

// GPIO 驱动用于复位引脚、配置方向和设置输出电平。
#include "driver/gpio.h"

// 当前假设 LED 接在 GPIO2；开发板引脚不同时，需要修改此宏。
#define LED_GPIO GPIO_NUM_2

// 软件保存的 LED 逻辑状态，静态变量上电时默认初始化为 false。
static bool led_on;

// 根据逻辑开关值输出电平，并同步更新供 GATT 读取的状态。
void led_set(bool on)
{
    // 当前按高电平点亮接法处理；低电平点亮的板子需要交换这里的 1 和 0。
    gpio_set_level(LED_GPIO, on ? 1 : 0);
    // 保存期望状态；这里没有检查 GPIO 写入结果，也没有读取实际 LED 亮度。
    led_on = on;
}

// 蓝牙读取特征值时，通过此接口获取 LED 的逻辑开关状态。
bool led_is_on(void)
{
    return led_on;
}

// 由 app_main 在蓝牙启动前调用，完成 LED 硬件的基础准备。
esp_err_t led_init(void)
{
    // 将该引脚恢复到驱动定义的默认配置，清除之前可能存在的 GPIO 设置。
    esp_err_t ret = gpio_reset_pin(LED_GPIO);
    if (ret != ESP_OK) {
        // 复位失败时立即返回，避免继续配置一个状态不确定的引脚。
        return ret;
    }

    // 配置为数字输出，后续才能通过 gpio_set_level 控制引脚电平。
    ret = gpio_set_direction(LED_GPIO, GPIO_MODE_OUTPUT);
    if (ret == ESP_OK) {
        // 上电默认关灯，同时将软件状态与 GPIO 输出状态统一。
        led_set(false);
    }

    // 将方向配置结果交给调用方，app_main 使用 ESP_ERROR_CHECK 检查它。
    return ret;
}
