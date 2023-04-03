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
package com.google.android.libraries.mobiledatadownload.internal.util;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedExecutionSequencer;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Helper class to maintain the state of MDD download futures.
 *
 * <p>This follows a limited Map interface and uses {@link ExecutionSequencer} to ensure that all
 * operations on the map are synchronized.
 *
 * <p><b>NOTE:</b> This class is meant to be a container class for download futures and <em>should
 * not</em> include any download-specific logic. Its sole purpose is to maintain any in-progress
 * download futures in a synchronized manner. Download-specific logic should be implemented outside
 * of this class, and can rely on {@link StateChangeCallbacks} to respond to events from this map.
 */
public final class DownloadFutureMap<T> {
  private static final String TAG = "DownloadFutureMap";

  // ExecutionSequencer ensures that enqueued futures are executed sequentially (regardless of the
  // executor used). This allows us to keep critical state changes sequential.
  private final PropagatedExecutionSequencer futureSerializer =
      PropagatedExecutionSequencer.create();

  private final Executor sequentialControlExecutor;
  private final StateChangeCallbacks callbacks;

  // Underlying map to store futures -- synchronization of accesses/updates is handled by
  // ExecutionSequencer.
  @VisibleForTesting
  public final Map<String, ListenableFuture<T>> keyToDownloadFutureMap = new HashMap<>();

  private DownloadFutureMap(Executor sequentialControlExecutor, StateChangeCallbacks callbacks) {
    this.sequentialControlExecutor = sequentialControlExecutor;
    this.callbacks = callbacks;
  }

  /** Convenience creator when no callbacks should be registered. */
  public static <T> DownloadFutureMap<T> create(Executor sequentialControlExecutor) {
    return create(sequentialControlExecutor, new StateChangeCallbacks() {});
  }

  /** Creates a new instance of DownloadFutureMap. */
  public static <T> DownloadFutureMap<T> create(
      Executor sequentialControlExecutor, StateChangeCallbacks callbacks) {
    return new DownloadFutureMap<T>(sequentialControlExecutor, callbacks);
  }

  /** Callback to support custom events based on the state of the map. */
  public static interface StateChangeCallbacks {
    /** Respond to the event immediately before a new future is added to the map. */
    default void onAdd(String key, int newSize) throws Exception {}

    /** Respond to the event immediately after a future is removed from the map. */
    default void onRemove(String key, int newSize) throws Exception {}
  }

  public ListenableFuture<Void> add(String key, ListenableFuture<T> downloadFuture) {
    LogUtil.v("%s: submitting request to add in-progress download future with key: %s", TAG, key);
    return futureSerializer.submitAsync(
        () -> {
          try {
            callbacks.onAdd(key, keyToDownloadFutureMap.size() + 1);
            keyToDownloadFutureMap.put(key, downloadFuture);
          } catch (Exception e) {
            LogUtil.e(e, "%s: Failed to add download future (%s) to map", TAG, key);
            return immediateFailedFuture(e);
          }
          return immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  public ListenableFuture<Void> remove(String key) {
    LogUtil.v(
        "%s: submitting request to remove in-progress download future with key: %s", TAG, key);
    return futureSerializer.submitAsync(
        () -> {
          try {
            keyToDownloadFutureMap.remove(key);
            callbacks.onRemove(key, keyToDownloadFutureMap.size());
          } catch (Exception e) {
            LogUtil.e(e, "%s: Failed to remove download future (%s) from map", TAG, key);
            return immediateFailedFuture(e);
          }
          return immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  public ListenableFuture<Optional<ListenableFuture<T>>> get(String key) {
    LogUtil.v("%s: submitting request for in-progress download future with key: %s", TAG, key);
    return futureSerializer.submit(
        () -> Optional.fromNullable(keyToDownloadFutureMap.get(key)), sequentialControlExecutor);
  }

  public ListenableFuture<Boolean> containsKey(String key) {
    LogUtil.v("%s: submitting check for in-progress download future with key: %s", TAG, key);
    return futureSerializer.submit(
        () -> keyToDownloadFutureMap.containsKey(key), sequentialControlExecutor);
  }
}
