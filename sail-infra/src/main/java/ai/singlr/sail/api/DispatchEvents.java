/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single builder for the {@code spec_dispatched} event's data payload, used by both dispatch
 * lanes (the CLI and the HTTP API). One builder means one field order and one shape on the bus — no
 * hand-synced duplicate to drift. {@code branch} is included only when present; {@code mode} is
 * always the launch mode.
 */
public final class DispatchEvents {

  private DispatchEvents() {}

  public static Map<String, Object> dispatchedData(String branch, String mode) {
    var data = new LinkedHashMap<String, Object>();
    if (Strings.isNotBlank(branch)) {
      data.put("branch", branch);
    }
    data.put("mode", mode);
    return data;
  }
}
