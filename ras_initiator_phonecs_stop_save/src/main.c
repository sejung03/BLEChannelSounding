/*
 * Copyright (c) 2024 Nordic Semiconductor ASA
 *
 * SPDX-License-Identifier: LicenseRef-Nordic-5-Clause
 */

/** @file
 *  @brief Channel Sounding Initiator with Ranging Requestor sample
 */

#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/uart.h>
#include <zephyr/kernel.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/printk.h>
#include <zephyr/types.h>
#include <zephyr/sys/byteorder.h>
#include <zephyr/sys/reboot.h>
#include <zephyr/bluetooth/cs.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/conn.h>
#include <bluetooth/scan.h>
#include <bluetooth/services/ras.h>
#include <bluetooth/gatt_dm.h>
#include <bluetooth/cs_de.h>

#include <dk_buttons_and_leds.h>

#include <zephyr/logging/log.h>
#include "reflector_oob.h"
LOG_MODULE_REGISTER(app_main, LOG_LEVEL_INF);

#define CON_STATUS_LED DK_LED1

/*
 * Android's CS stack can allocate configuration ID 0 for its own ranging
 * tracker before a remote initiator starts the LL configuration procedure.
 * Use a different ID to avoid a requester/responder tracker collision on
 * Android versions that predate the multi-tracker config-ID fix.
 */
#define CS_CONFIG_ID 1

#if defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_2_SUB_MODE_1)
#define CS_CONFIG_MODE BT_CONN_LE_CS_MAIN_MODE_2_SUB_MODE_1
#elif defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_2)
#define CS_CONFIG_MODE BT_CONN_LE_CS_MAIN_MODE_2_NO_SUB_MODE
#elif defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_1)
#define CS_CONFIG_MODE BT_CONN_LE_CS_MAIN_MODE_1_NO_SUB_MODE
#elif defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_3)
#define CS_CONFIG_MODE BT_CONN_LE_CS_MAIN_MODE_3_NO_SUB_MODE
#else
BUILD_ASSERT(false, "Invalid ranging mode");
#endif

#define NUM_MODE_0_STEPS       3
#define PROCEDURE_COUNTER_NONE (-1)
#define DE_SLIDING_WINDOW_SIZE (9)
#define MAX_AP                 (CONFIG_BT_RAS_MAX_ANTENNA_PATHS)

/*
 * Pixel reflector interoperability profile.
 *
 * This 37-channel, 2 MHz-spaced map is the "Medium" preset used by the
 * Pixel-compatible initiator profile. The remaining procedure values are
 * defined beside the HCI procedure parameter structure below.
 */
static const uint8_t pixel_medium_channel_map[10] = {
	0x54, 0x55, 0x55, 0x54, 0x55, 0x55, 0x55, 0x55, 0x55, 0x15,
};

#define LOCAL_PROCEDURE_MEM                                                                        \
	((BT_RAS_MAX_STEPS_PER_PROCEDURE * sizeof(struct bt_le_cs_subevent_step)) +                \
	 (BT_RAS_MAX_STEPS_PER_PROCEDURE * BT_RAS_MAX_STEP_DATA_LEN))

#define CHANNEL_INDEX_OFFSET                    (2)
#define TONE_QI_OK_TONE_COUNT_THRESHOLD         (15)
#define HCI_ERR_DIFFERENT_TRANSACTION_COLLISION 0x2A
#define CS_PROCEDURE_ENABLE_RETRY_COUNT         8
#define CS_PROCEDURE_ENABLE_INITIAL_DELAY       K_SECONDS(2)
#define CS_PROCEDURE_ENABLE_RETRY_DELAY         K_SECONDS(1)
#define UART_COMMAND_BUFFER_SIZE                 16

static const struct device *const console_uart = DEVICE_DT_GET(DT_CHOSEN(zephyr_console));

static K_SEM_DEFINE(sem_remote_capabilities_obtained, 0, 1);
static K_SEM_DEFINE(sem_remote_fae_table_obtained, 0, 1);
static K_SEM_DEFINE(sem_config_created, 0, 1);
static K_SEM_DEFINE(sem_cs_security_enabled, 0, 1);
static K_SEM_DEFINE(sem_connected, 0, 1);
static K_SEM_DEFINE(sem_discovery_done, 0, 1);
static K_SEM_DEFINE(sem_mtu_exchange_done, 0, 1);
static K_SEM_DEFINE(sem_security, 0, 1);
static K_SEM_DEFINE(sem_ras_features, 0, 1);
static K_SEM_DEFINE(sem_local_steps, 1, 1);
static K_SEM_DEFINE(sem_distance_estimate_updated, 0, 1);
static K_SEM_DEFINE(sem_procedure_enable_complete, 0, 1);

#define RAS_DISCOVERY_RETRY_COUNT 10
#define RAS_DISCOVERY_RETRY_DELAY K_MSEC(500)

static int ras_discovery_result;
static uint8_t procedure_enable_status;
static uint8_t remote_fae_table_status;
static struct bt_conn_le_cs_capabilities remote_cs_capabilities;
static bool remote_cs_capabilities_valid;

static K_MUTEX_DEFINE(distance_estimate_buffer_mutex);

static struct bt_conn *connection;
NET_BUF_SIMPLE_DEFINE_STATIC(latest_local_steps, LOCAL_PROCEDURE_MEM);
NET_BUF_SIMPLE_DEFINE_STATIC(latest_peer_steps, BT_RAS_PROCEDURE_MEM);
static int32_t most_recent_local_ranging_counter = PROCEDURE_COUNTER_NONE;
static int32_t dropped_ranging_counter = PROCEDURE_COUNTER_NONE;
static uint32_t ras_feature_bits;

struct distance_estimate_buffer {
	cs_de_dist_estimates_t estimates[DE_SLIDING_WINDOW_SIZE];
	uint8_t num_valid;
	uint8_t index;
};

static struct distance_estimate_buffer distance_estimate_buffers[MAX_AP];
static cs_de_dist_estimates_t latest_raw_distance_estimates[MAX_AP];

static struct bt_conn_le_cs_config cs_config;

static uint16_t m_n_iqs[CONFIG_BT_RAS_MAX_ANTENNA_PATHS][CS_DE_NUM_CHANNELS];
static cs_de_report_t m_cs_de_report;

static void store_distance_estimates_in_buffer(cs_de_dist_estimates_t *p_estimates,
					       uint8_t ap)
{
	struct distance_estimate_buffer *buffer = &distance_estimate_buffers[ap];
	int lock_state = k_mutex_lock(&distance_estimate_buffer_mutex, K_FOREVER);

	__ASSERT_NO_MSG(lock_state == 0);

	memcpy(&buffer->estimates[buffer->index], p_estimates, sizeof(cs_de_dist_estimates_t));
	memcpy(&latest_raw_distance_estimates[ap], p_estimates,
	       sizeof(cs_de_dist_estimates_t));

	buffer->index = (buffer->index + 1) % DE_SLIDING_WINDOW_SIZE;

	if (buffer->num_valid < DE_SLIDING_WINDOW_SIZE) {
		buffer->num_valid++;
	}

	k_mutex_unlock(&distance_estimate_buffer_mutex);
}

