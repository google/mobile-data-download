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
package com.google.android.libraries.mobiledatadownload.monitor;

import android.net.Uri;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.DownloadListener;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.file.monitors.ByteCountingOutputMonitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.lite.SingleFileDownloadProgressMonitor;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.HashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A Download Progress Monitor to support {@link DownloadListener}.
 *
 * <p>Before monitoring an Uri, one needs to call monitorUri to record the file group that Uri
 * belongs to at the downloading time.
 *
 * <p>Currently we only support 1 DownloadListener per File Group.
 */
@ThreadSafe
public class DownloadProgressMonitor implements Monitor, SingleFileDownloadProgressMonitor {

  private static final String TAG = "DownloadProgressMonitor";

  private final TimeSource timeSource;
  private final Executor sequentialControlExecutor;
  private final com.google.android.libraries.mobiledatadownload.lite.DownloadProgressMonitor
      liteDownloadProgressMonitor;

  public DownloadProgressMonitor(TimeSource timeSource, Executor controlExecutor) {
    this.timeSource = timeSource;

    // We want onProgress to be executed in order otherwise clients will observe out of order
    // updates (bigger current size update appears before smaller current size update).
    // We use Sequential Executor to ensure the onProgress will be processed sequentially.
    this.sequentialControlExecutor = MoreExecutors.newSequentialExecutor(controlExecutor);

    // Construct internal instance of MDD Lite's DownloadProgressMonitor. methods of
    // SingleFileDownloadProgressMonitor will delegate to this instance.
    this.liteDownloadProgressMonitor =
        com.google.android.libraries.mobiledatadownload.lite.DownloadProgressMonitor.create(
            this.timeSource, controlExecutor);
  }

  // We will only broadcast on progress notification at most once in this time frame.
  // Currently MobStore Monitor notify every 8KB of downloaded bytes. This may be too chatty on
  // fast network.
  // 1 second was chosen arbitrarily.
  @VisibleForTesting static final long LOG_FREQUENCY = 1000L;

  // MobStore Monitor works at file level. We want to monitor at FileGroup level. This map will
  // help to map from a fileUri to a file group.
  @GuardedBy("DownloadProgressMonitor.class")
  private final HashMap<Uri, String> uriToFileGroup = new HashMap<>();

  @GuardedBy("DownloadProgressMonitor.class")
  private final HashMap<String, ByteCountingOutputMonitor> fileGroupToByteCountingOutputMonitor =
      new HashMap<>();

  @Override
  @Nullable
  public Monitor.InputMonitor monitorRead(Uri uri) {
    return null;
  }

  @Override
  @Nullable
  public Monitor.OutputMonitor monitorWrite(Uri uri) {
    synchronized (DownloadProgressMonitor.class) {
      String groupName = uriToFileGroup.get(uri);
      if (groupName == null) {
        // MobStore will call all monitors for all files it handles.
        // In this case, we receive a call from MobStore for an Uri that we did not register for.
        //
        // This may be for a single file download, so delegate to liteDownloadProgressMonitor. This
        // will check its internal map and return null if not found.
        return liteDownloadProgressMonitor.monitorWrite(uri);
      }

      if (fileGroupToByteCountingOutputMonitor.get(groupName) == null) {
        // This is a real error. We register for this Uri but does ot have a counter for it.
        LogUtil.e("%s: Can't find file group for uri: %s", TAG, uri);
        return null;
      }
      return fileGroupToByteCountingOutputMonitor.get(groupName);
    }
  }

  @Override
  @Nullable
  public Monitor.OutputMonitor monitorAppend(Uri uri) {
    return monitorWrite(uri);
  }

  public void pausedForConnectivity() {
    synchronized (DownloadProgressMonitor.class) {
      for (ByteCountingOutputMonitor byteCountingOutputMonitor :
          fileGroupToByteCountingOutputMonitor.values()) {
        DownloadedBytesCounter counter =
            (DownloadedBytesCounter) byteCountingOutputMonitor.getCounter();
        counter.pausedForConnectivity();
      }

      // Delegate to liteDownloadProgressMonitor as well so single file downloads can be updated
      liteDownloadProgressMonitor.pausedForConnectivity();
    }
  }

