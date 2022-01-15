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
 * Listener for {@link MobileDataDownload#downloadFile} to respond to download events.
 *
 * <p>Supports registering for download progress update. Callbacks will be executed
 * on @MddControlExecutor. If you need to do heavy work, please offload to a background task.
 */
public interface SingleFileDownloadListener {
  /**
   * Will be triggered periodically with the current downloaded size of the being downloaded file.
   * This could be used to show progressbar to users.
   */
  void onProgress(long currentSize);

  /**
   * This will be called when the download is completed successfully. MDD will keep the Foreground
   * Download Service alive so that the onComplete can finish without being killed by Android.
   */
  ListenableFuture<Void> onComplete();

  /** This will be called when the download failed. */
  void onFailure(Throwable t);

  /**
   * Callback triggered when all downloads are in a state waiting for connectivity, and no download
   * progress is happening until connectivity resumes.
   */
  void onPausedForConnectivity();
}
