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
package com.google.android.libraries.mobiledatadownload.internal;

import android.os.Build.VERSION_CODES;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import com.google.android.libraries.mobiledatadownload.TimeSource;

/**
 * Implementation of {@link com.google.android.libraries.mobiledatadownload.TimeSource} based on
 * Android platform APIs.
 */

// necessary since cgal.clock isn't available in 3P
@RequiresApi(VERSION_CODES.JELLY_BEAN_MR1) // android.os.SystemClock#elapsedRealtimeNanos
public final class AndroidTimeSource implements TimeSource {

  @Override
  public long currentTimeMillis() {
    return System.currentTimeMillis();
  }

  @Override
  public long elapsedRealtimeNanos() {
    return SystemClock.elapsedRealtimeNanos();
  }
}