static cs_de_dist_estimates_t get_latest_raw_distance(uint8_t ap)
{
	cs_de_dist_estimates_t result;
	int lock_state = k_mutex_lock(&distance_estimate_buffer_mutex, K_FOREVER);

	__ASSERT_NO_MSG(lock_state == 0);

	memcpy(&result, &latest_raw_distance_estimates[ap], sizeof(result));

	k_mutex_unlock(&distance_estimate_buffer_mutex);

	return result;
}

static float get_best_distance(const cs_de_dist_estimates_t *estimates)
{
	if (isfinite(estimates->ifft)) {
		return estimates->ifft;
	}
	if (isfinite(estimates->phase_slope)) {
		return estimates->phase_slope;
	}
	if (isfinite(estimates->rtt)) {
		return estimates->rtt;
	}

	return NAN;
}

static int float_cmp(const void *a, const void *b)
{
	float fa = *(const float *)a;
	float fb = *(const float *)b;

	return (fa > fb) - (fa < fb);
}

static float median_inplace(int count, float *values)
{
	if (count == 0) {
		return NAN;
	}

	qsort(values, count, sizeof(float), float_cmp);

	if (count % 2 == 0) {
		return (values[count / 2] + values[count / 2 - 1]) / 2;
	} else {
		return values[count / 2];
	}
}

static cs_de_dist_estimates_t get_distance(uint8_t ap)
{
	cs_de_dist_estimates_t averaged_result = {};
	uint8_t num_ifft = 0;
	uint8_t num_phase_slope = 0;
	uint8_t num_rtt = 0;

	static float temp_ifft[DE_SLIDING_WINDOW_SIZE];
	static float temp_phase_slope[DE_SLIDING_WINDOW_SIZE];
	static float temp_rtt[DE_SLIDING_WINDOW_SIZE];

	struct distance_estimate_buffer *buffer = &distance_estimate_buffers[ap];

	int lock_state = k_mutex_lock(&distance_estimate_buffer_mutex, K_FOREVER);

	__ASSERT_NO_MSG(lock_state == 0);

	for (uint8_t i = 0; i < buffer->num_valid; i++) {
		if (isfinite(buffer->estimates[i].ifft)) {
			temp_ifft[num_ifft] = buffer->estimates[i].ifft;
			num_ifft++;
		}
		if (isfinite(buffer->estimates[i].phase_slope)) {
			temp_phase_slope[num_phase_slope] = buffer->estimates[i].phase_slope;
			num_phase_slope++;
		}
		if (isfinite(buffer->estimates[i].rtt)) {
			temp_rtt[num_rtt] = buffer->estimates[i].rtt;
			num_rtt++;
		}
	}

	k_mutex_unlock(&distance_estimate_buffer_mutex);

	averaged_result.ifft = median_inplace(num_ifft, temp_ifft);
	averaged_result.phase_slope = median_inplace(num_phase_slope, temp_phase_slope);
	averaged_result.rtt = median_inplace(num_rtt, temp_rtt);

	return averaged_result;
}

static bool m_is_tone_quality_ok(uint16_t num_iqs[CS_DE_NUM_CHANNELS], uint8_t channel_map[10])
{
	uint8_t ok_tones_count = 0;

	for (uint8_t i = 0; i < CS_DE_NUM_CHANNELS; ++i) {
		if (BT_LE_CS_CHANNEL_BIT_GET(channel_map, i + CHANNEL_INDEX_OFFSET) &&
		    num_iqs[i] >= 1) {
			ok_tones_count += 1;
		}
	}
	return (ok_tones_count >= TONE_QI_OK_TONE_COUNT_THRESHOLD);
}

static void cumulate_mean(float *avg, float new_value, uint16_t *N)
{
	float a = 1.0f / (*N);
	float b = 1.0f - a;
	*avg = a * new_value + b * (*avg);
}

static void extract_pcts(cs_de_report_t *p_report, uint8_t channel_index,
			 uint8_t antenna_permutation_index,
			 struct bt_hci_le_cs_step_data_tone_info *local_tone_info,
			 struct bt_hci_le_cs_step_data_tone_info *remote_tone_info)
{

	for (uint8_t tone_index = 0; tone_index < p_report->n_ap; tone_index++) {
		int antenna_path = bt_le_cs_get_antenna_path(p_report->n_ap,
							     antenna_permutation_index, tone_index);
		if (antenna_path < 0) {
			LOG_WRN("Invalid antenna path.");
			return;
		}

		if (local_tone_info[tone_index].quality_indicator !=
			    BT_HCI_LE_CS_TONE_QUALITY_HIGH ||
		    remote_tone_info[tone_index].quality_indicator !=
			    BT_HCI_LE_CS_TONE_QUALITY_HIGH) {
			return;
		}

		struct bt_le_cs_iq_sample local_iq =
			bt_le_cs_parse_pct(local_tone_info[tone_index].phase_correction_term);
		struct bt_le_cs_iq_sample remote_iq =
			bt_le_cs_parse_pct(remote_tone_info[tone_index].phase_correction_term);

		m_n_iqs[antenna_path][channel_index]++;

		if (m_n_iqs[antenna_path][channel_index] == 1) {
			p_report->iq_tones[antenna_path].i_local[channel_index] = local_iq.i;
			p_report->iq_tones[antenna_path].q_local[channel_index] = local_iq.q;
			p_report->iq_tones[antenna_path].i_remote[channel_index] = remote_iq.i;
			p_report->iq_tones[antenna_path].q_remote[channel_index] = remote_iq.q;
		} else {
			cumulate_mean(&p_report->iq_tones[antenna_path].i_local[channel_index],
				      local_iq.i, &m_n_iqs[antenna_path][channel_index]);
			cumulate_mean(&p_report->iq_tones[antenna_path].q_local[channel_index],
				      local_iq.q, &m_n_iqs[antenna_path][channel_index]);
			cumulate_mean(&p_report->iq_tones[antenna_path].i_remote[channel_index],
				      remote_iq.i, &m_n_iqs[antenna_path][channel_index]);
			cumulate_mean(&p_report->iq_tones[antenna_path].q_remote[channel_index],
				      remote_iq.q, &m_n_iqs[antenna_path][channel_index]);
		}
	}
}

static void extract_rtt_timings(cs_de_report_t *p_report,
				struct bt_hci_le_cs_step_data_mode_1 *local_rtt_data,
				struct bt_hci_le_cs_step_data_mode_1 *peer_rtt_data)
{
	if (local_rtt_data->packet_quality_aa_check !=
		    BT_HCI_LE_CS_PACKET_QUALITY_AA_CHECK_SUCCESSFUL ||
	    local_rtt_data->packet_rssi == BT_HCI_LE_CS_PACKET_RSSI_NOT_AVAILABLE ||
	    local_rtt_data->tod_toa_reflector == BT_HCI_LE_CS_TIME_DIFFERENCE_NOT_AVAILABLE ||
	    peer_rtt_data->packet_quality_aa_check !=
		    BT_HCI_LE_CS_PACKET_QUALITY_AA_CHECK_SUCCESSFUL ||
	    peer_rtt_data->packet_rssi == BT_HCI_LE_CS_PACKET_RSSI_NOT_AVAILABLE ||
	    peer_rtt_data->tod_toa_reflector == BT_HCI_LE_CS_TIME_DIFFERENCE_NOT_AVAILABLE) {
		return;
	}

