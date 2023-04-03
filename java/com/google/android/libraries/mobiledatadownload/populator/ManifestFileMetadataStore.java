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

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.populator.MetadataProto.ManifestFileBookkeeping;

/** Storage mechanism for ManifestFileBookkeeping. */
interface ManifestFileMetadataStore {
  /** Returns the metadata associated with {@code manifestId} if it exists. */
  ListenableFuture<Optional<ManifestFileBookkeeping>> read(String manifestId);

  /** Inserts or updates the metadata associated with {@code manifestId}. */
  ListenableFuture<Void> upsert(String manifestId, ManifestFileBookkeeping value);
}
