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
package com.google.android.libraries.mobiledatadownload.downloader;

import android.net.Uri;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;

/**
 * A composite {@link FileDownloader} that delegates to specific registered FileDownloaders based on
 * URL scheme.
 */
public final class MultiSchemeFileDownloader implements FileDownloader {
  private static final String TAG = "MultiSchemeFileDownloader";

  /** Builder for {@link MultiSchemeFileDownloader}. */
  public static final class Builder {
    private final Map<String, FileDownloader> schemeToDownloader = new HashMap<>();

    /** Associates a url scheme (e.g. "http") with a specific {@link FileDownloader} delegate. */
    @CanIgnoreReturnValue
    public MultiSchemeFileDownloader.Builder addScheme(String scheme, FileDownloader downloader) {
      schemeToDownloader.put(
          Preconditions.checkNotNull(scheme), Preconditions.checkNotNull(downloader));
      return this;
    }

    public MultiSchemeFileDownloader build() {
      return new MultiSchemeFileDownloader(this);
    }
  }

  private final ImmutableMap<String, FileDownloader> schemeToDownloader;

  /** Returns a Builder for {@link MultiSchemeFileDownloader}. */
  public static Builder builder() {
    return new Builder();
  }

  /** Returns a Builder containing all registered FileDownloaders. */
  public Builder toBuilder() {
    final Builder builder = new Builder();
    for (Map.Entry<String, FileDownloader> entry : schemeToDownloader.entrySet()) {
      builder.addScheme(entry.getKey(), entry.getValue());
    }
    return builder;
  }

  /** Returns true if a FileDownloader is registered for the given scheme. */
  public boolean supportsScheme(String scheme) {
    return schemeToDownloader.containsKey(scheme);
  }

  private MultiSchemeFileDownloader(Builder builder) {
    this.schemeToDownloader = ImmutableMap.copyOf(builder.schemeToDownloader);
  }

  @Override
  @CheckReturnValue
  public ListenableFuture<Void> startDownloading(DownloadRequest downloadRequest) {
    FileDownloader delegate;
    try {
      delegate = getDelegate(downloadRequest.urlToDownload());
    } catch (DownloadException e) {
      return Futures.immediateFailedFuture(e);
    }
    return delegate.startDownloading(downloadRequest);
  }

  @Override
  @CheckReturnValue
  public ListenableFuture<CheckContentChangeResponse> isContentChanged(
      CheckContentChangeRequest checkContentChangeRequest) {
    FileDownloader delegate;
    try {
      delegate = getDelegate(checkContentChangeRequest.url());
    } catch (DownloadException e) {
      return Futures.immediateFailedFuture(e);
    }
    return delegate.isContentChanged(checkContentChangeRequest);
  }

  /** Extract the scheme of a url string. */
  @VisibleForTesting
  static String getScheme(String url) throws MalformedURLException {
    Uri parsed = Uri.parse(url);
    if (parsed == null) {
      throw new MalformedURLException("Could not parse URL.");
    }
    String scheme = parsed.getScheme();
    if (scheme == null) {
      throw new MalformedURLException("URL contained no scheme.");
    }
    return scheme;
  }

  /**
   * Lookup the delegate FileDownloader that can handle a url, based on the url's scheme.
   *
   * @throws DownloadException If an appropriate delegate FileDownloader could not be found.
   */
  FileDownloader getDelegate(String url) throws DownloadException {
    String scheme;
    try {
      scheme = getScheme(url);
    } catch (MalformedURLException e) {
      LogUtil.e("%s: The download url is malformed, url = %s", TAG, url);
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.MALFORMED_DOWNLOAD_URL)
          .setCause(e)
          .build();
    }

    FileDownloader downloader = schemeToDownloader.get(scheme);
    if (downloader == null) {
      LogUtil.e(
          "%s: No registered downloader supports the download url scheme, scheme = %s",
          TAG, scheme);
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.UNSUPPORTED_DOWNLOAD_URL_SCHEME)
          .build();
    }
    return downloader;
  }
}