	if (p_report->role == BT_CONN_LE_CS_ROLE_INITIATOR) {
		p_report->rtt_accumulated_half_ns +=
			local_rtt_data->toa_tod_initiator - peer_rtt_data->tod_toa_reflector;
	} else {
		p_report->rtt_accumulated_half_ns +=
			peer_rtt_data->toa_tod_initiator - local_rtt_data->tod_toa_reflector;
	}

	p_report->rtt_count++;
}

static bool process_ranging_header(struct ras_ranging_header *ranging_header, void *user_data)
{
	cs_de_report_t *p_report = (cs_de_report_t *)user_data;

	p_report->n_ap = MAX(1, ((ranging_header->antenna_paths_mask & BIT(0)) +
				 ((ranging_header->antenna_paths_mask & BIT(1)) >> 1) +
				 ((ranging_header->antenna_paths_mask & BIT(2)) >> 2) +
				 ((ranging_header->antenna_paths_mask & BIT(3)) >> 3)));
	return true;
}

static bool process_step_data(struct bt_le_cs_subevent_step *local_step,
			      struct bt_le_cs_subevent_step *peer_step, void *user_data)
{
	cs_de_report_t *p_report = (cs_de_report_t *)user_data;

	if (local_step->mode == BT_HCI_OP_LE_CS_MAIN_MODE_2) {
		struct bt_hci_le_cs_step_data_mode_2 *local_step_data =
			(struct bt_hci_le_cs_step_data_mode_2 *)local_step->data;
		struct bt_hci_le_cs_step_data_mode_2 *peer_step_data =
			(struct bt_hci_le_cs_step_data_mode_2 *)peer_step->data;

		extract_pcts(p_report, local_step->channel - CHANNEL_INDEX_OFFSET,
			     local_step_data->antenna_permutation_index, local_step_data->tone_info,
			     peer_step_data->tone_info);
	} else if (local_step->mode == BT_HCI_OP_LE_CS_MAIN_MODE_1) {
		struct bt_hci_le_cs_step_data_mode_1 *local_step_data =
			(struct bt_hci_le_cs_step_data_mode_1 *)local_step->data;
		struct bt_hci_le_cs_step_data_mode_1 *peer_step_data =
			(struct bt_hci_le_cs_step_data_mode_1 *)peer_step->data;

		extract_rtt_timings(p_report, local_step_data, peer_step_data);
	} else if (local_step->mode == BT_HCI_OP_LE_CS_MAIN_MODE_3) {
		struct bt_hci_le_cs_step_data_mode_3 *local_step_data =
			(struct bt_hci_le_cs_step_data_mode_3 *)local_step->data;
		struct bt_hci_le_cs_step_data_mode_3 *peer_step_data =
			(struct bt_hci_le_cs_step_data_mode_3 *)peer_step->data;

		extract_pcts(p_report, local_step->channel - CHANNEL_INDEX_OFFSET,
			     local_step_data->antenna_permutation_index, local_step_data->tone_info,
			     peer_step_data->tone_info);

		extract_rtt_timings(p_report,
				    (struct bt_hci_le_cs_step_data_mode_1 *)local_step_data,
				    (struct bt_hci_le_cs_step_data_mode_1 *)peer_step_data);
	}

	return true;
}

static void ranging_data_cb(struct bt_conn *conn, uint16_t ranging_counter, int err)
{
	ARG_UNUSED(conn);

	if (err) {
		LOG_ERR("Error when receiving ranging data with ranging counter %d (err %d)",
			ranging_counter, err);
		return;
	}

	if (ranging_counter != most_recent_local_ranging_counter) {
		LOG_INF("Ranging data dropped as peer ranging counter doesn't match local ranging "
			"data counter. (peer: %u, local: %u)",
			ranging_counter, most_recent_local_ranging_counter);
		net_buf_simple_reset(&latest_local_steps);
		k_sem_give(&sem_local_steps);
		return;
	}

	LOG_DBG("Ranging data received for ranging counter %d", ranging_counter);

	if (latest_local_steps.len == 0) {
		LOG_WRN("All subevents in ranging counter %u were aborted",
			most_recent_local_ranging_counter);
		net_buf_simple_reset(&latest_local_steps);
		k_sem_give(&sem_local_steps);

		if (!(ras_feature_bits & RAS_FEAT_REALTIME_RD)) {
			net_buf_simple_reset(&latest_peer_steps);
		}
		return;
	}

	memset(&m_cs_de_report, 0x0, sizeof(cs_de_report_t));
	memset(m_n_iqs, 0, sizeof(m_n_iqs));

	bt_ras_rreq_rd_subevent_data_parse(&latest_peer_steps, &latest_local_steps, cs_config.role,
					   process_ranging_header, NULL, process_step_data,
					   &m_cs_de_report);

	for (uint8_t ap = 0; ap < m_cs_de_report.n_ap; ap++) {
		m_cs_de_report.distance_estimates[ap].ifft = NAN;
		m_cs_de_report.distance_estimates[ap].phase_slope = NAN;
		m_cs_de_report.distance_estimates[ap].rtt = NAN;
		m_cs_de_report.distance_estimates[ap].best = NAN;

		if (m_is_tone_quality_ok(m_n_iqs[ap], cs_config.channel_map)) {
			m_cs_de_report.tone_quality[ap] = CS_DE_TONE_QUALITY_OK;
		} else {
			m_cs_de_report.tone_quality[ap] = CS_DE_TONE_QUALITY_BAD;
		}
	}

	net_buf_simple_reset(&latest_local_steps);

	if (!(ras_feature_bits & RAS_FEAT_REALTIME_RD)) {
		net_buf_simple_reset(&latest_peer_steps);
	}

	k_sem_give(&sem_local_steps);

	cs_de_quality_t quality = cs_de_calc(&m_cs_de_report);

	if (quality == CS_DE_QUALITY_OK) {
		for (uint8_t ap = 0; ap < m_cs_de_report.n_ap; ap++) {
			if (m_cs_de_report.tone_quality[ap] == CS_DE_TONE_QUALITY_OK ||
			    isfinite(m_cs_de_report.distance_estimates[ap].rtt)) {
				store_distance_estimates_in_buffer(
					&m_cs_de_report.distance_estimates[ap], ap);
			}
		}
		k_sem_give(&sem_distance_estimate_updated);
	}
}

