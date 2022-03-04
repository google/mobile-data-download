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
package com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger.downloader2;

import static com.google.common.util.concurrent.Futures.immediateFuture;

import android.content.Context;
import androidx.annotation.VisibleForTesting;
import com.google.android.downloader.AndroidConnectivityHandler;
import com.google.android.downloader.Downloader;
import com.google.android.downloader.Downloader.StateChangeCallback;
import com.google.android.downloader.FloggerDownloaderLogger;
import com.google.android.downloader.PlatformAndroidTrafficStatsTagger;
import com.google.android.downloader.PlatformUrlEngine;
import com.google.android.downloader.UrlEngine;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.annotations.MddControlExecutor;
import com.google.android.libraries.mobiledatadownload.annotations.MddDownloadExecutor;
import com.google.android.libraries.mobiledatadownload.annotations.SocketTrafficTag;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.OAuthTokenProvider;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.ExceptionHandler;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.Offroad2FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger.BaseOffroadFileDownloaderModule;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.DownloadMetadataStore;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListeningExecutorService;
import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;
import javax.inject.Singleton;

/**
 * Dagger module for providing FileDownloader that uses Android Downloader2.
 *
 * <p>This module should only be used when {@link MultiSchemeFileDownloader} is being provided by
 * {@link FileDownloaderModule}. That module includes a map of FileDownloader Suppliers, which this
 * module assumes is available to bind into.
 */
@Module(
    includes = {
      BaseOffroadFileDownloaderModule.class,
      BaseFileDownloaderDepsModule.class,
    })
@VisibleForTesting
public abstract class BaseFileDownloaderModule {
  @Provides
  @Singleton
  @IntoMap
  @StringKey("https")
  static Supplier<FileDownloader> provideFileDownloader(
      Context context,
      @MddDownloadExecutor ScheduledExecutorService downloadExecutor,
      @MddControlExecutor ListeningExecutorService controlExecutor,
      SynchronousFileStorage fileStorage,
      DownloadMetadataStore downloadMetadataStore,
      Optional<DownloadProgressMonitor> downloadProgressMonitor,
      Optional<Lazy<UrlEngine>> urlEngineOptional,
      Optional<Lazy<ExceptionHandler>> exceptionHandlerOptional,
      Optional<Lazy<OAuthTokenProvider>> authTokenProviderOptional,
      @SocketTrafficTag Optional<Integer> trafficTag,
      Flags flags) {
    return () ->
        createOffroad2FileDownloader(
            context,
            downloadExecutor,
            controlExecutor,
            fileStorage,
            downloadMetadataStore,
            downloadProgressMonitor,
            urlEngineOptional,
            exceptionHandlerOptional,
            authTokenProviderOptional,
            trafficTag,
            flags);
  }

  @VisibleForTesting
  public static Offroad2FileDownloader createOffroad2FileDownloader(
      Context context,
      ScheduledExecutorService downloadExecutor,
      ListeningExecutorService controlExecutor,
      SynchronousFileStorage fileStorage,
      DownloadMetadataStore downloadMetadataStore,
      Optional<DownloadProgressMonitor> downloadProgressMonitor,
      Optional<Lazy<UrlEngine>> urlEngineOptional,
      Optional<Lazy<ExceptionHandler>> exceptionHandlerOptional,
      Optional<Lazy<OAuthTokenProvider>> authTokenProviderOptional,
      Optional<Integer> trafficTag,
      Flags flags) {
    @Nullable
    com.google.android.downloader.OAuthTokenProvider authTokenProvider =
        authTokenProviderOptional.isPresent()
            ? convertToDownloaderAuthTokenProvider(authTokenProviderOptional.get().get())
            : null;

    ExceptionHandler handler =
        exceptionHandlerOptional.transform(Lazy::get).or(ExceptionHandler.withDefaultHandling());

    UrlEngine urlEngine;
    if (urlEngineOptional.isPresent()) {
      urlEngine = urlEngineOptional.get().get();
    } else {
      // Use {@link PlatformUrlEngine} if one was not provided.
      urlEngine =
          new PlatformUrlEngine(
              controlExecutor,
              /* connectTimeoutMs = */ flags.timeToWaitForDownloader(),
              /* readTimeoutMs = */ flags.timeToWaitForDownloader(),
              new PlatformAndroidTrafficStatsTagger());
    }

    AndroidConnectivityHandler connectivityHandler =
        new AndroidConnectivityHandler(
            context, downloadExecutor, /* timeoutMillis = */ flags.timeToWaitForDownloader());

    FloggerDownloaderLogger logger = new FloggerDownloaderLogger();

    Downloader downloader =
        new Downloader.Builder()
            .withIOExecutor(controlExecutor)
            .withConnectivityHandler(connectivityHandler)
            .withMaxConcurrentDownloads(flags.downloaderMaxThreads())
            .withLogger(logger)
            .addUrlEngine("https", urlEngine)
            .build();

    if (downloadProgressMonitor.isPresent()) {
      // Wire up downloader's state changes to DownloadProgressMonitor to handle connectivity
      // pauses.
      StateChangeCallback callback =
          state -> {
            if (state.getNumDownloadsPendingConnectivity() > 0
                && state.getNumDownloadsInFlight() == 0) {
              // Handle network connectivity pauses
              downloadProgressMonitor.get().pausedForConnectivity();
            }
          };
      downloader.registerStateChangeCallback(callback, controlExecutor);
    }

    return new Offroad2FileDownloader(
        downloader,
        fileStorage,
        downloadExecutor,
        authTokenProvider,
        downloadMetadataStore,
        handler,
        trafficTag);
  }

  private static com.google.android.downloader.OAuthTokenProvider
      convertToDownloaderAuthTokenProvider(OAuthTokenProvider authTokenProvider) {
    return uri -> immediateFuture(authTokenProvider.provideOAuthToken(uri.toString()));
  }

  private BaseFileDownloaderModule() {}
}
