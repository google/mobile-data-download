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
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.foreground.NotificationUtil;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;

final class DownloaderImpl implements Downloader {
  private static final String TAG = "DownloaderImp";

  private final Context context;
  private final Optional<Class<?>> foregroundDownloadServiceClassOptional;
  // This executor will execute tasks sequentially.
  private final Executor sequentialControlExecutor;
  private final Optional<SingleFileDownloadProgressMonitor> downloadMonitorOptional;
  private final Supplier<FileDownloader> fileDownloaderSupplier;

  // Synchronization will be done through sequentialControlExecutor
  @VisibleForTesting
  final Map<String, ListenableFuture<Void>> keyToListenableFuture = new HashMap<>();

  DownloaderImpl(
      Context context,
      Optional<Class<?>> foregroundDownloadServiceClassOptional,
      Executor sequentialControlExecutor,
      Optional<SingleFileDownloadProgressMonitor> downloadMonitorOptional,
      Supplier<FileDownloader> fileDownloaderSupplier) {
    this.context = context;
    this.sequentialControlExecutor = sequentialControlExecutor;
    this.foregroundDownloadServiceClassOptional = foregroundDownloadServiceClassOptional;
    this.downloadMonitorOptional = downloadMonitorOptional;
    this.fileDownloaderSupplier = fileDownloaderSupplier;
  }

