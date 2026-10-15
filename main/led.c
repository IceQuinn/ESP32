#include "led.h"

#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#define LED_GPIO GPIO_NUM_2

static uint32_t blink_interval_ms;

void led_set(bool on)
{
    gpio_set_level(LED_GPIO, on ? 1 : 0);
}

esp_err_t led_init(void)
{
    esp_err_t ret = gpio_reset_pin(LED_GPIO);
    if (ret != ESP_OK) {
        return ret;
    }

    ret = gpio_set_direction(LED_GPIO, GPIO_MODE_OUTPUT);
    if (ret == ESP_OK) {
        led_set(false);
    }

    return ret;
}

static void led_blink_task(void *param)
{
    (void)param;
    bool is_on = false;

    while (true) {
        is_on = !is_on;
        led_set(is_on);
        vTaskDelay(pdMS_TO_TICKS(blink_interval_ms));
    }
}

esp_err_t led_start_blink(uint32_t interval_ms)
{
    if (interval_ms == 0) {
        return ESP_ERR_INVALID_ARG;
    }

    blink_interval_ms = interval_ms;
    if (xTaskCreate(led_blink_task, "led_blink", 2048, NULL, 5, NULL) !=
        pdPASS) {
        return ESP_ERR_NO_MEM;
    }

    return ESP_OK;
}
