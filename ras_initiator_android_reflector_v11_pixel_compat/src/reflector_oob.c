/*
 * Android Ranging OOB transport for the BleCsReflector application.
 *
 * SPDX-License-Identifier: LicenseRef-Nordic-5-Clause
 */

#include "reflector_oob.h"

#include <errno.h>
#include <string.h>

#include <bluetooth/gatt_dm.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>
#include <zephyr/sys/byteorder.h>

LOG_MODULE_REGISTER(reflector_oob, LOG_LEVEL_INF);

#define OOB_VERSION_V1                 0x01
#define OOB_MSG_CAPABILITY_REQUEST     0x00
#define OOB_MSG_CAPABILITY_RESPONSE    0x01
#define OOB_MSG_CONFIGURATION          0x02
#define OOB_MSG_CONFIGURATION_RESPONSE 0x03
#define OOB_TECHNOLOGY_BLE_CS          0x01
#define OOB_TECHNOLOGY_BIT_BLE_CS      BIT(1)
#define OOB_BLE_CS_BLOCK_SIZE          9
#define OOB_TIMEOUT                    K_SECONDS(10)

static struct bt_uuid_128 service_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x8e400001, 0xf315, 0x4f60, 0x9fb8, 0x838830daea50));
static struct bt_uuid_128 rx_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x8e400002, 0xf315, 0x4f60, 0x9fb8, 0x838830daea50));
static struct bt_uuid_128 tx_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x8e400003, 0xf315, 0x4f60, 0x9fb8, 0x838830daea50));

static K_SEM_DEFINE(discovery_sem, 0, 1);
static K_SEM_DEFINE(subscribe_sem, 0, 1);
static K_SEM_DEFINE(write_sem, 0, 1);
static K_SEM_DEFINE(capability_sem, 0, 1);

static struct bt_gatt_subscribe_params subscribe_params;
static struct bt_gatt_write_params write_params;
static uint8_t write_buffer[20];

static uint16_t rx_handle;
static uint16_t tx_handle;
static uint16_t tx_ccc_handle;
static int discovery_result;
static int subscribe_result;
static int write_result;
static int capability_result;
static uint8_t selected_security_level;

const struct bt_uuid *reflector_oob_service_uuid(void)
{
	return &service_uuid.uuid;
}

static int assign_handles(struct bt_gatt_dm *dm)
{
	const struct bt_gatt_dm_attr *service_attr = bt_gatt_dm_service_get(dm);
	const struct bt_gatt_service_val *service = bt_gatt_dm_attr_service_val(service_attr);
	const struct bt_gatt_dm_attr *characteristic;
	const struct bt_gatt_dm_attr *descriptor;

	if (bt_uuid_cmp(service->uuid, &service_uuid.uuid) != 0) {
		return -ENOTSUP;
	}

	characteristic = bt_gatt_dm_char_by_uuid(dm, &rx_uuid.uuid);
	if (characteristic == NULL) {
		LOG_ERR("Reflector RX characteristic is missing");
		return -ENOENT;
	}
	descriptor = bt_gatt_dm_desc_by_uuid(dm, characteristic, &rx_uuid.uuid);
	if (descriptor == NULL) {
		LOG_ERR("Reflector RX value handle is missing");
		return -ENOENT;
	}
	rx_handle = descriptor->handle;

	characteristic = bt_gatt_dm_char_by_uuid(dm, &tx_uuid.uuid);
	if (characteristic == NULL) {
		LOG_ERR("Reflector TX characteristic is missing");
		return -ENOENT;
	}
	descriptor = bt_gatt_dm_desc_by_uuid(dm, characteristic, &tx_uuid.uuid);
	if (descriptor == NULL) {
		LOG_ERR("Reflector TX value handle is missing");
		return -ENOENT;
	}
	tx_handle = descriptor->handle;

	descriptor = bt_gatt_dm_desc_by_uuid(dm, characteristic, BT_UUID_GATT_CCC);
	if (descriptor == NULL) {
		LOG_ERR("Reflector TX CCCD is missing");
		return -ENOENT;
	}
	tx_ccc_handle = descriptor->handle;

	return 0;
}

static void discovery_completed(struct bt_gatt_dm *dm, void *context)
{
	ARG_UNUSED(context);

	discovery_result = assign_handles(dm);
	int release_err = bt_gatt_dm_data_release(dm);

	if (release_err != 0 && discovery_result == 0) {
		discovery_result = release_err;
	}

	k_sem_give(&discovery_sem);
}

