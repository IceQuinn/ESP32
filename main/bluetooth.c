// 本模块实现 BLE 外设，手机作为中心设备连接后读取或写入 LED 特征值。
#include "bluetooth.h"

// strlen 用于计算广播扫描响应中的设备名长度，不包含字符串末尾的空字符。
#include <string.h>

// ESP_LOG 系列宏输出日志，NVS 接口负责初始化蓝牙依赖的非易失存储分区。
#include "esp_log.h"
#include "nvs_flash.h"

// FreeRTOS 任务接口用于在独立任务中运行 NimBLE Host 事件循环。
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// GAP 管理发现、广播和连接，GATT 定义服务以及特征值的访问行为。
#include "host/ble_gap.h"
#include "host/ble_gatt.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
// NimBLE 移植层负责协议栈初始化及 Host 主循环。
#include "nimble/nimble_port.h"
// 标准 GAP/GATT 服务与下面的自定义 LED 服务一起注册到属性数据库中。
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

// 将收到的蓝牙指令交给 LED 模块执行，不在蓝牙模块中直接操作 GPIO。
#include "led.h"

// 手机主动扫描时可从扫描响应中看到此名称。
#define DEVICE_NAME "ESP32_BLE"

// 日志标签，串口中可据此筛选蓝牙模块的运行信息。
static const char *TAG = "bluetooth";
// 本机广播使用的地址类型，协议栈同步后自动推断得到。
static uint8_t own_addr_type;
// NimBLE 注册属性时填入的特征值句柄；客户端应按 UUID 发现，而不要硬编码句柄。
static uint16_t led_characteristic_handle;

// 对应服务 UUID 12345678-1234-5678-1234-56789abcdef0，字节按 NimBLE 小端顺序排列。
static const ble_uuid128_t led_service_uuid = BLE_UUID128_INIT(
    0xf0, 0xde, 0xbc, 0x9a, 0x78, 0x56, 0x34, 0x12,
    0x78, 0x56, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12);

// 对应特征 UUID 12345678-1234-5678-1234-56789abcdef1，与服务 UUID 仅最后一字节不同。
static const ble_uuid128_t led_characteristic_uuid = BLE_UUID128_INIT(
    0xf1, 0xde, 0xbc, 0x9a, 0x78, 0x56, 0x34, 0x12,
    0x78, 0x56, 0x34, 0x12, 0x78, 0x56, 0x34, 0x12);

// 连接事件回调需要调用后面定义的广播函数，因此提前声明。
static void bluetooth_start_advertising(void);

// NimBLE 收到手机对 LED 特征值的读写请求时调用本函数；返回 0 表示访问成功。
// conn_handle 是连接句柄，attr_handle 是属性句柄，ctxt 包含操作类型和数据缓冲区。
// 此回调运行在蓝牙处理上下文中，应避免长时间阻塞、延时或执行耗时业务。
static int led_characteristic_access(uint16_t conn_handle,
                                     uint16_t attr_handle,
                                     struct ble_gatt_access_ctxt *ctxt,
                                     void *arg)
{
    // 此特征不区分客户端，也不使用额外参数，显式转换以避免未使用参数警告。
    (void)conn_handle;
    (void)attr_handle;
    (void)arg;

    // 手机读取特征值时，返回单字节 LED 状态：0x01 表示开，0x00 表示关。
    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t value = led_is_on() ? 1 : 0;
        // 将响应数据追加到 NimBLE 的 mbuf 缓冲区，由协议栈发送给手机。
        int rc = os_mbuf_append(ctxt->om, &value, sizeof(value));
        // 缓冲区不足时返回 ATT 资源不足错误，不能把失败伪装成读取成功。
        return rc == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }

    // 手机带响应写入特征值时，先验证数据，再修改 LED 状态。
    if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        // value 保存收到的单字节指令，value_len 保存实际拷贝的数据长度。
        uint8_t value;
        uint16_t value_len = 0;

        // mbuf 可能由多个分段组成，使用协议栈接口展开，避免只读取第一段。
        int rc = ble_hs_mbuf_to_flat(ctxt->om, &value, sizeof(value),
                                     &value_len);
        // 只接受恰好一个字节，空数据或超长数据返回 ATT 长度错误，不改变 LED。
        if (rc != 0 || value_len != sizeof(value)) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        // 限定协议取值为 0 和 1，拒绝其他值，避免不同客户端对指令含义理解不一致。
        if (value > 1) {
            return BLE_ATT_ERR_VALUE_NOT_ALLOWED;
        }

        // 校验通过后更新 GPIO 和软件状态，后续读取会返回新的状态。
        led_set(value == 1);
        ESP_LOGI(TAG, "LED %s", value == 1 ? "on" : "off");
        // 返回 0 后，由 NimBLE 完成写响应，Android 可在写完成回调中读取状态。
        return 0;
    }

    // 当前只实现特征值的读和写，其他意外操作以通用 ATT 错误返回。
    return BLE_ATT_ERR_UNLIKELY;
}

