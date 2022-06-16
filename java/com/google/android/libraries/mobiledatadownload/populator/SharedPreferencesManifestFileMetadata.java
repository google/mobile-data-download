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
package com.google.android.libraries.mobiledatadownload.populator;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.mobiledatadownload.populator.MetadataProto.ManifestFileBookkeeping;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.util.concurrent.Executor;

/** ManifestFileMetadataStore based on SharedPreferences. */
public final class SharedPreferencesManifestFileMetadata implements ManifestFileMetadataStore {

  private static final String SHARED_PREFS_NAME = "ManifestFileMetadata";

  private final Object lock = new Object();

  private final Supplier<SharedPreferences> sharedPrefs;
  private final Executor backgroundExecutor;

  /**
   * @param sharedPrefs may be called multiple times, so memoization is recommended
   */
  public static SharedPreferencesManifestFileMetadata create(
      Supplier<SharedPreferences> sharedPrefs, Executor backgroundExecutor) {
    return new SharedPreferencesManifestFileMetadata(sharedPrefs, backgroundExecutor);
  }

  public static SharedPreferencesManifestFileMetadata createFromContext(
      Context context, Optional<String> instanceIdOptional, Executor backgroundExecutor) {
    // Avoid calling getSharedPreferences on the main thread.
    Supplier<SharedPreferences> sharedPrefs =
        Suppliers.memoize(
            () ->
                SharedPreferencesUtil.getSharedPreferences(
                    context, SHARED_PREFS_NAME, instanceIdOptional));
    return new SharedPreferencesManifestFileMetadata(sharedPrefs, backgroundExecutor);
  }

  private SharedPreferencesManifestFileMetadata(
      Supplier<SharedPreferences> sharedPrefs, Executor backgroundExecutor) {
    this.sharedPrefs = sharedPrefs;
    this.backgroundExecutor = backgroundExecutor;
  }

  @Override
  public ListenableFuture<Optional<ManifestFileBookkeeping>> read(String manifestId) {
    return PropagatedFutures.submit(
        () -> {
          synchronized (lock) {
            ManifestFileBookkeeping proto =
                SharedPreferencesUtil.readProto(
                    sharedPrefs.get(), manifestId, ManifestFileBookkeeping.parser());
            return Optional.fromNullable(proto);
          }
        },
        backgroundExecutor);
  }

  @Override
  public ListenableFuture<Void> upsert(String manifestId, ManifestFileBookkeeping value) {
    return PropagatedFutures.submit(
        () -> {
          synchronized (lock) {
            SharedPreferences.Editor editor = sharedPrefs.get().edit();
            SharedPreferencesUtil.writeProto(editor, manifestId, value);
            if (!editor.commit()) {
              throw new IOException("Failed to commit");
            }
            return null; // for Callable
          }
        },
        backgroundExecutor);
  }
}