static void subevent_result_cb(struct bt_conn *conn, struct bt_conn_le_cs_subevent_result *result)
{
	if (dropped_ranging_counter == result->header.procedure_counter) {
		return;
	}

	if (most_recent_local_ranging_counter !=
	    bt_ras_rreq_get_ranging_counter(result->header.procedure_counter)) {
		int sem_state = k_sem_take(&sem_local_steps, K_NO_WAIT);

		if (sem_state < 0) {
			dropped_ranging_counter = result->header.procedure_counter;
			LOG_INF("Dropped subevent results. Waiting for ranging data from peer.");
			return;
		}

		most_recent_local_ranging_counter =
			bt_ras_rreq_get_ranging_counter(result->header.procedure_counter);
	}

	if (result->header.subevent_done_status == BT_CONN_LE_CS_SUBEVENT_ABORTED) {
		/* The steps from this subevent will not be used. */
	} else if (result->step_data_buf) {
		if (result->step_data_buf->len <= net_buf_simple_tailroom(&latest_local_steps)) {
			uint16_t len = result->step_data_buf->len;
			uint8_t *step_data = net_buf_simple_pull_mem(result->step_data_buf, len);

			net_buf_simple_add_mem(&latest_local_steps, step_data, len);
		} else {
			LOG_ERR("Not enough memory to store step data. (%d > %d)",
				latest_local_steps.len + result->step_data_buf->len,
				latest_local_steps.size);
			net_buf_simple_reset(&latest_local_steps);
			dropped_ranging_counter = result->header.procedure_counter;
			return;
		}
	}

	dropped_ranging_counter = PROCEDURE_COUNTER_NONE;

	if (result->header.procedure_done_status == BT_CONN_LE_CS_PROCEDURE_COMPLETE) {
		most_recent_local_ranging_counter =
			bt_ras_rreq_get_ranging_counter(result->header.procedure_counter);
	} else if (result->header.procedure_done_status == BT_CONN_LE_CS_PROCEDURE_ABORTED) {
		LOG_WRN("Procedure %u aborted", result->header.procedure_counter);
		net_buf_simple_reset(&latest_local_steps);
		k_sem_give(&sem_local_steps);
	}
}

static void ranging_data_ready_cb(struct bt_conn *conn, uint16_t ranging_counter)
{
	LOG_DBG("Ranging data ready %i", ranging_counter);

	if (ranging_counter == most_recent_local_ranging_counter) {
		int err = bt_ras_rreq_cp_get_ranging_data(connection, &latest_peer_steps,
							  ranging_counter, ranging_data_cb);
		if (err) {
			LOG_ERR("Get ranging data failed (err %d)", err);
			net_buf_simple_reset(&latest_local_steps);
			net_buf_simple_reset(&latest_peer_steps);
			k_sem_give(&sem_local_steps);
		}
	}
}

static void ranging_data_overwritten_cb(struct bt_conn *conn, uint16_t ranging_counter)
{
	LOG_INF("Ranging data overwritten %i", ranging_counter);
}

static void mtu_exchange_cb(struct bt_conn *conn, uint8_t err,
			    struct bt_gatt_exchange_params *params)
{
	if (err) {
		LOG_ERR("MTU exchange failed (err %d)", err);
		return;
	}

	LOG_INF("MTU exchange success (%u)", bt_gatt_get_mtu(conn));
	k_sem_give(&sem_mtu_exchange_done);
}

static void discovery_completed_cb(struct bt_gatt_dm *dm, void *context)
{
	int err;

	LOG_INF("The discovery procedure succeeded");

	struct bt_conn *conn = bt_gatt_dm_conn_get(dm);

	bt_gatt_dm_data_print(dm);

	err = bt_ras_rreq_alloc_and_assign_handles(dm, conn);
	if (err) {
		LOG_ERR("RAS RREQ alloc init failed (err %d)", err);
	}
	ras_discovery_result = err;

	err = bt_gatt_dm_data_release(dm);
	if (err) {
		LOG_ERR("Could not release the discovery data (err %d)", err);
	}

	k_sem_give(&sem_discovery_done);
}

static void discovery_service_not_found_cb(struct bt_conn *conn, void *context)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(context);
	LOG_WRN("RAS service is not available yet");
	ras_discovery_result = -ENOENT;
	k_sem_give(&sem_discovery_done);
}

static void discovery_error_found_cb(struct bt_conn *conn, int err, void *context)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(context);
	LOG_ERR("RAS discovery failed (err %d)", err);
	ras_discovery_result = err != 0 ? err : -EIO;
	k_sem_give(&sem_discovery_done);
}

static struct bt_gatt_dm_cb discovery_cb = {
	.completed = discovery_completed_cb,
	.service_not_found = discovery_service_not_found_cb,
	.error_found = discovery_error_found_cb,
};

static void security_changed(struct bt_conn *conn, bt_security_t level, enum bt_security_err err)
{
	char addr[BT_ADDR_LE_STR_LEN];

	bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));

	if (err) {
		LOG_ERR("Security failed: %s level %u err %d %s", addr, level, err,
			bt_security_err_to_str(err));
		return;
	}

	LOG_INF("Security changed: %s level %u", addr, level);
	k_sem_give(&sem_security);
}

static bool le_param_req(struct bt_conn *conn, struct bt_le_conn_param *param)
{
	/* Ignore peer parameter preferences. */
	return false;
}

static void connected_cb(struct bt_conn *conn, uint8_t err)
{
	char addr[BT_ADDR_LE_STR_LEN];

	(void)bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));
	LOG_INF("Connected to %s (err 0x%02X)", addr, err);

	if (err) {
		bt_conn_unref(conn);
		connection = NULL;
	} else {
		connection = bt_conn_ref(conn);

		k_sem_give(&sem_connected);

		dk_set_led_on(CON_STATUS_LED);
	}
}

static void disconnected_cb(struct bt_conn *conn, uint8_t reason)
{
	LOG_INF("Disconnected (reason 0x%02X)", reason);

	bt_conn_unref(conn);
	connection = NULL;
	dk_set_led_off(CON_STATUS_LED);

	sys_reboot(SYS_REBOOT_COLD);
}

static void remote_capabilities_cb(struct bt_conn *conn, uint8_t status,
				   struct bt_conn_le_cs_capabilities *params)
{
	ARG_UNUSED(conn);

	if (status == BT_HCI_ERR_SUCCESS && params != NULL) {
		remote_cs_capabilities = *params;
		remote_cs_capabilities_valid = true;

		LOG_INF("CS capability exchange completed.");
		LOG_INF("Peer CS caps: configs=%u, max_consecutive=%u, antennas=%u, paths=%u",
			params->num_config_supported, params->max_consecutive_procedures_supported,
			params->num_antennas_supported, params->max_antenna_paths_supported);
		LOG_INF("Peer CS caps: reflector=%u, RTT_AA_N=%u, RTT_AA_precision=%u, PHY_2M=%u",
			params->reflector_supported, params->rtt_aa_only_n,
			params->rtt_aa_only_precision, params->cs_sync_2m_phy_supported);
		LOG_INF("Peer CS caps: without_FAE=%u, mode_3=%u, chsel_3c=%u",
			params->cs_without_fae_supported, params->mode_3_supported,
			params->chsel_alg_3c_supported);
		k_sem_give(&sem_remote_capabilities_obtained);
	} else {
		LOG_WRN("CS capability exchange failed. (HCI status 0x%02x, params %p)", status,
			params);
	}
}

static void remote_fae_table_cb(struct bt_conn *conn, uint8_t status,
				struct bt_conn_le_cs_fae_table *params)
{
	ARG_UNUSED(conn);

