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

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.downloader.offroad.dagger.downloader2.BaseFileDownloaderModule;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.SharedPreferencesDownloadMetadata;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.internal.logging.SharedPreferencesLoggingState;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.common.base.Optional;
import com.google.common.base.Supplier;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Utility class that provides support for building MDD with different types of dependencies for
 * Testing.
 *
 * <p>If multiple type of dependencies need to be supported across tests, they can be defined here
 * so all tests can rely on a single definition. This is useful for parameterizing tests, such as
 * the case for ControlExecutor:
 *
 * <pre>{@code
 * // In the test, define a parameter for ExecutorType
 * @TestParameter ExecutorType controlExecutorType;
 *
 * // When building MDD in the test, rely on the shared provider:
 * MobileDataDownloadBuilder.newBuilder()
 *     .setControlExecutor(controlExecutorType.executor())
 *      // include other dependencies...
 *     .build();
 *
 * }</pre>
 */
public final class MddTestDependencies {

  private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";
  private static final int INSTANCE_ID_CHAR_LIMIT = 10;
  private static final Random random = new Random();

  private MddTestDependencies() {}

  /**
   * Generates a random instance id.
   *
   * <p>This prevents potential cross test conflicts from occurring since metadata will be siloed
   * between tests.
   */
  public static String randomInstanceId() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < INSTANCE_ID_CHAR_LIMIT; i++) {
      sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  /**
   * Type of executor passed when building MDD.
   *
   * <p>Used for parameterizing tests.
   */
  public enum ExecutorType {
    SINGLE_THREADED,
    MULTI_THREADED;

    public ListeningExecutorService executor() {
      switch (this) {
        case SINGLE_THREADED:
          return MoreExecutors.listeningDecorator(
              Executors.newSingleThreadExecutor(
                  new ThreadFactoryBuilder().setNameFormat("MddSingleThreaded-%d").build()));
        case MULTI_THREADED:
          return MoreExecutors.listeningDecorator(
              Executors.newCachedThreadPool(
                  new ThreadFactoryBuilder().setNameFormat("MddMultiThreaded-%d").build()));
      }
      throw new AssertionError("ExecutorType unsupported");
    }
  }

  /**
   * Differentiates between Downloader Configurations.
   *
   * <p>Used for parameterizing tests, as well as for making configuration-specific test assertions.
   */
  public enum DownloaderConfigurationType {
    V2_PLATFORM;

    public Supplier<FileDownloader> fileDownloaderSupplier(
        Context context,
        ListeningExecutorService controlExecutor,
        ListeningScheduledExecutorService downloadExecutor,
        SynchronousFileStorage fileStorage,
        Flags flags,
        Optional<DownloadProgressMonitor> downloadProgressMonitor,
        Optional<String> instanceId) {

      // Set up file downloader supplier based on the configuration given
      switch (this) {
        case V2_PLATFORM:
          return () -> {
            return BaseFileDownloaderModule.createOffroad2FileDownloader(
                context,
                downloadExecutor,
                controlExecutor,
                fileStorage,
                new SharedPreferencesDownloadMetadata(
                    context.getSharedPreferences("downloadmetadata", 0), controlExecutor),
                /* downloadProgressMonitor= */ downloadProgressMonitor,
                /* urlEngineOptional= */ Optional.absent(),
                /* exceptionHandlerOptional= */ Optional.absent(),
                /* authTokenProviderOptional= */ Optional.absent(),
                /* cookieJarSupplierOptional= */ Optional.absent(),
                /* trafficTag= */ Optional.absent(),
                flags);
          };
      }
      throw new AssertionError("Invalid DownloaderConfigurationType");
    }
  }

  /**
   * Differentiates between LoggingStateStore implementations.
   *
   * <p>Used for parameterizing tests, as well as for making configuration-specific test assertions.
   */
  public enum LoggingStateStoreImpl {
    SHARED_PREFERENCES;

    public LoggingStateStore loggingStateStore(
        Context context,
        Optional<String> instanceIdOptional,
        TimeSource timeSource,
        Executor backgroundExecutor,
        Random random) {

      // Set up file downloader supplier based on the configuration given
      switch (this) {
        case SHARED_PREFERENCES:
          return SharedPreferencesLoggingState.createFromContext(
              context, instanceIdOptional, timeSource, backgroundExecutor, random);
      }
      throw new AssertionError("Invalid LoggingStateStoreImpl");
    }
  }
}
