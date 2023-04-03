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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import androidx.annotation.GuardedBy;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.file.monitors.ByteCountingOutputMonitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.FileGroupLoggingState;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * A network usage monitor that counts bytes downloaded for each FileGroup. Before monitoring an
 * Uri, one needs to call monitorUri to record the file group that Uri belongs to at the downloading
 * time. Failing to do so will result in no network usage being counted.
 */
public class NetworkUsageMonitor implements Monitor {
  private static final String TAG = "NetworkUsageMonitor";

  // We will only flush counters to SharedPreference at most once in this time frame.
  // 10 seconds were chosen arbitrarily.
  @VisibleForTesting static final long LOG_FREQUENCY_SECONDS = 10L;

  private final TimeSource timeSource;
  private final Context context;

  private final Object lock = new Object();

  // Key is FileGroupLoggingState with GroupKey, build id, version number populated.
  @GuardedBy("lock")
  private final HashMap<FileGroupLoggingState, ByteCountingOutputMonitor>
      fileGroupLoggingStateToOutputMonitor = new HashMap<>();

  @GuardedBy("lock")
  private final HashMap<Uri, ByteCountingOutputMonitor> uriToOutputMonitor = new HashMap<>();

  public NetworkUsageMonitor(Context context, TimeSource timeSource) {
    this.context = context;
    this.timeSource = timeSource;
  }

  @Override
  @Nullable
  public Monitor.InputMonitor monitorRead(Uri uri) {
    return null;
  }

  @Override
  @Nullable
  public Monitor.OutputMonitor monitorWrite(Uri uri) {
    synchronized (lock) {
      return uriToOutputMonitor.get(uri);
    }
  }

  @Override
  @Nullable
  public Monitor.OutputMonitor monitorAppend(Uri uri) {
    return monitorWrite(uri);
  }

  /**
   * Record that the Uri belong to the FileGroup represented by ownerPackage and groupName. We need
   * to record this at downloading time since the files in FileGroup could change due to config
   * change.
   *
   * @param uri The Uri of the data file.
   * @param groupKey The groupKey part of the file group.
   * @param buildId The build id of the file group.
   * @param variantId The variant id of the file group.
   * @param versionNumber The version number of the file group.
   * @param loggingStateStore The storage for the network usage logs
   */
  public void monitorUri(
      Uri uri,
      GroupKey groupKey,
      long buildId,
      String variantId,
      int versionNumber,
      LoggingStateStore loggingStateStore) {
    FileGroupLoggingState fileGroupLoggingStateKey =
        FileGroupLoggingState.newBuilder()
            .setGroupKey(groupKey)
            .setBuildId(buildId)
            .setVariantId(variantId)
            .setFileGroupVersionNumber(versionNumber)
            .build();

    // If we haven't seen this file group, create a output monitor for it and register it to this
    // file group key.
    synchronized (lock) {
      if (!fileGroupLoggingStateToOutputMonitor.containsKey(fileGroupLoggingStateKey)) {
        fileGroupLoggingStateToOutputMonitor.put(
            fileGroupLoggingStateKey,
            new ByteCountingOutputMonitor(
                new DownloadedBytesCounter(context, loggingStateStore, fileGroupLoggingStateKey),
                timeSource::currentTimeMillis,
                LOG_FREQUENCY_SECONDS,
                SECONDS));
      }

      // Register the mapping from this uri to the output monitor we created.
      // NOTE: It's possible the URI is associated with another monitor here (e.g. if a
      // uri is shared by file groups), but we don't have a way to dedupe that at the moment, so we
      // just overwrite it.
      uriToOutputMonitor.put(
          uri, fileGroupLoggingStateToOutputMonitor.get(fileGroupLoggingStateKey));
    }
  }

  // A counter for bytes downloaded on wifi and cellular.
  // It will keep in-memory counters to reduce the write to SharedPreference.
  // When updating the in-memory counters, it will only save and reset them if the time since the
  // last save is at least LOG_FREQUENCY.
  private static final class DownloadedBytesCounter implements ByteCountingOutputMonitor.Counter {
    private final Context context;
    private final LoggingStateStore loggingStateStore;
    private final FileGroupLoggingState fileGroupLoggingStateKey;

    // In-memory counters before saving to SharedPreference.
    private final AtomicLong wifiCounter = new AtomicLong();
    private final AtomicLong cellularCounter = new AtomicLong();

    DownloadedBytesCounter(
        Context context,
        LoggingStateStore loggingStateStore,
        FileGroupLoggingState fileGroupLoggingStateKey) {
      this.context = context;
      this.loggingStateStore = loggingStateStore;
      this.fileGroupLoggingStateKey = fileGroupLoggingStateKey;
    }

    @Override
    public void bufferCounter(int len) {

      boolean isCellular = isCellular(context);

      if (isCellular) {
        cellularCounter.getAndAdd(len);
      } else {
        wifiCounter.getAndAdd(len);
      }

      LogUtil.v(
          "%s: Received data (%s) for fileGroup = %s, len = %d, wifiCounter = %d,"
              + " cellularCounter = %d",
          TAG,
          isCellular ? "cellular" : "wifi",
          fileGroupLoggingStateKey.getGroupKey().getGroupName(),
          len,
          wifiCounter.get(),
          cellularCounter.get());
    }

    @Override
    public void flushCounter() {
      ListenableFuture<Void> incrementDataUsage =
          loggingStateStore.incrementDataUsage(
              fileGroupLoggingStateKey.toBuilder()
                  .setCellularUsage(cellularCounter.getAndSet(0))
                  .setWifiUsage(wifiCounter.getAndSet(0))
                  .build());

      PropagatedFutures.addCallback(
          incrementDataUsage,
          new FutureCallback<Void>() {
            @Override
            public void onSuccess(Void unused) {
              LogUtil.d(
                  "%s: Successfully incremented LoggingStateStore network usage for %s",
                  TAG, fileGroupLoggingStateKey.getGroupKey().getGroupName());
            }

            @Override
            public void onFailure(Throwable t) {
              LogUtil.e(
                  t,
                  "%s: Unable to increment LoggingStateStore network usage for %s",
                  TAG,
                  fileGroupLoggingStateKey.getGroupKey().getGroupName());
            }
          },
          directExecutor());
    }
  }

  @Nullable
  public static ConnectivityManager getConnectivityManager(Context context) {
    try {
      return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    } catch (SecurityException e) {
      LogUtil.e("%s: Couldn't retrieve ConnectivityManager.", TAG);
    }
    return null;
  }

  @Nullable
  public static NetworkInfo getActiveNetworkInfo(Context context) {
    ConnectivityManager cm = getConnectivityManager(context);
    return (cm == null) ? null : cm.getActiveNetworkInfo();
  }

  /**
   * Returns true if the current network connectivity type is cellular. If the network connectivity
   * type is not cellular or if there is an error in getting NetworkInfo return false.
   *
   * <p>The logic here is similar to OffroadDownloader.
   */
  @VisibleForTesting
  public static boolean isCellular(Context context) {
    NetworkInfo networkInfo = getActiveNetworkInfo(context);
    if (networkInfo == null) {
      LogUtil.e("%s: Fail to get network type ", TAG);
      // We return false when we fail to get NetworkInfo.
      return false;
    }

    if ((networkInfo.getType() == ConnectivityManager.TYPE_WIFI
        || networkInfo.getType() == ConnectivityManager.TYPE_ETHERNET
        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            && networkInfo.getType() == ConnectivityManager.TYPE_VPN))) {
      return false;
    } else {
      return true;
    }
  }
}
