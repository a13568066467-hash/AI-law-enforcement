/**
 * NimBLE GATT — AI Field Cam 协议（BLE协议.md）
 */
#include "ble_service.h"

#include "app_fsm.h"
#include "power_mgr.h"

#include "esp_log.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/ble_uuid.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

#include <string.h>

static const char *TAG = "ble_svc";

#define BLE_DEVICE_NAME     "AI-FieldCam"
#define IMAGE_CHUNK_SIZE    512

#define UUID_SVC            0xA001
#define UUID_CMD_WRITE      0xA002
#define UUID_CMD_NOTIFY     0xA003
#define UUID_IMAGE_TX       0xA006
#define UUID_VIDEO_TX       0xA007
#define UUID_SENSOR_NOTIFY  0xA008

static uint16_t s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
static uint16_t s_h_cmd_notify;
static uint16_t s_h_image_tx;
static uint16_t s_h_video_tx;
static uint16_t s_h_sensor;

static int gatt_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                          struct ble_gatt_access_ctxt *ctxt, void *arg);
static int gap_event_cb(struct ble_gap_event *event, void *arg);

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = BLE_UUID16_DECLARE(UUID_SVC),
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = BLE_UUID16_DECLARE(UUID_CMD_WRITE),
                .access_cb = gatt_access_cb,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_WRITE_NO_RSP,
                .arg = (void *)(uintptr_t)UUID_CMD_WRITE,
            },
            {
                .uuid = BLE_UUID16_DECLARE(UUID_CMD_NOTIFY),
                .access_cb = gatt_access_cb,
                .val_handle = &s_h_cmd_notify,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(uintptr_t)UUID_CMD_NOTIFY,
            },
            {
                .uuid = BLE_UUID16_DECLARE(UUID_IMAGE_TX),
                .access_cb = gatt_access_cb,
                .val_handle = &s_h_image_tx,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(uintptr_t)UUID_IMAGE_TX,
            },
            {
                .uuid = BLE_UUID16_DECLARE(UUID_VIDEO_TX),
                .access_cb = gatt_access_cb,
                .val_handle = &s_h_video_tx,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(uintptr_t)UUID_VIDEO_TX,
            },
            {
                .uuid = BLE_UUID16_DECLARE(UUID_SENSOR_NOTIFY),
                .access_cb = gatt_access_cb,
                .val_handle = &s_h_sensor,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(uintptr_t)UUID_SENSOR_NOTIFY,
            },
            { 0 },
        },
    },
    { 0 },
};

static int parse_cmd_write(const uint8_t *buf, size_t len, uint8_t *cmd_out)
{
    if (len < 3 || !cmd_out) {
        return -1;
    }
    uint16_t plen = ((uint16_t)buf[1] << 8) | buf[2];
    if ((size_t)(3 + plen) > len) {
        return -1;
    }
    *cmd_out = buf[0];
    return 0;
}

static int gatt_access_cb(uint16_t conn_handle, uint16_t attr_handle,
                          struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    uint16_t which = (uint16_t)(uintptr_t)arg;

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        return 0;
    }
    if (which != UUID_CMD_WRITE || ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    uint8_t cmd = 0;
    if (parse_cmd_write(ctxt->om->om_data, ctxt->om->om_len, &cmd) != 0) {
        ESP_LOGW(TAG, "bad CMD_WRITE len=%u", (unsigned)ctxt->om->om_len);
        return 0;
    }
    ESP_LOGI(TAG, "CMD 0x%02x", cmd);
    app_fsm_on_ble_cmd(cmd);
    return 0;
}

static int notify_raw(uint16_t val_handle, const void *data, size_t len)
{
    if (s_conn_handle == BLE_HS_CONN_HANDLE_NONE || val_handle == 0) {
        return -1;
    }
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
    if (!om) {
        return -1;
    }
    int rc = ble_gatts_notify_custom(s_conn_handle, val_handle, om);
    if (rc != 0) {
        os_mbuf_free_chain(om);
    }
    return rc;
}

bool ble_service_is_connected(void)
{
    return s_conn_handle != BLE_HS_CONN_HANDLE_NONE;
}

void ble_notify_event(uint8_t evt_id, const uint8_t *payload, size_t payload_len)
{
    uint8_t buf[1 + 16];
    size_t n = 1;
    buf[0] = evt_id;
    if (payload && payload_len > 0) {
        if (payload_len > sizeof(buf) - 1) {
            payload_len = sizeof(buf) - 1;
        }
        memcpy(buf + 1, payload, payload_len);
        n += payload_len;
    }
    if (notify_raw(s_h_cmd_notify, buf, n) == 0) {
        ESP_LOGI(TAG, "notify evt 0x%02x (%u bytes)", evt_id, (unsigned)n);
    }
}

void ble_notify_low_battery(uint8_t percent)
{
    ble_notify_event(0x86, &percent, 1);
}

void ble_notify_sensor_state(fsm_sensor_state_t state, uint8_t battery_percent, uint8_t flags)
{
    uint8_t buf[3] = { battery_percent, (uint8_t)state, flags };
    notify_raw(s_h_sensor, buf, sizeof(buf));
}

void ble_service_publish_sensor_state(void)
{
    ble_notify_sensor_state(app_fsm_get_sensor_state(), power_mgr_get_percent(),
                            power_mgr_get_sensor_flags());
}

