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
package com.google.android.libraries.mobiledatadownload.file.integration.downloader;

import android.net.Uri;
import com.google.android.downloader.DownloadMetadata;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;

/** Storage mechanism for DownloadMetadata. */
public interface DownloadMetadataStore {
  /** Returns the DownloadMetadata associated with {@code uri} if it exists. */
  ListenableFuture<Optional<DownloadMetadata>> read(Uri uri);

  /** Inserts or updates the {@code metadata} associated with {@code uri}. */
  ListenableFuture<Void> upsert(Uri uri, DownloadMetadata metadata);

  /** Deletes the DownloadMetadata associated with {@code uri} if it exists. */
  ListenableFuture<Void> delete(Uri uri);
}
