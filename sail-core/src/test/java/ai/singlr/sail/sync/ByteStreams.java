/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class ByteStreams {
  private ByteStreams() {}

  public static final class Input extends ByteArrayInputStream {
    public Input(String text) {
      super(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  public static final class Output extends ByteArrayOutputStream {
    public void write(String text) {
      writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
      return toString(StandardCharsets.UTF_8);
    }
  }
}
