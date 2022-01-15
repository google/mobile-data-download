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
package com.google.android.libraries.mobiledatadownload.lite;

import android.net.Uri;

/** Interface for Monitoring Single File Downloads for MDD Lite. */
public interface SingleFileDownloadProgressMonitor {

  /**
   * Add a DownloadListener that client can use to receive download progress update. Currently we
   * only support 1 listener per file uri. Calling addDownloadListener to add another listener would
   * be no-op.
   *
   * @param uri The destination file uri that the DownloadListener will receive download progress
   *     update.
   * @param downloadListener the DownloadListener to add.
   */
  public void addDownloadListener(Uri uri, DownloadListener downloadListener);

  /**
   * Remove a DownloadListener.
   *
   * @param uri The uri that the DownloadListener receive download progress update.
   */
  public void removeDownloadListener(Uri uri);
}
