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
package com.google.android.libraries.mobiledatadownload.file.openers;

import android.net.Uri;
import android.os.Process;
import java.util.concurrent.atomic.AtomicLong;

/** Utility for creating a temp file based on PID and ThreadID. */
// TODO: consolidate with Pipes.java
final class ScratchFile {

  private static final AtomicLong SCRATCH_COUNTER = new AtomicLong();

  static Uri scratchUri(Uri file) {
    int process = Process.myPid();
    long thread = Thread.currentThread().getId();
    long timestamp = System.currentTimeMillis();
    long count = SCRATCH_COUNTER.getAndIncrement();
    String suffix = ".mobstore_tmp-" + process + "-" + thread + "-" + timestamp + "-" + count;
    return file.buildUpon().path(file.getPath() + suffix).build();
  }

  private ScratchFile() {}
}