  @Override
  public ListenableFuture<Void> download(DownloadRequest downloadRequest) {
    LogUtil.d("%s: download for Uri = %s", TAG, downloadRequest.destinationFileUri().toString());
    return Futures.submitAsync(
        () -> {
          // if there is the same on-going request, return that one.
          if (keyToListenableFuture.containsKey(downloadRequest.destinationFileUri().toString())) {
            // uriToListenableFuture.get must return Non-null since we check the containsKey above.
            // checkNotNull is to suppress false alarm about @Nullable result.
            return Preconditions.checkNotNull(
                keyToListenableFuture.get(downloadRequest.destinationFileUri().toString()));
          }

          // Register listener with monitor if present
          if (downloadRequest.listenerOptional().isPresent()) {
            if (downloadMonitorOptional.isPresent()) {
              downloadMonitorOptional
                  .get()
                  .addDownloadListener(
                      downloadRequest.destinationFileUri(),
                      downloadRequest.listenerOptional().get());
            } else {
              LogUtil.w(
                  "%s: download request included DownloadListener, but DownloadMonitor is not"
                      + " present! DownloadListener will only be invoked for complete/failure.");
            }
          }

          ListenableFuture<Void> downloadFuture = startDownload(downloadRequest);

          Futures.addCallback(
              downloadFuture,
              new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                  // Currently the MobStore monitor does not support onSuccess so we have to add
                  // callback to the download future here.

                  // Remove download listener and remove download future from map after listener
                  // completes
                  if (downloadRequest.listenerOptional().isPresent()) {
                    Futures.addCallback(
                        downloadRequest.listenerOptional().get().onComplete(),
                        new FutureCallback<Void>() {
                          @Override
                          public void onSuccess(@NullableDecl Void result) {
                            keyToListenableFuture.remove(
                                downloadRequest.destinationFileUri().toString());
                            if (downloadMonitorOptional.isPresent()) {
                              downloadMonitorOptional
                                  .get()
                                  .removeDownloadListener(downloadRequest.destinationFileUri());
                            }
                          }

                          @Override
                          public void onFailure(Throwable t) {
                            LogUtil.e(t, "%s: Failed to run client onComplete", TAG);
                            keyToListenableFuture.remove(
                                downloadRequest.destinationFileUri().toString());
                            if (downloadMonitorOptional.isPresent()) {
                              downloadMonitorOptional
                                  .get()
                                  .removeDownloadListener(downloadRequest.destinationFileUri());
                            }
                          }
                        },
                        sequentialControlExecutor);
                  } else {
                    // remove from future map immediately
                    keyToListenableFuture.remove(downloadRequest.destinationFileUri().toString());
                  }
                }

                @Override
                public void onFailure(Throwable t) {
                  LogUtil.e(t, "%s: Download Future failed", TAG);

                  // Currently the MobStore monitor does not support onFailure so we have to add
                  // callback to the download future here.
                  if (downloadRequest.listenerOptional().isPresent()) {
                    downloadRequest.listenerOptional().get().onFailure(t);
                    if (downloadMonitorOptional.isPresent()) {
                      downloadMonitorOptional
                          .get()
                          .removeDownloadListener(downloadRequest.destinationFileUri());
                    }
                  }
                  keyToListenableFuture.remove(downloadRequest.destinationFileUri().toString());
                }
              },
              MoreExecutors.directExecutor());

          keyToListenableFuture.put(
              downloadRequest.destinationFileUri().toString(), downloadFuture);
          return downloadFuture;
        },
        sequentialControlExecutor);
  }

  private ListenableFuture<Void> startDownload(DownloadRequest downloadRequest) {
    // Translate from MDDLite DownloadRequest to MDDDownloader DownloadRequest.
    com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest
        fileDownloaderRequest =
            com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest.newBuilder()
                .setFileUri(downloadRequest.destinationFileUri())
                .setDownloadConstraints(downloadRequest.downloadConstraints())
                .setUrlToDownload(downloadRequest.urlToDownload())
                .setExtraHttpHeaders(downloadRequest.extraHttpHeaders())
                .setTrafficTag(downloadRequest.trafficTag())
                .build();
    try {
      return fileDownloaderSupplier.get().startDownloading(fileDownloaderRequest);
    } catch (RuntimeException e) {
      // Catch any unchecked exceptions that prevented the download from starting.
      return Futures.immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
              .setCause(e)
              .build());
    }
  }

  @Override
  public ListenableFuture<Void> downloadWithForegroundService(DownloadRequest downloadRequest) {
    LogUtil.d(
        "%s: downloadWithForegroundService for Uri = %s",
        TAG, downloadRequest.destinationFileUri().toString());
    if (!downloadMonitorOptional.isPresent()) {
      return Futures.immediateFailedFuture(
          new IllegalStateException(
              "downloadWithForegroundService: DownloadMonitor is not provided!"));
    }
    if (!foregroundDownloadServiceClassOptional.isPresent()) {
      return Futures.immediateFailedFuture(
          new IllegalStateException(
              "downloadWithForegroundService: ForegroundDownloadService is not provided!"));
    }
    return Futures.submitAsync(
        () -> {
          // if there is the same on-going request, return that one.
          if (keyToListenableFuture.containsKey(downloadRequest.destinationFileUri().toString())) {
            // uriToListenableFuture.get must return Non-null since we check the containsKey above.
            // checkNotNull is to suppress false alarm about @Nullable result.
            return Preconditions.checkNotNull(
                keyToListenableFuture.get(downloadRequest.destinationFileUri().toString()));
          }

          // It's OK to recreate the NotificationChannel since it can also be used to restore a
          // deleted channel and to update an existing channel's name, description, group, and/or
          // importance.
          NotificationUtil.createNotificationChannel(context);

          // Only start the foreground download service when there is the first download request.
          if (keyToListenableFuture.isEmpty()) {
            NotificationUtil.startForegroundDownloadService(
                context,
                foregroundDownloadServiceClassOptional.get(),
                downloadRequest.destinationFileUri().toString());
          }

          DownloadListener downloadListenerWithNotification =
              createDownloadListenerWithNotification(downloadRequest);

          // The downloadMonitor will trigger the DownloadListener.
          downloadMonitorOptional
              .get()
              .addDownloadListener(
                  downloadRequest.destinationFileUri(), downloadListenerWithNotification);

          ListenableFuture<Void> downloadFuture = startDownload(downloadRequest);

          Futures.addCallback(
              downloadFuture,
              new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                  // Currently the MobStore monitor does not support onSuccess so we have to add
                  // callback to the download future here.

                  Futures.addCallback(
                      downloadListenerWithNotification.onComplete(),
                      new FutureCallback<Void>() {
                        @Override
                        public void onSuccess(@NullableDecl Void result) {}

                        @Override
                        public void onFailure(Throwable t) {
                          LogUtil.e(t, "%s: Failed to run client onComplete", TAG);
                        }
                      },
                      sequentialControlExecutor);
                }

                @Override
                public void onFailure(Throwable t) {
                  // Currently the MobStore monitor does not support onFailure so we have to add
                  // callback to the download future here.
                  LogUtil.e(t, "%s: Download Future failed", TAG);
                  downloadListenerWithNotification.onFailure(t);
                }
              },
              MoreExecutors.directExecutor());

          keyToListenableFuture.put(
              downloadRequest.destinationFileUri().toString(), downloadFuture);
          return downloadFuture;
        },
        sequentialControlExecutor);
  }

  // Assertion: foregroundDownloadService and downloadMonitor are present
  private DownloadListener createDownloadListenerWithNotification(DownloadRequest downloadRequest) {
    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    NotificationCompat.Builder notification =
        NotificationUtil.createNotificationBuilder(
            context,
            downloadRequest.fileSizeBytes(),
            downloadRequest.notificationContentTitle(),
            downloadRequest.notificationContentTextOptional().or(downloadRequest.urlToDownload()));

    int notificationKey =
        NotificationUtil.notificationKeyForKey(downloadRequest.destinationFileUri().toString());

    // Attach the Cancel action to the notification.
    NotificationUtil.createCancelAction(
        context,
        foregroundDownloadServiceClassOptional.get(),
        downloadRequest.destinationFileUri().toString(),
        notification,
        notificationKey);
    notificationManager.notify(notificationKey, notification.build());

    return new DownloadListener() {
      @Override
      public void onProgress(long currentSize) {
        sequentialControlExecutor.execute(
            () -> {
              // There can be a race condition, where onPausedForConnectivity can be called
              // after onComplete or onFailure which removes the future and the notification.
              if (keyToListenableFuture.containsKey(
                  downloadRequest.destinationFileUri().toString())) {
                notification
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(
                        downloadRequest.fileSizeBytes(),
                        (int) currentSize,
                        /* indeterminate = */ downloadRequest.fileSizeBytes() <= 0);
                notificationManager.notify(notificationKey, notification.build());
              }
              if (downloadRequest.listenerOptional().isPresent()) {
                downloadRequest.listenerOptional().get().onProgress(currentSize);
              }
            });
      }

      @Override
      public void onPausedForConnectivity() {
        sequentialControlExecutor.execute(
            () -> {
              // There can be a race condition, where onPausedForConnectivity can be called
              // after onComplete or onFailure which removes the future and the notification.
              if (keyToListenableFuture.containsKey(
                  downloadRequest.destinationFileUri().toString())) {
                notification
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentText(NotificationUtil.getDownloadPausedMessage(context))
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    // hide progress bar.
                    .setProgress(0, 0, false);
                notificationManager.notify(notificationKey, notification.build());
              }

              if (downloadRequest.listenerOptional().isPresent()) {
                downloadRequest.listenerOptional().get().onPausedForConnectivity();
              }
            });
      }

      @Override
      public ListenableFuture<Void> onComplete() {
        // We want to keep the Foreground Download Service alive until client's onComplete finishes.
        ListenableFuture<Void> clientOnCompleteFuture =
            downloadRequest.listenerOptional().isPresent()
                ? downloadRequest.listenerOptional().get().onComplete()
                : Futures.immediateVoidFuture();

        // Logic to shutdown Foreground Download Service after the client's provided onComplete
        // finished
        clientOnCompleteFuture.addListener(
            () -> {
              // Clear the notification action.
              notification.mActions.clear();

              if (downloadRequest.showDownloadedNotification()) {
                notification
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentText(NotificationUtil.getDownloadSuccessMessage(context))
                    .setOngoing(false)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    // hide progress bar.
                    .setProgress(0, 0, false);

                notificationManager.notify(notificationKey, notification.build());
              } else {
                NotificationUtil.cancelNotificationForKey(
                    context, downloadRequest.destinationFileUri().toString());
              }

              keyToListenableFuture.remove(downloadRequest.destinationFileUri().toString());
              // If there is no other on-going foreground download, shutdown the
              // ForegroundDownloadService
              if (keyToListenableFuture.isEmpty()) {
                NotificationUtil.stopForegroundDownloadService(
                    context, foregroundDownloadServiceClassOptional.get());
              }

              downloadMonitorOptional
                  .get()
                  .removeDownloadListener(downloadRequest.destinationFileUri());
            },
            sequentialControlExecutor);
        return clientOnCompleteFuture;
      }

      @Override
      public void onFailure(Throwable t) {
        sequentialControlExecutor.execute(
            () -> {
              // Clear the notification action.
              notification.mActions.clear();

              // Show download failed in notification.
              notification
                  .setCategory(NotificationCompat.CATEGORY_STATUS)
                  .setContentText(NotificationUtil.getDownloadFailedMessage(context))
                  .setOngoing(false)
                  .setSmallIcon(android.R.drawable.stat_sys_warning)
                  // hide progress bar.
                  .setProgress(0, 0, false);

              notificationManager.notify(notificationKey, notification.build());

              keyToListenableFuture.remove(downloadRequest.destinationFileUri().toString());

              // If there is no other on-going foreground download, shutdown the
              // ForegroundDownloadService
              if (keyToListenableFuture.isEmpty()) {
                NotificationUtil.stopForegroundDownloadService(
                    context, foregroundDownloadServiceClassOptional.get());
              }

              if (downloadRequest.listenerOptional().isPresent()) {
                downloadRequest.listenerOptional().get().onFailure(t);
              }
              downloadMonitorOptional
                  .get()
                  .removeDownloadListener(downloadRequest.destinationFileUri());
            });
      }
    };
  }

  @Override
  public void cancelForegroundDownload(String destinationFileUri) {
    LogUtil.d("%s: CancelForegroundDownload for Uri = %s", TAG, destinationFileUri);
    sequentialControlExecutor.execute(
        () -> {
          if (keyToListenableFuture.containsKey(destinationFileUri)) {
            keyToListenableFuture.get(destinationFileUri).cancel(true);
          }
        });
  }
}
