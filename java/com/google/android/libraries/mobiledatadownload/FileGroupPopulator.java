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

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Populates MDD with file groups from multiple sources like phenotype or built on the device.
 *
 * <p>Clients must overrides the refreshFileGroups method and add groups to {@link
 * MobileDataDownload#addFileGroup(AddFileGroupRequest)} in the impl.
 */
public interface FileGroupPopulator {

  /**
   * Called periodically by MDD to refresh file groups.
   *
   * <p>This group should ideally be reading from a source like phenotype, so that mdd gets the
   * updates from there on a regular basis.
   */
  ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload);
}
