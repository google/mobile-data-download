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

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.content.Context;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.foreground.ForegroundDownloadKey;
import com.google.android.libraries.mobiledatadownload.foreground.NotificationUtil;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.DownloadFutureMap;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.common.util.concurrent.MoreExecutors;
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

  @VisibleForTesting final DownloadFutureMap<Void> downloadFutureMap;
  @VisibleForTesting final DownloadFutureMap<Void> foregroundDownloadFutureMap;

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
    this.downloadFutureMap = DownloadFutureMap.create(sequentialControlExecutor);
    this.foregroundDownloadFutureMap =
        DownloadFutureMap.create(
            sequentialControlExecutor,
            createCallbacksForForegroundService(context, foregroundDownloadServiceClassOptional));
  }

  @Override
  public ListenableFuture<Void> download(DownloadRequest downloadRequest) {
    LogUtil.d("%s: download for Uri = %s", TAG, downloadRequest.destinationFileUri().toString());
    ForegroundDownloadKey foregroundDownloadKey =
        ForegroundDownloadKey.ofSingleFile(downloadRequest.destinationFileUri());

    return PropagatedFutures.transformAsync(
        getInProgressDownloadFuture(foregroundDownloadKey.toString()),
        (Optional<ListenableFuture<Void>> existingDownloadFuture) -> {
          // if there is the same on-going request, return that one.
          if (existingDownloadFuture.isPresent()) {
            return existingDownloadFuture.get();
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
                      + " present! DownloadListener will only be invoked for complete/failure.",
                  TAG);
            }
          }

          // Create a ListenableFutureTask to delay starting the downloadFuture until we can add the
          // future to our map.
          ListenableFutureTask<Void> startTask = ListenableFutureTask.create(() -> null);
          ListenableFuture<Void> downloadFuture =
              PropagatedFutures.transformAsync(
                  startTask, unused -> startDownload(downloadRequest), sequentialControlExecutor);

          PropagatedFutures.addCallback(
              downloadFuture,
              new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                  // Currently the MobStore monitor does not support onSuccess so we have to add
                  // callback to the download future here.

                  // Remove download listener and remove download future from map after listener
                  // completes
                  if (downloadRequest.listenerOptional().isPresent()) {
                    PropagatedFutures.addCallback(
                        downloadRequest.listenerOptional().get().onComplete(),
                        new FutureCallback<Void>() {
                          @Override
                          public void onSuccess(@NullableDecl Void result) {
                            if (downloadMonitorOptional.isPresent()) {
                              downloadMonitorOptional
                                  .get()
                                  .removeDownloadListener(downloadRequest.destinationFileUri());
                            }
                            ListenableFuture<Void> unused =
                                downloadFutureMap.remove(foregroundDownloadKey.toString());
                          }

                          @Override
                          public void onFailure(Throwable t) {
                            LogUtil.e(t, "%s: Failed to run client onComplete", TAG);
                            if (downloadMonitorOptional.isPresent()) {
                              downloadMonitorOptional
                                  .get()
                                  .removeDownloadListener(downloadRequest.destinationFileUri());
                            }
                            ListenableFuture<Void> unused =
                                downloadFutureMap.remove(foregroundDownloadKey.toString());
                          }
                        },
                        sequentialControlExecutor);
                  } else {
                    ListenableFuture<Void> unused =
                        downloadFutureMap.remove(foregroundDownloadKey.toString());
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
                  ListenableFuture<Void> unused =
                      downloadFutureMap.remove(foregroundDownloadKey.toString());
                }
              },
              MoreExecutors.directExecutor());

          return PropagatedFutures.transformAsync(
              downloadFutureMap.add(foregroundDownloadKey.toString(), downloadFuture),
              unused -> {
                // Now that the download future is added, start the task and return the future
                startTask.run();
                return downloadFuture;
              },
              sequentialControlExecutor);
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
      return immediateFailedFuture(
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
      return immediateFailedFuture(
          new IllegalStateException(
              "downloadWithForegroundService: DownloadMonitor is not provided!"));
    }
    if (!foregroundDownloadServiceClassOptional.isPresent()) {
      return immediateFailedFuture(
          new IllegalStateException(
              "downloadWithForegroundService: ForegroundDownloadService is not provided!"));
    }

    ForegroundDownloadKey foregroundDownloadKey =
        ForegroundDownloadKey.ofSingleFile(downloadRequest.destinationFileUri());

    return PropagatedFutures.transformAsync(
        getInProgressDownloadFuture(foregroundDownloadKey.toString()),
        (Optional<ListenableFuture<Void>> existingDownloadFuture) -> {
          // if there is the same on-going request, return that one.
          if (existingDownloadFuture.isPresent()) {
            return existingDownloadFuture.get();
          }

          // It's OK to recreate the NotificationChannel since it can also be used to restore a
          // deleted channel and to update an existing channel's name, description, group, and/or
          // importance.
          NotificationUtil.createNotificationChannel(context);

          DownloadListener downloadListenerWithNotification =
              createDownloadListenerWithNotification(downloadRequest);

          // The downloadMonitor will trigger the DownloadListener.
          downloadMonitorOptional
              .get()
              .addDownloadListener(
                  downloadRequest.destinationFileUri(), downloadListenerWithNotification);

          // Create a ListenableFutureTask to delay starting the downloadFuture until we can add the
          // future to our map.
          ListenableFutureTask<Void> startTask = ListenableFutureTask.create(() -> null);
          ListenableFuture<Void> downloadFuture =
              PropagatedFutures.transformAsync(
                  startTask, unused -> startDownload(downloadRequest), sequentialControlExecutor);

          PropagatedFutures.addCallback(
              downloadFuture,
              new FutureCallback<Void>() {
                @Override
                public void onSuccess(Void result) {
                  // Currently the MobStore monitor does not support onSuccess so we have to add
                  // callback to the download future here.

                  PropagatedFutures.addCallback(
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

          return PropagatedFutures.transformAsync(
              foregroundDownloadFutureMap.add(foregroundDownloadKey.toString(), downloadFuture),
              unused -> {
                // Now that the download future is added, start the task and return the future
                startTask.run();
                return downloadFuture;
              },
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  // Assertion: foregroundDownloadService and downloadMonitor are present
  private DownloadListener createDownloadListenerWithNotification(DownloadRequest downloadRequest) {
    String networkPausedMessage =
        downloadRequest.downloadConstraints().requireUnmeteredNetwork()
            ? NotificationUtil.getDownloadPausedWifiMessage(context)
            : NotificationUtil.getDownloadPausedMessage(context);

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    NotificationCompat.Builder notification =
        NotificationUtil.createNotificationBuilder(
            context,
            downloadRequest.fileSizeBytes(),
            downloadRequest.notificationContentTitle(),
            downloadRequest.notificationContentTextOptional().or(downloadRequest.urlToDownload()));

    ForegroundDownloadKey foregroundDownloadKey =
        ForegroundDownloadKey.ofSingleFile(downloadRequest.destinationFileUri());

    int notificationKey = NotificationUtil.notificationKeyForKey(foregroundDownloadKey.toString());

    // Attach the Cancel action to the notification.
    NotificationUtil.createCancelAction(
        context,
        foregroundDownloadServiceClassOptional.get(),
        foregroundDownloadKey.toString(),
        notification,
        notificationKey);
    notificationManager.notify(notificationKey, notification.build());

    return new DownloadListener() {
      @Override
      public void onProgress(long currentSize) {
        // TODO(b/229123693): return this future once DownloadListener has an async api.
        ListenableFuture<?> unused =
            PropagatedFutures.transformAsync(
                foregroundDownloadFutureMap.containsKey(foregroundDownloadKey.toString()),
                futureInProgress -> {
                  if (futureInProgress) {
                    notification
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setContentText(
                            downloadRequest
                                .notificationContentTextOptional()
                                .or(downloadRequest.urlToDownload()))
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setProgress(
                            downloadRequest.fileSizeBytes(),
                            (int) currentSize,
                            /* indeterminate= */ downloadRequest.fileSizeBytes() <= 0);
                    notificationManager.notify(notificationKey, notification.build());
                  }
                  if (downloadRequest.listenerOptional().isPresent()) {
                    downloadRequest.listenerOptional().get().onProgress(currentSize);
                  }
                  return immediateVoidFuture();
                },
                sequentialControlExecutor);
      }

      @Override
      public void onPausedForConnectivity() {
        // TODO(b/229123693): return this future once DownloadListener has an async api.
        ListenableFuture<?> unused =
            PropagatedFutures.transformAsync(
                foregroundDownloadFutureMap.containsKey(foregroundDownloadKey.toString()),
                futureInProgress -> {
                  if (futureInProgress) {
                    notification
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentText(networkPausedMessage)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOngoing(true)
                        // hide progress bar.
                        .setProgress(0, 0, false);
                    notificationManager.notify(notificationKey, notification.build());
                  }
                  if (downloadRequest.listenerOptional().isPresent()) {
                    downloadRequest.listenerOptional().get().onPausedForConnectivity();
                  }
                  return immediateVoidFuture();
                },
                sequentialControlExecutor);
      }

      @Override
      public ListenableFuture<Void> onComplete() {
        // We want to keep the Foreground Download Service alive until client's onComplete finishes.
        ListenableFuture<Void> clientOnCompleteFuture =
            downloadRequest.listenerOptional().isPresent()
                ? downloadRequest.listenerOptional().get().onComplete()
                : immediateVoidFuture();

        // Logic to shutdown Foreground Download Service after the client's provided onComplete
        // finished
        return PropagatedFluentFuture.from(clientOnCompleteFuture)
            .transformAsync(
                unused -> {
                  // onComplete succeeded, show a success message
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
                        context, foregroundDownloadKey.toString());
                  }
                  return immediateVoidFuture();
                },
                sequentialControlExecutor)
            .catchingAsync(
                Exception.class,
                e -> {
                  LogUtil.w(
                      e,
                      "%s: Delegate onComplete failed for uri: %s, showing failure notification.",
                      TAG,
                      downloadRequest.destinationFileUri());
                  notification.mActions.clear();

                  if (downloadRequest.showDownloadedNotification()) {
                    notification
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentText(NotificationUtil.getDownloadFailedMessage(context))
                        .setOngoing(false)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        // hide progress bar.
                        .setProgress(0, 0, false);

                    notificationManager.notify(notificationKey, notification.build());
                  } else {
                    NotificationUtil.cancelNotificationForKey(
                        context, downloadRequest.destinationFileUri().toString());
                  }

                  return immediateVoidFuture();
                },
                sequentialControlExecutor)
            .transformAsync(
                unused -> {
                  // After success or failure notification is shown, clean up
                  downloadMonitorOptional
                      .get()
                      .removeDownloadListener(downloadRequest.destinationFileUri());

                  return foregroundDownloadFutureMap.remove(foregroundDownloadKey.toString());
                },
                sequentialControlExecutor);
      }

      @Override
      public void onFailure(Throwable t) {
        // TODO(b/229123693): return this future once DownloadListener has an async api.
        ListenableFuture<?> unused =
            PropagatedFutures.submitAsync(
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

                  if (downloadRequest.listenerOptional().isPresent()) {
                    downloadRequest.listenerOptional().get().onFailure(t);
                  }
                  downloadMonitorOptional
                      .get()
                      .removeDownloadListener(downloadRequest.destinationFileUri());

                  return foregroundDownloadFutureMap.remove(foregroundDownloadKey.toString());
                },
                sequentialControlExecutor);
      }
    };
  }

  @Override
  public void cancelForegroundDownload(String downloadKey) {
    LogUtil.d("%s: CancelForegroundDownload for Uri = %s", TAG, downloadKey);
    ListenableFuture<?> unused =
        PropagatedFutures.transformAsync(
            getInProgressDownloadFuture(downloadKey),
            downloadFuture -> {
              if (downloadFuture.isPresent()) {
                LogUtil.v(
                    "%s: CancelForegroundDownload future found for key = %s, cancelling...",
                    TAG, downloadKey);
                downloadFuture.get().cancel(false);
              }
              return immediateVoidFuture();
            },
            sequentialControlExecutor);
  }

  private ListenableFuture<Optional<ListenableFuture<Void>>> getInProgressDownloadFuture(
      String key) {
    return PropagatedFutures.transformAsync(
        foregroundDownloadFutureMap.containsKey(key),
        isInForeground ->
            isInForeground ? foregroundDownloadFutureMap.get(key) : downloadFutureMap.get(key),
        sequentialControlExecutor);
  }

  private static DownloadFutureMap.StateChangeCallbacks createCallbacksForForegroundService(
      Context context, Optional<Class<?>> foregroundDownloadServiceClassOptional) {
    return new DownloadFutureMap.StateChangeCallbacks() {
      @Override
      public void onAdd(String key, int newSize) {
        // Only start foreground service if this is the first future we are adding.
        if (newSize == 1 && foregroundDownloadServiceClassOptional.isPresent()) {
          NotificationUtil.startForegroundDownloadService(
              context, foregroundDownloadServiceClassOptional.get(), key);
        }
      }

      @Override
      public void onRemove(String key, int newSize) {
        // Only stop foreground service if there are no more futures remaining.
        if (newSize == 0 && foregroundDownloadServiceClassOptional.isPresent()) {
          NotificationUtil.stopForegroundDownloadService(
              context, foregroundDownloadServiceClassOptional.get(), key);
        }
      }
    };
  }
}
