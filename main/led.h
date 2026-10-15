#ifndef LED_H
#define LED_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

esp_err_t led_init(void);
void led_set(bool on);
esp_err_t led_start_blink(uint32_t interval_ms);

#endif