	remote_fae_table_status = status;
	if (status == BT_HCI_ERR_SUCCESS && params != NULL && params->remote_fae_table != NULL) {
		LOG_INF("Remote CS FAE table exchange completed.");
	} else {
		LOG_WRN("Remote CS FAE table exchange failed. (HCI status 0x%02x)", status);
	}

	k_sem_give(&sem_remote_fae_table_obtained);
}

static void config_create_cb(struct bt_conn *conn, uint8_t status,
			     struct bt_conn_le_cs_config *config)
{
	ARG_UNUSED(conn);

	if (status == BT_HCI_ERR_SUCCESS) {
		cs_config = *config;

		const char *mode_str;
		const char *role_str[3] = {"Initiator", "Reflector", "Invalid"};
		const char *rtt_type_str[8] = {
			"AA only",       "32-bit sounding", "96-bit sounding", "32-bit random",
			"64-bit random", "96-bit random",   "128-bit random",  "Invalid"};
		const char *phy_str[4] = {"Invalid", "LE 1M PHY", "LE 2M PHY", "LE 2M 2BT PHY"};
		const char *chsel_type_str[3] = {"Algorithm #3b", "Algorithm #3c", "Invalid"};
		const char *ch3c_shape_str[3] = {"Hat shape", "X shape", "Invalid"};

		switch (config->mode) {
		case BT_CONN_LE_CS_MAIN_MODE_1_NO_SUB_MODE:
			mode_str = "1 (RTT), no submode";
			break;
		case BT_CONN_LE_CS_MAIN_MODE_2_NO_SUB_MODE:
			mode_str = "2 (PBR), no submode";
			break;
		case BT_CONN_LE_CS_MAIN_MODE_3_NO_SUB_MODE:
			mode_str = "3 (RTT + PBR), no submode";
			break;
		case BT_CONN_LE_CS_MAIN_MODE_2_SUB_MODE_1:
			mode_str = "2 (PBR) + submode 1 (RTT)";
			break;
		case BT_CONN_LE_CS_MAIN_MODE_2_SUB_MODE_3:
			mode_str = "2 (PBR) + submode 3 (RTT + PBR)";
			break;
		case BT_CONN_LE_CS_MAIN_MODE_3_SUB_MODE_2:
			mode_str = "3 (RTT + PBR) + submode 2 (PBR)";
			break;
		default:
			mode_str = "Invalid";
			break;
		}
		uint8_t role_idx = MIN(config->role, 2);
		uint8_t rtt_type_idx = MIN(config->rtt_type, 7);
		uint8_t phy_idx = config->cs_sync_phy > 0 && config->cs_sync_phy < 4
					  ? config->cs_sync_phy
					  : 0;
		uint8_t chsel_type_idx = MIN(config->channel_selection_type, 2);
		uint8_t ch3c_shape_idx = MIN(config->ch3c_shape, 2);

		LOG_INF("CS config creation complete.\n"
			" - id: %u\n"
			" - mode: %s\n"
			" - min_main_mode_steps: %u\n"
			" - max_main_mode_steps: %u\n"
			" - main_mode_repetition: %u\n"
			" - mode_0_steps: %u\n"
			" - role: %s\n"
			" - rtt_type: %s\n"
			" - cs_sync_phy: %s\n"
			" - channel_map_repetition: %u\n"
			" - channel_selection_type: %s\n"
			" - ch3c_shape: %s\n"
			" - ch3c_jump: %u\n"
			" - t_ip1_time_us: %u\n"
			" - t_ip2_time_us: %u\n"
			" - t_fcs_time_us: %u\n"
			" - t_pm_time_us: %u\n"
			" - channel_map: 0x%08X%08X%04X\n",
			config->id, mode_str, config->min_main_mode_steps,
			config->max_main_mode_steps, config->main_mode_repetition,
			config->mode_0_steps, role_str[role_idx], rtt_type_str[rtt_type_idx],
			phy_str[phy_idx], config->channel_map_repetition,
			chsel_type_str[chsel_type_idx], ch3c_shape_str[ch3c_shape_idx],
			config->ch3c_jump, config->t_ip1_time_us, config->t_ip2_time_us,
			config->t_fcs_time_us, config->t_pm_time_us,
			sys_get_le32(&config->channel_map[6]),
			sys_get_le32(&config->channel_map[2]),
			sys_get_le16(&config->channel_map[0]));

		k_sem_give(&sem_config_created);
	} else {
		LOG_WRN("CS config creation failed. (HCI status 0x%02x)", status);
	}
}

static void security_enable_cb(struct bt_conn *conn, uint8_t status)
{
	ARG_UNUSED(conn);

	if (status == BT_HCI_ERR_SUCCESS) {
		LOG_INF("CS security enabled.");
		k_sem_give(&sem_cs_security_enabled);
	} else {
		LOG_WRN("CS security enable failed. (HCI status 0x%02x)", status);
	}
}

static void procedure_enable_cb(struct bt_conn *conn, uint8_t status,
				struct bt_conn_le_cs_procedure_enable_complete *params)
{
	ARG_UNUSED(conn);
	procedure_enable_status = status;

	if (status == BT_HCI_ERR_SUCCESS) {
		if (params->state == 1) {
			LOG_INF("CS procedures enabled:\n"
				" - config ID: %u\n"
				" - antenna configuration index: %u\n"
				" - TX power: %d dbm\n"
				" - subevent length: %u us\n"
				" - subevents per event: %u\n"
				" - subevent interval: %u\n"
				" - event interval: %u\n"
				" - procedure interval: %u\n"
				" - procedure count: %u\n"
				" - maximum procedure length: %u",
				params->config_id, params->tone_antenna_config_selection,
				params->selected_tx_power, params->subevent_len,
				params->subevents_per_event, params->subevent_interval,
				params->event_interval, params->procedure_interval,
				params->procedure_count, params->max_procedure_len);
		} else {
			LOG_INF("CS procedures disabled.");
		}
	} else {
		LOG_WRN("CS procedures enable failed. (HCI status 0x%02x)", status);
	}

	k_sem_give(&sem_procedure_enable_complete);
}

void ras_features_read_cb(struct bt_conn *conn, uint32_t feature_bits, int err)
{
	if (err) {
		LOG_WRN("Error while reading RAS feature bits (err %d)", err);
	} else {
		LOG_INF("Read RAS feature bits: 0x%x", feature_bits);
		ras_feature_bits = feature_bits;
	}

	k_sem_give(&sem_ras_features);
}

static void scan_filter_match(struct bt_scan_device_info *device_info,
			      struct bt_scan_filter_match *filter_match, bool connectable)
{
	char addr[BT_ADDR_LE_STR_LEN];

	bt_addr_le_to_str(device_info->recv_info->addr, addr, sizeof(addr));

	LOG_INF("Filters matched. Address: %s connectable: %d", addr, connectable);
}

static void scan_connecting_error(struct bt_scan_device_info *device_info)
{
	int err;

	LOG_INF("Connecting failed, restarting scanning");

	err = bt_scan_start(BT_SCAN_TYPE_SCAN_PASSIVE);
	if (err) {
		LOG_ERR("Failed to restart scanning (err %i)", err);
		return;
	}
}

