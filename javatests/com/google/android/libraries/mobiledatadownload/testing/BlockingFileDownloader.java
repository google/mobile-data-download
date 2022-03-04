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
package com.google.android.libraries.mobiledatadownload.testing;

import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.common.base.Optional;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.concurrent.CountDownLatch;

/**
 * File Downloader that allows control over how long the download takes.
 *
 * <p>An optional delegate FileDownloader can be provided to perform downloading while maintaining
 * control over the download state.
 *
 * <h3>The states of a download</h3>
 *
 * <p>The following "states" are defined and using the BlockingFileDownloader will allow the
 * download to be paused so assertions can be made:
 *
 * <ol>
 *   <li>Idle - The download has not started yet.
 *   <li>In Progress - The download has started, but has not completed yet.
 *   <li>Finishing* - The download is finishing (Only applicable when delegate is provided).
 *   <li>Finished - The download has finished.
 * </ol>
 *
 * <h5>The "Finishing" state</h5>
 *
 * <p>The Finishing state is a special state only applicable when a delegate FileDownloader is
 * provided. The Finishing state can be considered most like the In Progress state, the main
 * distinction being that no action is being performed during the In Progress state, but the
 * delegate is running during the Finishing state.
 *
 * <p><em>Why not just run the delegate during In Progress?</em>
 *
 * <p>Because the delgate could be performing actual work (i.e. network calls, I/O), there could be
 * some variability introduced that causes test assertions to become flaky. The In Progress is
 * reserved to effectively be a Frozen state that ensure no work is being done. This ensures that
 * test assertions remain consistent.
 *
 * <h5>Controlling the states</h5>
 *
 * <p>After creating an instance of BlockingFileDownloader, the following example shows how the
 * BlockingFileDownloader can control the state as well as when assertions can be made.
 *
 * <pre>{@code
 * // Before the call to MDD's downloadFileGroup, the state is considered "Idle" and assertions
 * // can be made at this time.
 *
 * mdd.downloadFileGroup(...);
 *
 * // After calling downloadFileGroup, the state is in a transition period from "Idle" to
 * // "In Progress." assertions should not be made during this time.
 *
 * blockingFileDownloader.waitForDownloadStarted();
 *
 * // The above method blocks until the "In Progress" state has been reached. Assertions can be made
 * // after this call for the "In Progress" state. If no assertions need to be made during this
 * // state, the above call can be skipped.
 *
 * blockingFileDownloader.finishDownloading();
 *
 * // The above method moves the state from "In Progress" to "Finishing" (if a delegate is provided)
 * // or "Finished" (if no delegate is provided). This is another transition period, so assertions
 * // should not be made during this time.
 *
 * blockingFileDownloader.waitForDownloadCompleted();
 *
 * // The above method ensures the state has changed from "In Progress"/"Finishing" to "Finished."
 * // After this point, assertions can be made safely again.
 * //
 * // Optionally, the future returned from mdd.downloadFileGroup can be awaited instead, since that
 * // also ensures the download has completed.
 * }</pre>
 */
public final class BlockingFileDownloader implements FileDownloader {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final ListeningExecutorService downloadExecutor;
  private final Optional<FileDownloader> delegateFileDownloaderOptional;

  private CountDownLatch downloadInProgressLatch = new CountDownLatch(1);
  private CountDownLatch downloadFinishingLatch = new CountDownLatch(1);
  private CountDownLatch delegateInProgressLatch = new CountDownLatch(1);
  private CountDownLatch downloadFinishedLatch = new CountDownLatch(1);

  public BlockingFileDownloader(ListeningExecutorService downloadExecutor) {
    this.downloadExecutor = downloadExecutor;
    this.delegateFileDownloaderOptional = Optional.absent();
  }

  public BlockingFileDownloader(
      ListeningExecutorService downloadExecutor, FileDownloader delegateFileDownloader) {
    this.downloadExecutor = downloadExecutor;
    this.delegateFileDownloaderOptional = Optional.of(delegateFileDownloader);
  }

  @Override
  public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
    ListenableFuture<Void> downloadFuture =
        Futures.submitAsync(
            () -> {
              logger.atInfo().log("Download Started, state changed to: In Progress");
              downloadInProgressLatch.countDown();

              logger.atInfo().log("Waiting for download to continue");
              downloadFinishingLatch.await();

              ListenableFuture<Void> result;
              if (delegateFileDownloaderOptional.isPresent()) {
                logger.atInfo().log("Download State Changed to: Finishing (delegate in progress)");
                // Delegate was provided, so perform its download
                result = delegateFileDownloaderOptional.get().startDownloading(downloadRequest);
                delegateInProgressLatch.countDown();
              } else {
                result = Futures.immediateVoidFuture();
              }

              return result;
            },
            downloadExecutor);

    // Add a callback to ensure the state transitions from "In Progress"/"Finishing" to "Finished."
    Futures.addCallback(
        downloadFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void unused) {
            BlockingFileDownloader.logger.atInfo().log("Download State Changed to: Finished");
            downloadFinishedLatch.countDown();
          }

          @Override
          public void onFailure(Throwable t) {
            BlockingFileDownloader.logger
                .atSevere()
                .withCause(t)
                .log("Download State Changed to: Finished (with error)");
            downloadFinishedLatch.countDown();
          }
        },
        downloadExecutor);

    return downloadFuture;
  }

  /** Blocks the caller thread until the download has moved to the "In Progress" state. */
  public void waitForDownloadStarted() throws InterruptedException {
    downloadInProgressLatch.await();
  }

  /** Blocks the caller thread until the download has moved to the "Finishing" state. */
  public void waitForDownloadCompleted() throws InterruptedException {
    downloadFinishedLatch.await();
  }

  /**
   * Blocks the caller thread until the delegate downloader has been invoked. This method will never
   * complete if no delegate was provided.
   */
  public void waitForDelegateStarted() throws InterruptedException {
    delegateInProgressLatch.await();
  }

  /**
   * Finishes the current download lifecycle.
   *
   * <p>The in progress latch is triggered if it hasn't yet been triggered to prevent a deadlock
   * from occurring.
   */
  public void finishDownloading() {
    if (downloadInProgressLatch.getCount() > 0) {
      downloadInProgressLatch.countDown();
    }
    downloadFinishingLatch.countDown();
  }

  /**
   * Resets the current state of the download lifecycle.
   *
   * <p>An existing download cycle is finished before resetting the state to prevent a deadlock from
   * occurring.
   */
  public void resetState() {
    // Force a finish download if it was previously in progress to prevent deadlock.
    if (downloadFinishedLatch.getCount() > 0) {
      finishDownloading();
    }
    downloadFinishedLatch.countDown();

    logger.atInfo().log("Reset State back to: Idle");
    downloadInProgressLatch = new CountDownLatch(1);
    downloadFinishingLatch = new CountDownLatch(1);
    downloadFinishedLatch = new CountDownLatch(1);
    delegateInProgressLatch = new CountDownLatch(1);
  }
}
