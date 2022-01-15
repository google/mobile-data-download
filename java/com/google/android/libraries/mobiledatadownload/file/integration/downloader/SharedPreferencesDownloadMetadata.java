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
package com.google.android.libraries.mobiledatadownload.file.integration.downloader;

import android.content.SharedPreferences;
import android.net.Uri;
import com.google.android.downloader.DownloadMetadata;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.io.IOException;

/** DownloadMetadataStore based on SharedPreferences. */
public final class SharedPreferencesDownloadMetadata implements DownloadMetadataStore {

  private final Object lock = new Object();

  private final SharedPreferences sharedPrefs;
  private final ListeningExecutorService backgroundExecutor;

  public SharedPreferencesDownloadMetadata(
      SharedPreferences sharedPrefs, ListeningExecutorService backgroundExecutor) {
    this.sharedPrefs = sharedPrefs;
    this.backgroundExecutor = backgroundExecutor;
  }

  /** Data fields for each URI persisted in SharedPreferences. */
  private enum Key {
    CONTENT_TAG("ct"),
    LAST_MODIFIED_TIME_SECS("lmts");

    final String sharedPrefsSuffix;

    Key(String sharedPrefsSuffix) {
      this.sharedPrefsSuffix = sharedPrefsSuffix;
    }
  }

  private static final String SPLIT_CHAR = "|";

  /** Returns the SharedPreferences key used to store the given data field for {@code uri}. */
  private static String getKey(Uri uri, Key key) {
    return uri + SPLIT_CHAR + key.sharedPrefsSuffix;
  }

  @Override
  public ListenableFuture<Optional<DownloadMetadata>> read(Uri uri) {
    return backgroundExecutor.submit(
        () -> {
          synchronized (lock) {
            // Checking for CONTENT_TAG is sufficient since we will always store both the content
            // tag and last modified timestamp in the same commit.
            if (!sharedPrefs.contains(getKey(uri, Key.CONTENT_TAG))) {
              return Optional.absent();
            }

            String contentTag = sharedPrefs.getString(getKey(uri, Key.CONTENT_TAG), "");
            long lastModifiedTimeSeconds =
                sharedPrefs.getLong(getKey(uri, Key.LAST_MODIFIED_TIME_SECS), 0);
            return Optional.of(DownloadMetadata.create(contentTag, lastModifiedTimeSeconds));
          }
        });
  }

  @Override
  public ListenableFuture<Void> upsert(Uri uri, DownloadMetadata metadata) {
    return backgroundExecutor.submit(
        () -> {
          synchronized (lock) {
            SharedPreferences.Editor editor = sharedPrefs.edit();
            editor.putString(getKey(uri, Key.CONTENT_TAG), metadata.getContentTag());
            editor.putLong(
                getKey(uri, Key.LAST_MODIFIED_TIME_SECS), metadata.getLastModifiedTimeSeconds());
            commitOrThrow(editor);

            return null;
          }
        });
  }

  @Override
  public ListenableFuture<Void> delete(Uri uri) {
    return backgroundExecutor.submit(
        () -> {
          synchronized (lock) {
            SharedPreferences.Editor editor = sharedPrefs.edit();
            editor.remove(getKey(uri, Key.CONTENT_TAG));
            editor.remove(getKey(uri, Key.LAST_MODIFIED_TIME_SECS));
            commitOrThrow(editor);

            return null;
          }
        });
  }

  /** Calls {@code editor.commit()} and throws IOException if the commit failed. */
  private static void commitOrThrow(SharedPreferences.Editor editor) throws IOException {
    if (!editor.commit()) {
      throw new IOException("Failed to commit");
    }
  }
}