void ble_send_jpeg(const uint8_t *data, size_t len)
{
    if (!data || len == 0) {
        return;
    }

    uint8_t done_pl[4] = {
        (uint8_t)((len >> 24) & 0xFF),
        (uint8_t)((len >> 16) & 0xFF),
        (uint8_t)((len >> 8) & 0xFF),
        (uint8_t)(len & 0xFF),
    };
    ble_notify_event(0x83, done_pl, sizeof(done_pl));

    const uint16_t msg_id = 1;
    const uint16_t total = (uint16_t)((len + IMAGE_CHUNK_SIZE - 1) / IMAGE_CHUNK_SIZE);
    uint16_t seq = 0;
    size_t offset = 0;

    while (offset < len) {
        size_t chunk = len - offset;
        if (chunk > IMAGE_CHUNK_SIZE) {
            chunk = IMAGE_CHUNK_SIZE;
        }
        uint8_t hdr[6 + IMAGE_CHUNK_SIZE];
        hdr[0] = (uint8_t)((msg_id >> 8) & 0xFF);
        hdr[1] = (uint8_t)(msg_id & 0xFF);
        hdr[2] = (uint8_t)((seq >> 8) & 0xFF);
        hdr[3] = (uint8_t)(seq & 0xFF);
        hdr[4] = (uint8_t)((total >> 8) & 0xFF);
        hdr[5] = (uint8_t)(total & 0xFF);
        memcpy(hdr + 6, data + offset, chunk);
        notify_raw(s_h_image_tx, hdr, 6 + chunk);
        offset += chunk;
        seq++;
    }
    ESP_LOGI(TAG, "IMAGE_TX %u bytes in %u chunks", (unsigned)len, (unsigned)total);
}

void ble_send_video(const uint8_t *data, size_t len, uint32_t file_id)
{
    if (!data || len == 0) {
        return;
    }

    const uint16_t total = (uint16_t)((len + IMAGE_CHUNK_SIZE - 1) / IMAGE_CHUNK_SIZE);
    uint16_t seq = 0;
    size_t offset = 0;

    while (offset < len) {
        size_t chunk = len - offset;
        if (chunk > IMAGE_CHUNK_SIZE) {
            chunk = IMAGE_CHUNK_SIZE;
        }
        uint8_t hdr[8 + IMAGE_CHUNK_SIZE];
        hdr[0] = (uint8_t)((file_id >> 24) & 0xFF);
        hdr[1] = (uint8_t)((file_id >> 16) & 0xFF);
        hdr[2] = (uint8_t)((file_id >> 8) & 0xFF);
        hdr[3] = (uint8_t)(file_id & 0xFF);
        hdr[4] = (uint8_t)((seq >> 8) & 0xFF);
        hdr[5] = (uint8_t)(seq & 0xFF);
        hdr[6] = (uint8_t)((total >> 8) & 0xFF);
        hdr[7] = (uint8_t)(total & 0xFF);
        memcpy(hdr + 8, data + offset, chunk);
        notify_raw(s_h_video_tx, hdr, 8 + chunk);
        offset += chunk;
        seq++;
    }
    ESP_LOGI(TAG, "VIDEO_TX id=%u %u bytes in %u chunks", (unsigned)file_id, (unsigned)len,
             (unsigned)total);
}

static void start_advertising(void)
{
    struct ble_gap_adv_params adv_params;
    struct ble_hs_adv_fields fields;
    static const ble_uuid16_t adv_uuids16[] = {
        BLE_UUID16_INIT(UUID_SVC),
    };
    memset(&fields, 0, sizeof(fields));

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids16 = adv_uuids16;
    fields.num_uuids16 = 1;
    fields.uuids16_is_complete = 1;
    fields.name = (uint8_t *)BLE_DEVICE_NAME;
    fields.name_len = strlen(BLE_DEVICE_NAME);
    fields.name_is_complete = 1;

    ble_gap_adv_set_fields(&fields);

    memset(&adv_params, 0, sizeof(adv_params));
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;
    ble_gap_adv_start(BLE_OWN_ADDR_PUBLIC, NULL, BLE_HS_FOREVER, &adv_params, gap_event_cb, NULL);
    ESP_LOGI(TAG, "advertising as %s", BLE_DEVICE_NAME);
}

static int gap_event_cb(struct ble_gap_event *event, void *arg)
{
    (void)arg;
    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status == 0) {
            s_conn_handle = event->connect.conn_handle;
            ESP_LOGI(TAG, "connected handle=%d", s_conn_handle);
            ble_service_publish_sensor_state();
        } else {
            start_advertising();
        }
        break;
    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "disconnected reason=%d", event->disconnect.reason);
        s_conn_handle = BLE_HS_CONN_HANDLE_NONE;
        start_advertising();
        break;
    default:
        break;
    }
    return 0;
}

static void on_sync(void)
{
    ble_svc_gap_device_name_set(BLE_DEVICE_NAME);
    int rc = ble_gatts_count_cfg(gatt_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "gatts_count_cfg %d", rc);
        return;
    }
    rc = ble_gatts_add_svcs(gatt_svcs);
    if (rc != 0) {
        ESP_LOGE(TAG, "gatts_add_svcs %d", rc);
        return;
    }
    start_advertising();
}

static void host_task(void *param)
{
    (void)param;
    nimble_port_run();
    nimble_port_freertos_deinit();
}

void ble_service_init(void)
{
    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "nimble_port_init %s", esp_err_to_name(err));
        return;
    }

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.gatts_register_cb = NULL;
    ble_hs_cfg.sm_io_cap = BLE_SM_IO_CAP_NO_IO;
    ble_hs_cfg.sm_bonding = 0;

    ble_svc_gap_init();
    ble_svc_gatt_init();

    nimble_port_freertos_init(host_task);
    ESP_LOGI(TAG, "NimBLE ready service 0x%04x", UUID_SVC);
}