static void discovery_not_found(struct bt_conn *conn, void *context)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(context);

	discovery_result = -ENOENT;
	k_sem_give(&discovery_sem);
}

static void discovery_error(struct bt_conn *conn, int err, void *context)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(context);

	discovery_result = err != 0 ? err : -EIO;
	k_sem_give(&discovery_sem);
}

static struct bt_gatt_dm_cb discovery_callbacks = {
	.completed = discovery_completed,
	.service_not_found = discovery_not_found,
	.error_found = discovery_error,
};

static uint8_t select_security_level(uint8_t supported_levels)
{
	/* Capability bits map directly to level numbers: bit 4 -> level 4, etc. */
	for (int level = 4; level >= 0; level--) {
		if ((supported_levels & BIT(level)) != 0U) {
			return (uint8_t)level;
		}
	}

	return UINT8_MAX;
}

static int parse_capability_response(const uint8_t *data, uint16_t length)
{
	if (length < 4 || data[0] != OOB_VERSION_V1 || data[1] != OOB_MSG_CAPABILITY_RESPONSE) {
		return -EBADMSG;
	}

	if ((sys_get_le16(&data[2]) & OOB_TECHNOLOGY_BIT_BLE_CS) == 0U) {
		LOG_ERR("Android responder did not report BLE CS support");
		return -ENOTSUP;
	}

	for (uint16_t offset = 4; offset + 2 <= length;) {
		uint8_t technology = data[offset];
		uint8_t block_size = data[offset + 1];

		if (block_size < 2 || offset + block_size > length) {
			return -EBADMSG;
		}

		if (technology == OOB_TECHNOLOGY_BLE_CS) {
			if (block_size < OOB_BLE_CS_BLOCK_SIZE) {
				return -EBADMSG;
			}

			selected_security_level = select_security_level(data[offset + 2]);
			if (selected_security_level == UINT8_MAX) {
				LOG_ERR("Android responder reported no usable CS security level");
				return -ENOTSUP;
			}

			LOG_INF("OOB capability response: CS security level %u selected",
				selected_security_level);
			LOG_HEXDUMP_INF(&data[offset + 3], 6,
					"Android responder address (big-endian)");
			return 0;
		}

		offset += block_size;
	}

	return -ENOENT;
}

static uint8_t indication_received(struct bt_conn *conn, struct bt_gatt_subscribe_params *params,
				   const void *data, uint16_t length)
{
	ARG_UNUSED(conn);

	if (data == NULL) {
		LOG_WRN("Reflector OOB indication subscription removed");
		params->value_handle = 0;
		return BT_GATT_ITER_STOP;
	}

	LOG_HEXDUMP_INF(data, length, "RX OOB indication");

	const uint8_t *frame = data;
	if (length >= 2 && frame[0] == OOB_VERSION_V1 && frame[1] == OOB_MSG_CAPABILITY_RESPONSE) {
		capability_result = parse_capability_response(frame, length);
		k_sem_give(&capability_sem);
	} else if (length >= 2 && frame[0] == OOB_VERSION_V1 &&
		   frame[1] == OOB_MSG_CONFIGURATION_RESPONSE) {
		LOG_INF("Optional OOB configuration response received");
	}

	return BT_GATT_ITER_CONTINUE;
}

static void subscribed(struct bt_conn *conn, uint8_t err, struct bt_gatt_subscribe_params *params)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);

	subscribe_result = err == 0 ? 0 : -EACCES;
	if (err != 0) {
		LOG_ERR("TX indication CCCD write failed (ATT err 0x%02x)", err);
	}
	k_sem_give(&subscribe_sem);
}

static void write_completed(struct bt_conn *conn, uint8_t err, struct bt_gatt_write_params *params)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(params);

	write_result = err == 0 ? 0 : -EIO;
	if (err != 0) {
		LOG_ERR("RX OOB write failed (ATT err 0x%02x)", err);
	}
	k_sem_give(&write_sem);
}

static int wait_for(struct k_sem *sem, k_timeout_t timeout, const char *operation)
{
	int err = k_sem_take(sem, timeout);

	if (err != 0) {
		LOG_ERR("Timed out waiting for %s", operation);
		return -ETIMEDOUT;
	}

	return 0;
}

static int write_frame(struct bt_conn *conn, const uint8_t *data, size_t length)
{
	if (length > sizeof(write_buffer)) {
		return -EMSGSIZE;
	}

	memcpy(write_buffer, data, length);
	k_sem_reset(&write_sem);
	write_result = -EINPROGRESS;

	write_params.func = write_completed;
	write_params.handle = rx_handle;
	write_params.offset = 0;
	write_params.data = write_buffer;
	write_params.length = length;

	LOG_HEXDUMP_INF(write_buffer, length, "TX OOB write");
	int err = bt_gatt_write(conn, &write_params);
	if (err != 0) {
		return err;
	}

	err = wait_for(&write_sem, OOB_TIMEOUT, "OOB write response");
	return err != 0 ? err : write_result;
}

