idf_component_register(
    SRCS
        "main.c"
        "bluetooth.c"
        "led.c"
    INCLUDE_DIRS "."
    PRIV_REQUIRES
        bt
        nvs_flash
        esp_driver_gpio
)
