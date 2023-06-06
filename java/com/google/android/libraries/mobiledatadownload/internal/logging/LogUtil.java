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
package com.google.android.libraries.mobiledatadownload.internal.logging;

import android.annotation.SuppressLint;
import android.util.Log;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import java.util.Locale;
import java.util.Random;
import javax.annotation.Nullable;

/** Utility class for logging with the "MDD" tag. */
public class LogUtil {
  public static final String TAG = "MDD";

  private static final Random random = new Random();

  public static int getLogPriority() {
    int level = Log.ASSERT;
    while (level > Log.VERBOSE) {
      if (!Log.isLoggable(TAG, level - 1)) {
        break;
      }
      level--;
    }
    return level;
  }

  public static int v(String msg) {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      return Log.v(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int v(@FormatString String format, Object obj0) {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      String msg = format(format, obj0);
      return Log.v(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int v(@FormatString String format, Object obj0, Object obj1) {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      String msg = format(format, obj0, obj1);
      return Log.v(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int v(@FormatString String format, Object... params) {
    if (Log.isLoggable(TAG, Log.VERBOSE)) {
      String msg = format(format, params);
      return Log.v(TAG, msg);
    }
    return 0;
  }

  public static int d(String msg) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      return Log.d(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int d(@FormatString String format, Object obj0) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      String msg = format(format, obj0);
      return Log.d(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int d(@FormatString String format, Object obj0, Object obj1) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      String msg = format(format, obj0, obj1);
      return Log.d(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int d(@FormatString String format, Object... params) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      String msg = format(format, params);
      return Log.d(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int d(@Nullable Throwable tr, @FormatString String format, Object... params) {
    if (Log.isLoggable(TAG, Log.DEBUG)) {
      String msg = format(format, params);
      return Log.d(TAG, msg, tr);
    }
    return 0;
  }

  public static int i(String msg) {
    if (Log.isLoggable(TAG, Log.INFO)) {
      return Log.i(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int i(@FormatString String format, Object obj0) {
    if (Log.isLoggable(TAG, Log.INFO)) {
      String msg = format(format, obj0);
      return Log.i(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int i(@FormatString String format, Object obj0, Object obj1) {
    if (Log.isLoggable(TAG, Log.INFO)) {
      String msg = format(format, obj0, obj1);
      return Log.i(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int i(@FormatString String format, Object... params) {
    if (Log.isLoggable(TAG, Log.INFO)) {
      String msg = format(format, params);
      return Log.i(TAG, msg);
    }
    return 0;
  }

  public static int e(String msg) {
    if (Log.isLoggable(TAG, Log.ERROR)) {
      return Log.e(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int e(@FormatString String format, Object obj0) {
    if (Log.isLoggable(TAG, Log.ERROR)) {
      String msg = format(format, obj0);
      return Log.e(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int e(@FormatString String format, Object obj0, Object obj1) {
    if (Log.isLoggable(TAG, Log.ERROR)) {
      String msg = format(format, obj0, obj1);
      return Log.e(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int e(@FormatString String format, Object... params) {
    if (Log.isLoggable(TAG, Log.ERROR)) {
      String msg = format(format, params);
      return Log.e(TAG, msg);
    }
    return 0;
  }

  @SuppressLint("LogTagMismatch")
  public static int e(@Nullable Throwable tr, String msg) {
    if (Log.isLoggable(TAG, Log.ERROR)) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        return Log.e(TAG, msg, tr);
      } else {
        // If not DEBUG level, only print the throwable type and message.
        msg = msg + ": " + tr;
        return Log.e(TAG, msg);
      }
    }
    return 0;
  }

  @FormatMethod
  public static int e(@Nullable Throwable tr, @FormatString String format, Object... params) {
    return Log.isLoggable(TAG, Log.ERROR) ? e(tr, format(format, params)) : 0;
  }

  public static int w(String msg) {
    if (Log.isLoggable(TAG, Log.WARN)) {
      return Log.w(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int w(@FormatString String format, Object obj0) {
    if (Log.isLoggable(TAG, Log.WARN)) {
      String msg = format(format, obj0);
      return Log.w(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int w(@FormatString String format, Object obj0, Object obj1) {
    if (Log.isLoggable(TAG, Log.WARN)) {
      String msg = format(format, obj0, obj1);
      return Log.w(TAG, msg);
    }
    return 0;
  }

  @FormatMethod
  public static int w(@FormatString String format, Object... params) {
    if (Log.isLoggable(TAG, Log.WARN)) {
      String msg = format(format, params);
      return Log.w(TAG, msg);
    }
    return 0;
  }

  @SuppressLint("LogTagMismatch")
  @FormatMethod
  public static int w(@Nullable Throwable tr, @FormatString String format, Object... params) {
    if (Log.isLoggable(TAG, Log.WARN)) {
      if (Log.isLoggable(TAG, Log.DEBUG)) {
        String msg = format(format, params);
        return Log.w(TAG, msg, tr);
      } else {
        // If not DEBUG level, only print the throwable type and message.
        String msg = format(format, params) + ": " + tr;
        return Log.w(TAG, msg);
      }
    }
    return 0;
  }

  @FormatMethod
  private static String format(@FormatString String format, Object... args) {
    return String.format(Locale.US, format, args);
  }

  public static boolean shouldSampleInterval(long sampleInterval) {
    if (sampleInterval <= 0L) {
      if (sampleInterval < 0L) {
        LogUtil.e("Bad sample interval: %d", sampleInterval);
      }
      return false;
    } else {
      return (random.nextLong() % sampleInterval) == 0;
    }
  }

  private LogUtil() {}
}
