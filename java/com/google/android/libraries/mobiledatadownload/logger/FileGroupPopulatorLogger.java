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
package com.google.android.libraries.mobiledatadownload.logger;

import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.Logger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.mobiledatadownload.LogEnumsProto.MddDownloadResult;

/** The event logger for {@code FileGroupPopulator}'s. */
public final class FileGroupPopulatorLogger {

  private final Logger logger;
  private final Flags flags;

  public FileGroupPopulatorLogger(Logger logger, Flags flags) {
    this.logger = logger;
    this.flags = flags;
  }

  /** Logs the refresh result of {@code ManifestFileGroupPopulator}. */
  public void logManifestFileGroupPopulatorRefreshResult(
      MddDownloadResult.Code code,
      String manifestId,
      String ownerPackageName,
      String manifestFileUrl) {
    int sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
  }

  /** Logs the refresh result of {@code GellerFileGroupPopulator}. */
  public void logGddFileGroupPopulatorRefreshResult(
      MddDownloadResult.Code code, String configurationId, String ownerPackageName, String corpus) {
    int sampleInterval = flags.mddDefaultSampleInterval();
    if (!LogUtil.shouldSampleInterval(sampleInterval)) {
      return;
    }
  }
}
