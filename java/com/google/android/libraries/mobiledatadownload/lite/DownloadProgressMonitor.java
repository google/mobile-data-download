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
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/** A Download Progress Monitor to support {@link DownloadListener}. */
@ThreadSafe
public class DownloadProgressMonitor implements Monitor, SingleFileDownloadProgressMonitor {

  private static final String TAG = "DownloadProgressMonitor";

  private final TimeSource timeSource;
  private final Executor sequentialControlExecutor;

  // NOTE: GuardRails prohibits multiple public constructors
  private DownloadProgressMonitor(TimeSource timeSource, Executor controlExecutor) {
    this.timeSource = timeSource;

    // We want onProgress to be executed in order otherwise clients will observe out of order
    // updates (bigger current size update appears before smaller current size update).
    // We use Sequential Executor to ensure the onProgress will be processed sequentially.
    this.sequentialControlExecutor = MoreExecutors.newSequentialExecutor(controlExecutor);
  }

  /** Constructor overload with {@link TimeSource}. */
  // NOTE: this is necessary for use by other MDD components.
  public static DownloadProgressMonitor create(TimeSource timeSource, Executor controlExecutor) {
    return new DownloadProgressMonitor(timeSource, controlExecutor);
  }

  // We will only broadcast on progress notification at most once in this time frame.
  // Currently MobStore Monitor notify every 8KB of downloaded bytes. This may be too chatty on
  // fast network.
  // 1000 was chosen arbitrarily.
  @VisibleForTesting static final long BUFFERED_TIME_MS = 1000;

  @GuardedBy("DownloadProgressMonitor.class")
  private final HashMap<Uri, DownloadedBytesCounter> uriToDownloadedBytesCounter = new HashMap<>();

  @Override
  @Nullable
  public Monitor.InputMonitor monitorRead(Uri uri) {
    return null;
  }

  @Override
  @Nullable
  public Monitor.OutputMonitor monitorWrite(Uri uri) {
    synchronized (DownloadProgressMonitor.class) {
      if (uriToDownloadedBytesCounter.get(uri) == null) {
        // All monitors for a shared FileStorage will be invoked for all file accesses through this
        // shared FileStorage. So this monitor can receive non-MDD-Lite Uri.
        return null;
      }
      return uriToDownloadedBytesCounter.get(uri);
    }
  }

  @Override
  @Nullable
  public Monitor.OutputMonitor monitorAppend(Uri uri) {
    return monitorWrite(uri);
  }

  public void pausedForConnectivity() {
    synchronized (DownloadProgressMonitor.class) {
      for (DownloadedBytesCounter downloadedBytesCounter : uriToDownloadedBytesCounter.values()) {
        downloadedBytesCounter.pausedForConnectivity();
      }
    }
  }

  @Override
  public void addDownloadListener(Uri uri, DownloadListener downloadListener) {
    synchronized (DownloadProgressMonitor.class) {
      if (!uriToDownloadedBytesCounter.containsKey(uri)) {
        uriToDownloadedBytesCounter.put(uri, new DownloadedBytesCounter(uri, downloadListener));
      }
    }
  }

  @Override
  public void removeDownloadListener(Uri uri) {
    synchronized (DownloadProgressMonitor.class) {
      uriToDownloadedBytesCounter.remove(uri);
    }
  }

  // A counter for bytes downloaded.
  private final class DownloadedBytesCounter implements Monitor.OutputMonitor {
    private final Uri uri;
    private final DownloadListener downloadListener;

    private final AtomicLong byteCounter = new AtomicLong();

    // Last timestamp that we broadcast on progress.
    private long lastBroadcastOnProgressTimestampMs;

    DownloadedBytesCounter(Uri uri, DownloadListener downloadListener) {
      this.uri = uri;
      this.downloadListener = downloadListener;
      lastBroadcastOnProgressTimestampMs = timeSource.currentTimeMillis();
    }

    @Override
    public void bytesWritten(byte[] b, int off, int len) {
      notifyProgress(len);
    }

    private void notifyProgress(long len) {
      // Only broadcast progress update every BUFFERED_TIME_MS.
      // It will be fast (no locking) when there is no need to broadcast progress.
      // When there is a need to broadcast progress, we need to obtain the lock due to 2 reasons:
      // 1- Concurrent access to uriToDownloadedBytesCounter.
      // 2- Prevent out of order progress update.
      if (timeSource.currentTimeMillis() - lastBroadcastOnProgressTimestampMs < BUFFERED_TIME_MS) {
        byteCounter.getAndAdd(len);
        LogUtil.v(
            "%s: Received data for uri = %s, len = %d, Counter = %d",
            TAG, uri, len, byteCounter.get());
      } else {
        synchronized (DownloadProgressMonitor.class) {
          // Reset timestamp.
          lastBroadcastOnProgressTimestampMs = timeSource.currentTimeMillis();

          byteCounter.getAndAdd(len);
          LogUtil.v(
              "%s: Received data for uri = %s, len = %d, Counter = %d",
              TAG, uri, len, byteCounter.get());

          if (uriToDownloadedBytesCounter.containsKey(uri)) {
            sequentialControlExecutor.execute(() -> downloadListener.onProgress(byteCounter.get()));
          }
        }
      }
    }

    public void pausedForConnectivity() {
      downloadListener.onPausedForConnectivity();
    }
  }
}