static void scan_connecting(struct bt_scan_device_info *device_info, struct bt_conn *conn)
{
	LOG_INF("Connecting");
}

BT_SCAN_CB_INIT(scan_cb, scan_filter_match, NULL, scan_connecting_error, scan_connecting);

static int scan_init(struct bt_scan_init_param *p_param)
{
	int err;

	bt_scan_init(p_param);
	bt_scan_cb_register(&scan_cb);

	err = bt_scan_filter_add(BT_SCAN_FILTER_TYPE_UUID, reflector_oob_service_uuid());
	if (err) {
		LOG_ERR("Scanning filters cannot be set (err %d)", err);
		return err;
	}

	err = bt_scan_filter_enable(BT_SCAN_UUID_FILTER, false);
	if (err) {
		LOG_ERR("Filters cannot be turned on (err %d)", err);
		return err;
	}

	return 0;
}

static int discover_ras_service(struct bt_conn *conn)
{
	for (int attempt = 1; attempt <= RAS_DISCOVERY_RETRY_COUNT; attempt++) {
		k_sem_reset(&sem_discovery_done);
		ras_discovery_result = -EINPROGRESS;

		int err = bt_gatt_dm_start(conn, BT_UUID_RANGING_SERVICE, &discovery_cb, NULL);
		if (err != 0) {
			return err;
		}

		err = k_sem_take(&sem_discovery_done, K_SECONDS(10));
		if (err != 0) {
			return -ETIMEDOUT;
		}
		if (ras_discovery_result == 0) {
			return 0;
		}
		if (ras_discovery_result != -ENOENT || attempt == RAS_DISCOVERY_RETRY_COUNT) {
			return ras_discovery_result;
		}

		LOG_INF("Waiting for Android RAS service (%d/%d)", attempt,
			RAS_DISCOVERY_RETRY_COUNT);
		k_sleep(RAS_DISCOVERY_RETRY_DELAY);
	}

	return -ENOENT;
}

BT_CONN_CB_DEFINE(conn_cb) = {
	.connected = connected_cb,
	.disconnected = disconnected_cb,
	.le_param_req = le_param_req,
	.security_changed = security_changed,
	.le_cs_read_remote_capabilities_complete = remote_capabilities_cb,
	.le_cs_read_remote_fae_table_complete = remote_fae_table_cb,
	.le_cs_config_complete = config_create_cb,
	.le_cs_security_enable_complete = security_enable_cb,
	.le_cs_procedure_enable_complete = procedure_enable_cb,
	.le_cs_subevent_data_available = subevent_result_cb,
};

static void cs_config_get(struct bt_le_cs_create_config_params *config_params)
{
	memcpy(config_params->channel_map, pixel_medium_channel_map,
	       sizeof(pixel_medium_channel_map));
	config_params->id = CS_CONFIG_ID;
	config_params->mode = CS_CONFIG_MODE;
	config_params->min_main_mode_steps = 2;
	config_params->max_main_mode_steps = 5;
	config_params->main_mode_repetition = 0;
	config_params->mode_0_steps = NUM_MODE_0_STEPS;
	config_params->role = BT_CONN_LE_CS_ROLE_INITIATOR;
	config_params->rtt_type = BT_CONN_LE_CS_RTT_TYPE_AA_ONLY;
	config_params->cs_sync_phy = BT_CONN_LE_CS_SYNC_1M_PHY;
	config_params->channel_map_repetition = 1;
	/*
	 * Algorithm #3c is advertised by the Pixel but is not supported by this
	 * nRF54L15 controller build (Create Config returns HCI 0x11). With #3b the
	 * Ch3c fields are unused; use the same zero value reported by the Pixel so
	 * the two controllers retain matching configuration contents.
	 */
	config_params->channel_selection_type = BT_CONN_LE_CS_CHSEL_TYPE_3B;
	config_params->ch3c_shape = BT_CONN_LE_CS_CH3C_SHAPE_HAT;
	config_params->ch3c_jump = 0;
}

static int enable_cs_procedure_with_retry(struct bt_conn *conn)
{
	const struct bt_le_cs_procedure_enable_param params = {
		.config_id = CS_CONFIG_ID,
		.enable = 1,
	};

	/* Allow Android to finish any LL procedure triggered by OOB/CS security setup. */
	LOG_INF("Waiting for Android LL procedures to settle");
	k_sleep(CS_PROCEDURE_ENABLE_INITIAL_DELAY);

	for (int attempt = 1; attempt <= CS_PROCEDURE_ENABLE_RETRY_COUNT; attempt++) {
		k_sem_reset(&sem_procedure_enable_complete);
		procedure_enable_status = UINT8_MAX;

		int err = bt_le_cs_procedure_enable(conn, &params);
		if (err != 0) {
			LOG_ERR("Failed to queue CS procedure enable (err %d)", err);
			return err;
		}

		err = k_sem_take(&sem_procedure_enable_complete, K_SECONDS(10));
		if (err != 0) {
			LOG_ERR("Timed out waiting for CS procedure enable completion");
			return -ETIMEDOUT;
		}

		if (procedure_enable_status == BT_HCI_ERR_SUCCESS) {
			return 0;
		}

		if (procedure_enable_status != HCI_ERR_DIFFERENT_TRANSACTION_COLLISION) {
			LOG_ERR("CS procedure enable stopped on HCI status 0x%02x",
				procedure_enable_status);
			return -EIO;
		}

		if (attempt == CS_PROCEDURE_ENABLE_RETRY_COUNT) {
			break;
		}

		LOG_WRN("CS procedure collision; retrying in 1 second (%d/%d)", attempt,
			CS_PROCEDURE_ENABLE_RETRY_COUNT);
		k_sleep(CS_PROCEDURE_ENABLE_RETRY_DELAY);
	}

	LOG_ERR("CS procedure enable kept colliding with Android");
	return -EBUSY;
}

static bool uart_stop_command_received(void)
{
	static char command[UART_COMMAND_BUFFER_SIZE];
	static size_t command_length;
	uint8_t byte;

	while (uart_poll_in(console_uart, &byte) == 0) {
		if (byte == '\r' || byte == '\n') {
			command[command_length] = '\0';
			command_length = 0;

			if (strcmp(command, "STOP") == 0) {
				return true;
			}
			continue;
		}

		if (byte >= 0x20 && byte <= 0x7e) {
			if (command_length < (sizeof(command) - 1)) {
				command[command_length++] = (char)byte;
			} else {
				command_length = 0;
			}
		}
	}

	return false;
}

static int disable_cs_procedure(struct bt_conn *conn)
{
	const struct bt_le_cs_procedure_enable_param params = {
		.config_id = CS_CONFIG_ID,
		.enable = BT_CONN_LE_CS_PROCEDURES_DISABLED,
	};

	k_sem_reset(&sem_procedure_enable_complete);
	procedure_enable_status = UINT8_MAX;

	int err = bt_le_cs_procedure_enable(conn, &params);
	if (err != 0) {
		LOG_ERR("Failed to queue CS procedure disable (err %d)", err);
		return err;
	}

	err = k_sem_take(&sem_procedure_enable_complete, K_SECONDS(10));
	if (err != 0) {
		LOG_ERR("Timed out waiting for CS procedure disable completion");
		return -ETIMEDOUT;
	}

	if (procedure_enable_status != BT_HCI_ERR_SUCCESS) {
		LOG_ERR("CS procedure disable failed (HCI status 0x%02x)",
			procedure_enable_status);
		return -EIO;
	}

	return 0;
}

