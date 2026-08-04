/*
 * Android Ranging OOB transport for the BleCsReflector application.
 *
 * SPDX-License-Identifier: LicenseRef-Nordic-5-Clause
 */

#ifndef REFLECTOR_OOB_H_
#define REFLECTOR_OOB_H_

#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/uuid.h>

/** UUID advertised by the Android BleCsReflector application. */
const struct bt_uuid *reflector_oob_service_uuid(void);

/**
 * Discover the reflector GATT service, enable TX indications, and perform the
 * Android Ranging OOB v1 BLE Channel Sounding capability/configuration exchange.
 *
 * The ACL connection must already be encrypted. This call blocks until the
 * exchange completes or a timeout/error occurs.
 */
int reflector_oob_setup(struct bt_conn *conn);

#endif /* REFLECTOR_OOB_H_ */
