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
package com.google.android.libraries.mobiledatadownload.populator;

import com.google.android.libraries.mobiledatadownload.FileGroupPopulator;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * A {@code MigrationProxyPopulator} can be used by a client to run migration experiments from one
 * file group populator to another.
 *
 * <p>{@code flagSupplier} is used to determine whether the control or experiment populator is used.
 * It is called every time a client calls refreshFileGroups. When it returns true, {@code
 * experimentFileGroupPopulator} is delegated to refresh file group; when it returns false, {@code
 * controlFileGroupPopulator} is delegated to refresh file group. This design enables the client to
 * refresh on the correct populator based on most up-to-date flag value.
 */
public final class MigrationProxyPopulator implements FileGroupPopulator {
  private final FileGroupPopulator controlFileGroupPopulator;
  private final FileGroupPopulator experimentFileGroupPopulator;
  private final Supplier<Boolean> flagSupplier;

  public MigrationProxyPopulator(
      FileGroupPopulator controlFileGroupPopulator,
      FileGroupPopulator experimentFileGroupPopulator,
      Supplier<Boolean> flagSupplier) {
    this.controlFileGroupPopulator = controlFileGroupPopulator;
    this.experimentFileGroupPopulator = experimentFileGroupPopulator;
    this.flagSupplier = flagSupplier;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
    if (flagSupplier.get()) {
      return experimentFileGroupPopulator.refreshFileGroups(mobileDataDownload);
    } else {
      return controlFileGroupPopulator.refreshFileGroups(mobileDataDownload);
    }
  }
}