static void distance_estimates_print(uint8_t ap)
{
	cs_de_dist_estimates_t distance_on_ap = get_distance(ap);
	cs_de_dist_estimates_t raw_distance_on_ap = get_latest_raw_distance(ap);
	float raw_best_distance = get_best_distance(&raw_distance_on_ap);
	char peer_address[BT_ADDR_STR_LEN];

	bt_addr_to_str(&bt_conn_get_dst(connection)->a, peer_address, sizeof(peer_address));

	/*
	 * Machine-readable record consumed by tools/capture_distance_csv.ps1.
	 * Keep this independent of the human-readable log text so host-side data
	 * collection remains stable if the regular log wording changes.
	 */
	printk("DISTANCE_CSV,%lld,%ld,%u,%.6f,%.6f,%.6f\n", (long long)k_uptime_get(),
	       (long)most_recent_local_ranging_counter, ap, (double)distance_on_ap.ifft,
	       (double)distance_on_ap.phase_slope, (double)distance_on_ap.rtt);
	printk("PHONECS_DISTANCE,%lld,%ld,%u,%.6f,%s\n", (long long)k_uptime_get(),
	       (long)most_recent_local_ranging_counter, ap, (double)raw_best_distance,
	       peer_address);

#if defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_1)
	LOG_INF("Latest distance estimate rtt: %.2f meters", (double)distance_on_ap.rtt);
#elif defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_2)
	LOG_INF("Latest distance estimates on antenna path %u: ifft: %.2f, phase_slope: %.2f "
		"meters",
		ap, (double)distance_on_ap.ifft, (double)distance_on_ap.phase_slope);
#elif defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_2_SUB_MODE_1) ||                               \
	defined(CONFIG_SAMPLE_RAS_INITIATOR_STEP_MODE_3)
	LOG_INF("Latest distance estimates on antenna path %u: ifft: %.2f, phase_slope: %.2f, rtt: "
		"%.2f meters",
		ap, (double)distance_on_ap.ifft, (double)distance_on_ap.phase_slope,
		(double)distance_on_ap.rtt);
#else
	BUILD_ASSERT(false, "Invalid ranging mode");
#endif
}

