#include "esp_err.h"

#include "bluetooth.h"
#include "led.h"

void app_main(void)
{
    ESP_ERROR_CHECK(led_init());
    ESP_ERROR_CHECK(bluetooth_init());
    ESP_ERROR_CHECK(led_start_blink(500));
}