  /**
   * Add a File Group DownloadListener that client can use to receive download progress update.
   *
   * <p>Currently we only support 1 listener per file group. Calling addDownloadListener to add
   * another listener would be no-op.
   *
   * @param groupName The groupName that the DownloadListener will receive download progress update.
   * @param downloadListener the DownloadListener to add.
   */
  public void addDownloadListener(String groupName, DownloadListener downloadListener) {
    synchronized (DownloadProgressMonitor.class) {
      if (!fileGroupToByteCountingOutputMonitor.containsKey(groupName)) {
        fileGroupToByteCountingOutputMonitor.put(
            groupName,
            new ByteCountingOutputMonitor(
                new DownloadedBytesCounter(groupName, downloadListener),
                timeSource::currentTimeMillis,
                LOG_FREQUENCY,
                TimeUnit.MILLISECONDS));
      }
    }
  }

  /**
   * Add a Single File DownloadListener.
   *
   * <p>This listener allows clients to receive on progress updates for single file downloads.
   *
   * @param uri the uri for which the DownloadListener should receive updates
   * @param downloadListener the MDD Lite DownloadListener to add
   */
  @Override
  public void addDownloadListener(
      Uri uri,
      com.google.android.libraries.mobiledatadownload.lite.DownloadListener downloadListener) {
    liteDownloadProgressMonitor.addDownloadListener(uri, downloadListener);
  }

  /**
   * Remove a File Group DownloadListener.
   *
   * @param groupName The groupName that the DownloadListener receive download progress update.
   */
  public void removeDownloadListener(String groupName) {
    synchronized (DownloadProgressMonitor.class) {
      fileGroupToByteCountingOutputMonitor.remove(groupName);
    }
  }

  /**
   * Remove a Single File DownloadListener.
   *
   * @param uri the uri which should be cleared of any registered DownloadListener
   */
  @Override
  public void removeDownloadListener(Uri uri) {
    liteDownloadProgressMonitor.removeDownloadListener(uri);
  }

  /**
   * Record that the Uri belong to the FileGroup represented by groupName. We need to record this at
   * downloading time since the files in FileGroup could change due to config change. monitorUri
   * should only be called for files that have not been downloaded.
   *
   * @param uri the FileUri to be monitored.
   * @param groupName the group name to be monitored.
   */
  public void monitorUri(Uri uri, String groupName) {
    synchronized (DownloadProgressMonitor.class) {
      uriToFileGroup.put(uri, groupName);
    }
  }

  /**
   * Add the current size of a downloaded or partially downloaded file to a file group counter.
   *
   * @param groupName the group name to add the current size.
   * @param currentSize the current size of the file.
   */
  public void notifyCurrentFileSize(String groupName, long currentSize) {
    synchronized (DownloadProgressMonitor.class) {
      // Update the counter with the current size.
      if (fileGroupToByteCountingOutputMonitor.containsKey(groupName)) {
        fileGroupToByteCountingOutputMonitor
            .get(groupName)
            .getCounter()
            .bufferCounter((int) currentSize);
      }
    }
  }

  // A counter for bytes downloaded.
  private final class DownloadedBytesCounter implements ByteCountingOutputMonitor.Counter {
    private final String groupName;
    private final DownloadListener downloadListener;

    private final AtomicLong byteCounter = new AtomicLong();

    DownloadedBytesCounter(String groupName, DownloadListener downloadListener) {
      this.groupName = groupName;
      this.downloadListener = downloadListener;
    }

    @Override
    public void bufferCounter(int len) {
      byteCounter.getAndAdd(len);
      LogUtil.v(
          "%s: Received data for groupName = %s, len = %d, Counter = %d",
          TAG, groupName, len, byteCounter.get());
    }

    @Override
    public void flushCounter() {
      // Check if the DownloadListener is still being used before calling its onProgress.
      synchronized (DownloadProgressMonitor.class) {
        if (fileGroupToByteCountingOutputMonitor.containsKey(groupName)) {
          sequentialControlExecutor.execute(() -> downloadListener.onProgress(byteCounter.get()));
        }
      }
    }

    public void pausedForConnectivity() {
      downloadListener.pausedForConnectivity();
    }
  }
}