int main(void)
{
	int err;

	LOG_INF("Starting Channel Sounding Initiator Sample");
	printk("DISTANCE_CSV_HEADER,uptime_ms,ranging_counter,antenna_path,ifft_m,"
	       "phase_slope_m,rtt_m\n");
	printk("PHONECS_DISTANCE_HEADER,uptime_ms,ranging_counter,antenna_path,"
	       "raw_best_distance_m,peer_address\n");

	if (!device_is_ready(console_uart)) {
		LOG_ERR("Console UART is not ready");
		return 0;
	}

	dk_leds_init();

	err = bt_enable(NULL);
	if (err) {
		LOG_ERR("Bluetooth init failed (err %d)", err);
		return 0;
	}

	struct bt_conn_le_cs_capabilities local_cs_capabilities;

	err = bt_le_cs_read_local_supported_capabilities(&local_cs_capabilities);
	if (err) {
		LOG_WRN("Failed to read local CS capabilities (err %d)", err);
	} else {
		LOG_INF("Local CS caps: antennas=%u, paths=%u, mode_3=%u, PHY_2M=%u, chsel_3c=%u",
			local_cs_capabilities.num_antennas_supported,
			local_cs_capabilities.max_antenna_paths_supported,
			local_cs_capabilities.mode_3_supported,
			local_cs_capabilities.cs_sync_2m_phy_supported,
			local_cs_capabilities.chsel_alg_3c_supported);
	}

	if (IS_ENABLED(CONFIG_SETTINGS)) {
		err = settings_load();
		if (err != 0) {
			LOG_ERR("Settings load failed (err %d)", err);
			return 0;
		}
	}

	struct bt_scan_init_param scan_params = {
		.scan_param = NULL,
		/* Pixel-compatible custom scheduling uses a fixed 30 ms ACL interval. */
		.conn_param = BT_LE_CONN_PARAM(0x18, 0x18, 0, BT_GAP_MS_TO_CONN_TIMEOUT(4000)),
		.connect_if_match = 1};

	err = scan_init(&scan_params);
	if (err) {
		LOG_ERR("Scan init failed (err %d)", err);
		return 0;
	}

	err = bt_scan_start(BT_SCAN_TYPE_SCAN_PASSIVE);
	if (err) {
		LOG_ERR("Scanning failed to start (err %i)", err);
		return 0;
	}

	k_sem_take(&sem_connected, K_FOREVER);

	k_sem_reset(&sem_security);
	err = bt_conn_set_security(connection, BT_SECURITY_L2);
	if (err != 0 && err != -EALREADY) {
		LOG_ERR("Failed to encrypt connection (err %d)", err);
		return 0;
	}

	struct bt_conn_info conn_info;
	err = bt_conn_get_info(connection, &conn_info);
	if (err != 0) {
		LOG_ERR("Failed to read connection security (err %d)", err);
		return 0;
	}
	if (conn_info.security.level < BT_SECURITY_L2) {
		err = k_sem_take(&sem_security, K_SECONDS(30));
		if (err != 0) {
			LOG_ERR("Timed out waiting for pairing/encryption");
			return 0;
		}
	}

	static struct bt_gatt_exchange_params mtu_exchange_params = {.func = mtu_exchange_cb};

	err = bt_gatt_exchange_mtu(connection, &mtu_exchange_params);
	if (err != 0) {
		LOG_ERR("Failed to start MTU exchange (err %d)", err);
		return 0;
	}

	err = k_sem_take(&sem_mtu_exchange_done, K_SECONDS(10));
	if (err != 0) {
		LOG_ERR("Timed out waiting for MTU exchange");
		return 0;
	}

	err = reflector_oob_setup(connection);
	if (err) {
		LOG_ERR("Android Ranging OOB setup failed (err %d)", err);
		return 0;
	}

	err = discover_ras_service(connection);
	if (err != 0) {
		LOG_ERR("Android RAS service discovery failed (err %d)", err);
		return 0;
	}

	const struct bt_le_cs_set_default_settings_param default_settings = {
		.enable_initiator_role = true,
		.enable_reflector_role = false,
		.cs_sync_antenna_selection = BT_LE_CS_ANTENNA_SELECTION_OPT_REPETITIVE,
		.max_tx_power = BT_HCI_OP_LE_CS_MAX_MAX_TX_POWER,
	};

	err = bt_le_cs_set_default_settings(connection, &default_settings);
	if (err) {
		LOG_ERR("Failed to configure default CS settings (err %d)", err);
		return 0;
	}

	err = bt_ras_rreq_read_features(connection, ras_features_read_cb);
	if (err) {
		LOG_ERR("Could not get RAS features from peer (err %d)", err);
		return 0;
	}

	k_sem_take(&sem_ras_features, K_FOREVER);

	const bool realtime_rd = ras_feature_bits & RAS_FEAT_REALTIME_RD;

	if (realtime_rd) {
		err = bt_ras_rreq_realtime_rd_subscribe(connection, &latest_peer_steps,
							ranging_data_cb);
		if (err) {
			LOG_ERR("RAS RREQ Real-time ranging data subscribe failed (err %d)", err);
			return 0;
		}
	} else {
		err = bt_ras_rreq_rd_overwritten_subscribe(connection, ranging_data_overwritten_cb);
		if (err) {
			LOG_ERR("RAS RREQ ranging data overwritten subscribe failed (err %d)", err);
			return 0;
		}

		err = bt_ras_rreq_rd_ready_subscribe(connection, ranging_data_ready_cb);
		if (err) {
			LOG_ERR("RAS RREQ ranging data ready subscribe failed (err %d)", err);
			return 0;
		}

		err = bt_ras_rreq_on_demand_rd_subscribe(connection);
		if (err) {
			LOG_ERR("RAS RREQ On-demand ranging data subscribe failed (err %d)", err);
			return 0;
		}

		err = bt_ras_rreq_cp_subscribe(connection);
		if (err) {
			LOG_ERR("RAS RREQ CP subscribe failed (err %d)", err);
			return 0;
		}
	}

	err = bt_le_cs_read_remote_supported_capabilities(connection);
	if (err) {
		LOG_ERR("Failed to exchange CS capabilities (err %d)", err);
		return 0;
	}

	k_sem_take(&sem_remote_capabilities_obtained, K_FOREVER);

	/*
	 * The CS Start procedure may only begin once the remote mode-0 FAE table
	 * is known, unless the peer explicitly reports CS-without-FAE support.
	 * Nordic-to-Nordic examples can skip this exchange, but Android peers may
	 * require it.
	 */
	if (!remote_cs_capabilities.cs_without_fae_supported) {
		LOG_INF("Peer requires a remote CS FAE table; starting exchange.");
		remote_fae_table_status = BT_HCI_ERR_UNSPECIFIED;
		err = bt_le_cs_read_remote_fae_table(connection);
		if (err) {
			LOG_ERR("Failed to start remote CS FAE table exchange (err %d)", err);
			return 0;
		}

		k_sem_take(&sem_remote_fae_table_obtained, K_FOREVER);
		if (remote_fae_table_status != BT_HCI_ERR_SUCCESS) {
			LOG_ERR("Cannot continue without the required remote CS FAE table");
			return 0;
		}
	} else {
		LOG_INF("Peer supports CS without FAE; remote FAE exchange is not required.");
	}

	struct bt_le_cs_create_config_params config_params;

	cs_config_get(&config_params);

	err = bt_le_cs_create_config(connection, &config_params,
				     BT_LE_CS_CREATE_CONFIG_CONTEXT_LOCAL_AND_REMOTE);
	if (err) {
		LOG_ERR("Failed to create CS config (err %d)", err);
		return 0;
	}

	k_sem_take(&sem_config_created, K_FOREVER);

	err = bt_le_cs_security_enable(connection);
	if (err) {
		LOG_ERR("Failed to start CS Security (err %d)", err);
		return 0;
	}

	k_sem_take(&sem_cs_security_enabled, K_FOREVER);

	/*
	 * Pixel-compatible custom schedule:
	 *  - 30 ms ACL connection interval (configured above)
	 *  - one procedure every 7 connection events
	 *  - maximum procedure duration 150 ms (240 * 0.625 ms)
	 *  - fixed 9820 us subevent duration
	 */
	const uint16_t desired_procedure_interval = 7;
	const uint16_t desired_max_procedure_length = 240;
	/*
	 * A zero procedure count requests an indefinite number of procedures. The
	 * peer only supports that when max_consecutive_procedures_supported is zero.
	 * Android reflectors may report a finite limit, so honor the exchanged peer
	 * capability instead of always requesting the indefinite form.
	 */
	const uint16_t max_procedure_count =
		(remote_cs_capabilities_valid &&
		 remote_cs_capabilities.max_consecutive_procedures_supported != 0)
			? remote_cs_capabilities.max_consecutive_procedures_supported
			: 0;
	const enum bt_le_cs_procedure_phy procedure_phy =
		(remote_cs_capabilities_valid && remote_cs_capabilities.cs_sync_2m_phy_supported)
			? BT_LE_CS_PROCEDURE_PHY_2M
			: BT_LE_CS_PROCEDURE_PHY_1M;

	struct bt_le_cs_set_procedure_parameters_param procedure_params = {
		.config_id = CS_CONFIG_ID,
		.max_procedure_len = desired_max_procedure_length,
		.min_procedure_interval = desired_procedure_interval,
		.max_procedure_interval = desired_procedure_interval,
		.max_procedure_count = max_procedure_count,
		.min_subevent_len = 9820,
		.max_subevent_len = 9820,
		.tone_antenna_config_selection = BT_LE_CS_TONE_ANTENNA_CONFIGURATION_A1_B1,
		.phy = procedure_phy,
		.tx_power_delta = 0x80,
		.preferred_peer_antenna = BT_LE_CS_PROCEDURE_PREFERRED_PEER_ANTENNA_3,
		.snr_control_initiator = BT_LE_CS_SNR_CONTROL_NOT_USED,
		.snr_control_reflector = BT_LE_CS_SNR_CONTROL_NOT_USED,
	};

	LOG_INF("CS procedure parameters: max_len=%u, interval=%u, count=%u, "
		"subevent=9820 us, peer_antenna=3, PHY=%s",
		procedure_params.max_procedure_len, procedure_params.min_procedure_interval,
		procedure_params.max_procedure_count,
		procedure_params.phy == BT_LE_CS_PROCEDURE_PHY_2M ? "2M" : "1M");

	err = bt_le_cs_set_procedure_parameters(connection, &procedure_params);
	if (err) {
		LOG_ERR("Failed to set procedure parameters (err %d)", err);
		return 0;
	}

	err = enable_cs_procedure_with_retry(connection);
	if (err) {
		LOG_ERR("Failed to enable CS procedures after retry (err %d)", err);
		return 0;
	}

	while (true) {
		if (uart_stop_command_received()) {
			char peer_address[BT_ADDR_STR_LEN];

			bt_addr_to_str(&bt_conn_get_dst(connection)->a, peer_address,
				       sizeof(peer_address));
			err = disable_cs_procedure(connection);
			if (err == 0) {
				printk("PHONECS_STOPPED,%lld,%s\n", (long long)k_uptime_get(),
				       peer_address);
				LOG_INF("Initiator measurement stopped by PC command");
				break;
			}

			printk("PHONECS_STOP_ERROR,%lld,%d\n", (long long)k_uptime_get(), err);
		}

		if (k_sem_take(&sem_distance_estimate_updated, K_MSEC(100)) != 0) {
			continue;
		}
		for (uint8_t ap = 0; ap < MAX_AP; ap++) {
			if (distance_estimate_buffers[ap].num_valid != 0) {
				distance_estimates_print(ap);
			}
		}
	}

	return 0;
}