// 自定义服务表在程序运行期间保持有效，Host 启动时会据此建立属性数据库。
static const struct ble_gatt_svc_def gatt_services[] = {
    {
        // 将灯控服务声明为主服务，供手机通过服务发现直接找到。
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &led_service_uuid.u,
        // 一个服务可以包含多个特征，本工程只提供一个 LED 状态特征。
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &led_characteristic_uuid.u,
                // 同一个访问回调处理读取状态与写入开关指令。
                .access_cb = led_characteristic_access,
                // READ 允许读取；WRITE 允许带响应写入；当前不提供无响应写入或通知。
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                // 保存分配到的特征值句柄，实际数值由 NimBLE 决定。
                .val_handle = &led_characteristic_handle,
            },
            // 全零项是特征列表结束标记，不是一个可访问的特征。
            {0},
        },
    },
    // 全零项是服务列表结束标记，遍历到此项时停止注册。
    {0},
};

// 在 Host 任务启动前配置 GATT，成功返回 0，失败返回 NimBLE 错误码。
static int bluetooth_gatt_init(void)
{
    // 加入标准 GATT 服务，用于提供属性数据库相关的标准能力。
    ble_svc_gatt_init();

    // 根据自定义服务表统计需要的服务、特征和描述符资源。
    int rc = ble_gatts_count_cfg(gatt_services);
    if (rc != 0) {
        return rc;
    }

    // 将自定义服务加入待注册队列，Host 启动后完成实际注册。
    return ble_gatts_add_svcs(gatt_services);
}

// GAP 回调集中处理连接成功、连接失败和断开连接等事件。
static int bluetooth_gap_event(struct ble_gap_event *event, void *arg)
{
    // 广播启动时未传入用户参数，因此无需使用 arg。
    (void)arg;

    switch (event->type) {
    // CONNECT 既可能表示连接成功，也可能表示连接尝试失败，需检查 status。
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            ESP_LOGI(TAG, "device connected");
        } else {
            ESP_LOGW(TAG, "connection failed, status=%d",
                     event->connect.status);
            // 连接尝试会结束本轮可连接广播，失败后需要重新启动。
            bluetooth_start_advertising();
        }
        break;

    // 断开后保留当前 LED 状态，重新广播以允许手机再次连接。
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "device disconnected, reason=%d",
                 event->disconnect.reason);
        bluetooth_start_advertising();
        break;

    // 本版本无需处理的其他 GAP 事件交由协议栈继续处理。
    default:
        break;
    }

    return 0;
}

// 配置传统 BLE 广播及扫描响应，并进入可连接、可发现状态。
static void bluetooth_start_advertising(void)
{
    // 广播数据、扫描响应数据和广播参数全部清零，未显式配置的字段保持默认值。
    struct ble_hs_adv_fields fields = {0};
    struct ble_hs_adv_fields response_fields = {0};
    struct ble_gap_adv_params params = {0};
    const char *name = ble_svc_gap_device_name();

    // 标记为通用可发现的 BLE 设备，本次广播不支持经典蓝牙 BR/EDR。
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    // 广播中携带完整的 128 位服务 UUID，使 Android 可按 UUID 过滤扫描结果。
    fields.uuids128 = (ble_uuid128_t *)&led_service_uuid;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;

    // 将广播字段交给协议栈编码；传统广播数据最长 31 字节。
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to set advertising data, rc=%d", rc);
        return;
    }

    // 设备名放入单独的扫描响应，避免与服务 UUID 一起放入广播包时超出容量。
    response_fields.name = (uint8_t *)name;
    response_fields.name_len = strlen(name);
    response_fields.name_is_complete = 1;

    // 手机主动扫描请求到来时，控制器会返回这里配置的扫描响应数据。
    rc = ble_gap_adv_rsp_set_fields(&response_fields);
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to set scan response data, rc=%d", rc);
        return;
    }

    // 使用非定向可连接广播，允许附近客户端发起连接，而不是只面向指定地址。
    params.conn_mode = BLE_GAP_CONN_MODE_UND;
    // 通用发现模式用于普通设备扫描，未设置为仅短时间可发现。
    params.disc_mode = BLE_GAP_DISC_MODE_GEN;

    // own_addr_type 指定本机地址类型，NULL 表示无定向目标，FOREVER 表示无固定超时。
    // 注册 GAP 回调以响应连接与断开事件，广播会在建立连接时结束。
    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &params,
                           bluetooth_gap_event, NULL);
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to start advertising, rc=%d", rc);
        return;
    }

    ESP_LOGI(TAG, "advertising started, name=%s", name);
}

