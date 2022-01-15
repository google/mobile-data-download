/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import android.os.Build;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Static helpers for commonly-used operations on {@link Exception} classes. */
public final class Exceptions {

  /**
   * Combines multiple {@code causes} into a single new {@link IOException}. Causes are attached to
   * the new exception as {@code suppressed}, or if unsupported (SDK <19), the new exception message
   * simply includes the number of causes.
   */
  public static IOException combinedIOException(String message, List<IOException> causes) {
    // addSuppressed is available on SDK 19+.
    if (Build.VERSION.SDK_INT < 19) {
      String suppressedCount =
          String.format(Locale.US, " (%d suppressed exceptions)", causes.size());
      return new IOException(message + suppressedCount);
    }

    IOException result = new IOException(message);
    for (IOException cause : causes) {
      result.addSuppressed(cause);
    }
    return result;
  }

  private Exceptions() {}
}
