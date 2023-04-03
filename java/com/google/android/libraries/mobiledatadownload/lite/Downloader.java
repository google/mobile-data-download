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

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import java.util.concurrent.Executor;

/** The root object and entry point for the MDDLite (<internal>). */
public interface Downloader {

  /**
   * Downloads a file with the given {@link DownloadRequest}.
   *
   * <p>This method will not create a notification and will not run in a ForegroundService.
   *
   * <p>NOTE: The caller is responsible for keeping the download alive. This is typically used by
   * clients who use a service the platform binds with, so a notification is not needed. If you are
   * unsure whether to use this method or {@link #downloadWithForegroundService}, contact the MDD
   * team (<internal>@ or via <a href="<internal>">yaqs</a>.
   */
  @CheckReturnValue
  ListenableFuture<Void> download(DownloadRequest downloadRequest);

  /**
   * Download a file and show foreground download progress in a notification. User can cancel the
   * download from the notification menu.
   *
   * <p>NOTE: Calling downloadWithForegroundService without a provided ForegroundService will return
   * a failed future.
   *
   * <p>The cancel action in the notification menu requires the ForegroundService to be registered
   * with the application (via the AndroidManifest.xml). This allows the cancellation intents to be
   * properly picked up. To register the service, the following lines must be included in the app's
   * {@code AndroidManifest.xml}:
   *
   * <pre>{@code
   * <!-- Needed by foreground download service -->
   * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   *
   * <!-- Service for MDD Lite foreground downloads -->
   * <service
   *   android:name="com.google.android.libraries.mobiledatadownload.lite.sting.ForegroundDownloadService"
   *   android:exported="false" />
   * }</pre>
   *
   * <p>NOTE: The above excerpt is for Framework and Sting apps. Dagger apps should use the same
   * excerpt, but change the {@code android:name} property to:
   *
   * <pre>{@code
   * android:name="com.google.android.libraries.mobiledatadownload.lite.dagger.ForegroundDownloadService"
   * }</pre>
   */
  @CheckReturnValue
  ListenableFuture<Void> downloadWithForegroundService(DownloadRequest downloadRequest);

  /**
   * Cancel an on-going foreground download.
   *
   * <p>Use {@link ForegroundDownloadKey} to construct the unique key.
   *
   * <p><b>NOTE:</b> In most cases, clients will not need to call this -- it is meant to allow the
   * ForegroundDownloadService to cancel a download via the Cancel action registered to a
   * notification.
   *
   * <p>Clients should prefer to cancel the future returned to them from {@link
   * #downloadWithForegroundService} instead.
   */
  void cancelForegroundDownload(String downloadKey);

  static Downloader.Builder newBuilder() {
    return new Downloader.Builder();
  }

  /** A Builder for the {@link Downloader}. */
  final class Builder {

    private static final String TAG = "Builder";
    private Executor sequentialControlExecutor;

    private Context context;
    private Supplier<FileDownloader> fileDownloaderSupplier;
    private Optional<SingleFileDownloadProgressMonitor> downloadMonitorOptional = Optional.absent();
    private Optional<Class<?>> foregroundDownloadServiceClassOptional = Optional.absent();

    @CanIgnoreReturnValue
    public Builder setContext(Context context) {
      this.context = context.getApplicationContext();
      return this;
    }

    /** Set the Control Executor which will run MDDLite control flow. */
    @CanIgnoreReturnValue
    public Builder setControlExecutor(Executor controlExecutor) {
      Preconditions.checkNotNull(controlExecutor);
      // Executor that will execute tasks sequentially.
      this.sequentialControlExecutor = MoreExecutors.newSequentialExecutor(controlExecutor);
      return this;
    }

    /**
     * Set the SingleFileDownloadProgressMonitor. This instance must be the same instance that is
     * registered with SynchronousFileStorage.
     *
     * <p>This is required to use {@link Downloader#downloadWithForegroundService}. Not providing
     * this will result in a failed future when calling downloadWithForegroundService.
     *
     * <p>This is required to track progress updates and network pauses when passing a {@link
     * DownloadListener} to {@link Downloader#download}. The DownloadListener's {@code onFailure}
     * and {@code onComplete} will be invoked regardless of whether this is set.
     */
    @CanIgnoreReturnValue
    public Builder setDownloadMonitor(SingleFileDownloadProgressMonitor downloadMonitor) {
      this.downloadMonitorOptional = Optional.of(downloadMonitor);
      return this;
    }

    /**
     * Set the Foreground Download Service. This foreground service will keep the download alive
     * even if the user navigates away from the host app. This ensures long download can finish.
     *
     * <p>This is required to use {@link Downloader#downloadWithForegroundService}. Not providing
     * this will result in a failed future when calling downloadWithForegroundService.
     */
    @CanIgnoreReturnValue
    public Builder setForegroundDownloadService(Class<?> foregroundDownloadServiceClass) {
      this.foregroundDownloadServiceClassOptional = Optional.of(foregroundDownloadServiceClass);
      return this;
    }

    /**
     * Set the FileDownloader Supplier. MDDLite takes in a Supplier of FileDownload to support lazy
     * instantiation of the FileDownloader
     */
    @CanIgnoreReturnValue
    public Builder setFileDownloaderSupplier(Supplier<FileDownloader> fileDownloaderSupplier) {
      this.fileDownloaderSupplier = fileDownloaderSupplier;
      return this;
    }

    Builder() {}

    public Downloader build() {
      return new DownloaderImpl(
          context,
          foregroundDownloadServiceClassOptional,
          sequentialControlExecutor,
          downloadMonitorOptional,
          fileDownloaderSupplier);
    }
  }
}