// Host 与控制器同步完成后调用，只有此时才开始准备地址和广播。
static void bluetooth_on_sync(void)
{
    // 确保有可用的本机身份地址，参数 0 表示不要求优先使用随机地址。
    int rc = ble_hs_util_ensure_addr(0);
    if (rc != 0) {
        ESP_LOGE(TAG, "no available Bluetooth address, rc=%d", rc);
        return;
    }

    // 根据现有地址配置自动推断地址类型，参数 0 表示不请求隐私地址模式。
    rc = ble_hs_id_infer_auto(0, &own_addr_type);
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to determine address type, rc=%d", rc);
        return;
    }

    bluetooth_start_advertising();
}

// 独立 FreeRTOS 任务持续处理 NimBLE 事件，不占用 app_main 的执行流程。
static void bluetooth_host_task(void *param)
{
    (void)param;
    ESP_LOGI(TAG, "NimBLE host started");

    // 进入阻塞式事件循环，通常仅在调用 nimble_port_stop 后返回。
    nimble_port_run();
    // Host 循环退出后删除当前任务，FreeRTOS 任务函数不能直接返回。
    vTaskDelete(NULL);
}

// 蓝牙启动入口，应在 led_init 成功后调用一次。
esp_err_t bluetooth_init(void)
{
    // 初始化默认 NVS 分区，供蓝牙底层等组件按配置使用；本版 LED 状态不持久化。
    esp_err_t ret = nvs_flash_init();
    if (ret == ESP_ERR_NVS_NO_FREE_PAGES ||
        ret == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        // NVS 空间不足或格式版本不兼容时擦除整个默认 NVS 分区，已有数据会丢失。
        ret = nvs_flash_erase();
        if (ret != ESP_OK) {
            return ret;
        }
        // 擦除成功后重新初始化，建立可使用的 NVS 存储结构。
        ret = nvs_flash_init();
    }
    if (ret != ESP_OK) {
        return ret;
    }

    // 初始化 NimBLE Host 及此 ESP-IDF 配置下的控制器和通信移植层。
    ret = nimble_port_init();
    if (ret != ESP_OK) {
        return ret;
    }

    // 添加标准 GAP 服务，包括设备名等基本属性。
    ble_svc_gap_init();
    // 设置 GAP 设备名，后续扫描响应通过同一名称接口读取此值。
    if (ble_svc_gap_device_name_set(DEVICE_NAME) != 0) {
        ESP_LOGE(TAG, "failed to set device name");
        return ESP_FAIL;
    }

    // 先完成自定义服务配置，再启动 Host，避免连接后找不到 LED 特征。
    int rc = bluetooth_gatt_init();
    if (rc != 0) {
        ESP_LOGE(TAG, "failed to initialize GATT service, rc=%d", rc);
        return ESP_FAIL;
    }

    // 注册异步同步完成回调，不能在协议栈尚未就绪时直接启动广播。
    ble_hs_cfg.sync_cb = bluetooth_on_sync;

    // 创建 Host 任务：ESP-IDF 中栈大小单位是字节，4096 表示 4 KiB，优先级为 5。
    // 两个 NULL 分别表示不传入任务参数、不保存创建后的任务句柄。
    if (xTaskCreate(bluetooth_host_task, "nimble_host", 4096, NULL, 5, NULL) !=
        pdPASS) {
        ESP_LOGE(TAG, "failed to create NimBLE host task");
        return ESP_ERR_NO_MEM;
    }

    // 此时任务已创建，实际广播将在 bluetooth_on_sync 中异步启动。
    return ESP_OK;
}