static int subscribe_to_indications(struct bt_conn *conn)
{
	memset(&subscribe_params, 0, sizeof(subscribe_params));
	k_sem_reset(&subscribe_sem);
	subscribe_result = -EINPROGRESS;

	subscribe_params.notify = indication_received;
	subscribe_params.subscribe = subscribed;
	subscribe_params.value_handle = tx_handle;
	subscribe_params.ccc_handle = tx_ccc_handle;
	subscribe_params.value = BT_GATT_CCC_INDICATE;
	subscribe_params.min_security = BT_SECURITY_L2;
	atomic_set_bit(subscribe_params.flags, BT_GATT_SUBSCRIBE_FLAG_VOLATILE);

	int err = bt_gatt_subscribe(conn, &subscribe_params);
	if (err != 0) {
		return err;
	}

	err = wait_for(&subscribe_sem, OOB_TIMEOUT, "TX indication subscription");
	return err != 0 ? err : subscribe_result;
}

static int build_configuration(struct bt_conn *conn, uint8_t frame[15])
{
	struct bt_conn_info info;
	int err = bt_conn_get_info(conn, &info);

	if (err != 0 || info.type != BT_CONN_TYPE_LE || info.le.src == NULL) {
		return err != 0 ? err : -EINVAL;
	}

	frame[0] = OOB_VERSION_V1;
	frame[1] = OOB_MSG_CONFIGURATION;
	sys_put_le16(OOB_TECHNOLOGY_BIT_BLE_CS, &frame[2]);
	/* OOB v1 requires this RFU field to repeat the selected technology set. */
	sys_put_le16(OOB_TECHNOLOGY_BIT_BLE_CS, &frame[4]);
	frame[6] = OOB_TECHNOLOGY_BLE_CS;
	frame[7] = OOB_BLE_CS_BLOCK_SIZE;
	frame[8] = selected_security_level;

	/*
	 * The configuration carries the initiator address, because Android turns
	 * it into BleCsRangingParams.peerBluetoothAddress. Zephyr stores Bluetooth
	 * addresses least-significant octet first; OOB v1 requires big-endian.
	 */
	for (size_t i = 0; i < 6; i++) {
		frame[9 + i] = info.le.src->a.val[5 - i];
	}

	LOG_HEXDUMP_INF(&frame[9], 6, "Nordic initiator address (big-endian)");
	return 0;
}

int reflector_oob_setup(struct bt_conn *conn)
{
	if (conn == NULL) {
		return -EINVAL;
	}

	rx_handle = 0;
	tx_handle = 0;
	tx_ccc_handle = 0;
	discovery_result = -EINPROGRESS;
	k_sem_reset(&discovery_sem);

	int err = bt_gatt_dm_start(conn, &service_uuid.uuid, &discovery_callbacks, NULL);
	if (err != 0) {
		return err;
	}

	err = wait_for(&discovery_sem, OOB_TIMEOUT, "reflector service discovery");
	if (err != 0) {
		return err;
	}
	if (discovery_result != 0) {
		return discovery_result;
	}

	LOG_INF("Reflector OOB service discovered");
	err = subscribe_to_indications(conn);
	if (err != 0) {
		return err;
	}
	LOG_INF("Reflector OOB indications enabled");

	static const uint8_t capability_request[] = {
		OOB_VERSION_V1,
		OOB_MSG_CAPABILITY_REQUEST,
		OOB_TECHNOLOGY_BIT_BLE_CS,
		0x00,
	};

	selected_security_level = UINT8_MAX;
	capability_result = -EINPROGRESS;
	k_sem_reset(&capability_sem);

	err = write_frame(conn, capability_request, sizeof(capability_request));
	if (err != 0) {
		return err;
	}

	err = wait_for(&capability_sem, OOB_TIMEOUT, "OOB capability response");
	if (err != 0) {
		return err;
	}
	if (capability_result != 0) {
		return capability_result;
	}

	uint8_t configuration[15];
	err = build_configuration(conn, configuration);
	if (err != 0) {
		return err;
	}

	err = write_frame(conn, configuration, sizeof(configuration));
	if (err != 0) {
		return err;
	}

	LOG_INF("Android Ranging OOB negotiation complete");
	return 0;
}
