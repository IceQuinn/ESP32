#include "bluetooth.h"

#include <string.h>

#include "esp_log.h"
#include "nvs_flash.h"

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "host/ble_gap.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "nimble/nimble_port.h"
#include "services/gap/ble_svc_gap.h"

#define DEVICE_NAME "ESP32_BLE"

static const char *TAG = "bluetooth";
static uint8_t own_addr_type;

static void bluetooth_start_advertising(void);

static int bluetooth_gap_event(struct ble_gap_event *event, void *arg)
{
    (void)arg;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            ESP_LOGI(TAG, "device connected");
        } else {
            ESP_LOGW(TAG, "connection failed, status=%d",
                     event->connect.status);
            bluetooth_start_advertising();
        }
        break;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "device disconnected, reason=%d",
                 event->disconnect.reason);
        bluetooth_start_advertising();
        break;

    default:
        break;
    }

    return 0;
}

static void bluetooth_start_advertising(void)
{
    struct ble_hs_adv_fields fields = {0};
    struct ble_gap_adv_params params = {0};
    const char *name = ble_svc_gap_device_name();

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.name = (uint8_t *)name;
    fields.name_len = strlen(name);
    fields.name_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to set advertising data, rc=%d", rc);
        return;
    }

    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params,
                           bluetooth_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to start advertising, rc=%d", rc);
        return;
    }

    ESP_LOGI(TAG, "advertising started, name=%s", name);
}

static void bluetooth_on_sync(void)
{
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "no available Bluetooth address, rc=%d", rc);
        return;
    }

    rc = ble_hs_id_infer_auto(0, &own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to determine address type, rc=%d", rc);
        return;
    }

    bluetooth_start_advertising();
}

static void bluetooth_host_task(void *param)
{
    (void)param;
    ESP_LOGI(TAG, "NimBLE host started");

    nimble_port_run();
    vTaskDelete(NULL);
}

esp_err_t bluetooth_init(void)
{
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES ||
        ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ret = nvs_flash_erase();
        if (ret != ESP_OK) {
            return ret;
        }
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK) {
        return ret;
    }

    ret = nimble_port_init();
    if (ret != ESP_OK) {
        return ret;
    }

    ble_svc_gap_init();
    if (ble_svc_gap_device_name_set(DEVICE_NAME) != 0) {
        ESP_LOGE(TAG, "failed to set device name");
        return ESP_FAIL;
    }

    ble_hs_cfg.sync_cb = bluetooth_on_sync;

    if (xTaskCreate(bluetooth_host_task, "nimble_host", 4096, NULL, 5, NULL) !=
        pdPASS) {
        ESP_LOGE(TAG, "failed to create NimBLE host task");
        return ESP_ERR_NO_MEM;
    }

    return ESP_OK;
}
