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

import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;

/**
 * Supports registering for download progress update.
 *
 * <p>In general, don't do anything heavy on onProgress and onComplete since it is running o
 */
public interface DownloadListener {
  String TAG = "DownloadListener";

  /**
   * Will be triggered periodically with the current downloaded size of the file group. This could
   * be used to show progressbar to users.
   *
   * <p>The onProgress is run on MDD Download Executor. If you need to do heavy work, please offload
   * to a background task.
   */
  // TODO(b/129464897): make onProgress run on control executor.
  void onProgress(long currentSize);

  /**
   * This will be called when the download is completed. The clientFileGroup has data about the
   * downloaded file group.
   *
   * <p>The onComplete is run on MDD Control Executor. If you need to do heavy work, please offload
   * to a background task.
   *
   * <p>If using foreground downloads, an exception may be thrown here to tell MDD a failure
   * notification should be shown instead of a success notification. <b>NOTE:</b> this is the only
   * case where the exception will be taken into account. Throwing an exception here will
   * <em>NOT</em> cause the download future returned by MDD to fail.
   */
  // TODO (b/236401280): Switch to async api
  void onComplete(ClientFileGroup clientFileGroup) throws Exception;

  /** This will be called when the download failed. */
  default void onFailure(Throwable t) {
    LogUtil.e(t, "%s: onFailure", TAG);
  }

  /**
   * Callback triggered when all downloads are in a state waiting for connectivity, and no download
   * progress is happening until connectivity resumes.
   */
  default void pausedForConnectivity() {
    LogUtil.d("%s: pausedForConnectivity", TAG);
  }
}
