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

import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import java.util.Collection;

/** Responsible for attaching external experiment ids to log sources. */
@CheckReturnValue
public interface DownloadStageManager {

  /**
   * Clear all set experiment ids from phenotype.
   *
   * <p>Note: this must be called before any metadata is cleared since this reads from metadata to
   * learn which builds to clear.
   */
  ListenableFuture<Void> clearAll();

  /**
   * For each file group: if there are no active versions of the build, all experiment ids are
   * removed from phenotype. If there are active versions of the build (which can happen if there
   * are multiple variants/accounts), this will update the experiment ids to reflect the current
   * state given that an instance of the build was removed.
   *
   * @param fileGroupsToClear the file groups to remove experiment ids
   * @return a future signalling completion of the task
   */
  ListenableFuture<Void> clearExperimentIdsForBuildsIfNoneActive(
      Collection<DataFileGroupInternal> fileGroupsToClear);

  /**
   * Propagates the experiment ids for {@code groupName} to phenotype. If there are multiple active
   * builds with the given name, all experiment ids will be propagated.
   *
   * <p>Any failures encountered will be propagated to the returned future.
   */
  ListenableFuture<Void> updateExperimentIds(String groupName);
}
