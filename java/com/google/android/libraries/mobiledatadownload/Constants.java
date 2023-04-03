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
package com.google.android.libraries.mobiledatadownload;

import com.google.common.base.Optional;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceStoragePolicy;

/** External MDD Constants */
public final class Constants {
  // TODO: Figure out if we can add a test to keep in sync with DownloadCondition
  /** To download under any conditions, clients should use constant. */
  // LINT.IfChange
  public static final Optional<DownloadConditions> NO_RESTRICTIONS_DOWNLOAD_CONDITIONS =
      Optional.of(
          DownloadConditions.newBuilder()
              .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK)
              .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_LOWER_THRESHOLD)
              .build());
  // LINT.ThenChange(<internal>)

  /** The version of MDD library. Same as mdi_download module version. */
  // TODO(b/122271766): Figure out how to update this automatically.
  // LINT.IfChange
  public static final int MDD_LIB_VERSION = 516938429;
  // LINT.ThenChange(<internal>)

  // <internal>
  private Constants() {}
}
