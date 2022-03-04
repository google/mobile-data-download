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
package com.google.android.libraries.mobiledatadownload.internal.experimentation;

import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import java.util.Collection;

/** Implementation of DownloadStageManager that does nothing. */
@CheckReturnValue
public final class NoOpDownloadStageManager implements DownloadStageManager {

  /** Clear all set experiment ids from phenotype. */
  @Override
  public ListenableFuture<Void> clearAll() {
    return immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> clearExperimentIdsForBuildsIfNoneActive(
      Collection<DataFileGroupInternal> fileGroupsToClear) {
    return immediateVoidFuture();
  }

  /**
   * Propagates the experiment ids for {@code groupName} to phenotype. If there are multiple active
   * builds with the given name, all experiment ids will be propagated.
   *
   * <p>Any failures encountered will be propagated to the returned future.
   */
  @Override
  public ListenableFuture<Void> updateExperimentIds(String groupName) {
    return immediateVoidFuture();
  }
}
