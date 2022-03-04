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
package com.google.android.libraries.mobiledatadownload.internal.dagger;

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.AccountSource;
import com.google.android.libraries.mobiledatadownload.ExperimentationConfig;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.ApplicationContext;
import com.google.android.libraries.mobiledatadownload.internal.FileGroupsMetadata;
import com.google.android.libraries.mobiledatadownload.internal.SharedFilesMetadata;
import com.google.android.libraries.mobiledatadownload.internal.SharedPreferencesFileGroupsMetadata;
import com.google.android.libraries.mobiledatadownload.internal.SharedPreferencesSharedFilesMetadata;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.DownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.experimentation.NoOpDownloadStageManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.internal.logging.NoOpLoggingState;
import com.google.android.libraries.mobiledatadownload.internal.util.FuturesUtil;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.common.base.Optional;
import dagger.Module;
import dagger.Provides;
import java.util.concurrent.Executor;
import javax.inject.Singleton;

/** Module for MDD Lib dependencies */
@Module
public class MainMddLibModule {
  /** The version of MDD library. Same as mdi_download module version. */
  // TODO(b/122271766): Figure out how to update this automatically.
  // LINT.IfChange
  public static final int MDD_LIB_VERSION = 422883838;
  // LINT.ThenChange(<internal>)

  private final SynchronousFileStorage fileStorage;
  private final NetworkUsageMonitor networkUsageMonitor;
  private final EventLogger eventLogger;
  private final Optional<DownloadProgressMonitor> downloadProgressMonitorOptional;
  private final Optional<SilentFeedback> silentFeedbackOptional;
  private final Optional<String> instanceId;
  private final Optional<AccountSource> accountSourceOptional;
  private final Flags flags;
  private final Optional<ExperimentationConfig> experimentationConfigOptional;

  public MainMddLibModule(
      SynchronousFileStorage fileStorage,
      NetworkUsageMonitor networkUsageMonitor,
      EventLogger eventLogger,
      Optional<DownloadProgressMonitor> downloadProgressMonitorOptional,
      Optional<SilentFeedback> silentFeedbackOptional,
      Optional<String> instanceId,
      Optional<AccountSource> accountSourceOptional,
      Flags flags,
      Optional<ExperimentationConfig> experimentationConfigOptional) {
    this.fileStorage = fileStorage;
    this.networkUsageMonitor = networkUsageMonitor;
    this.eventLogger = eventLogger;
    this.downloadProgressMonitorOptional = downloadProgressMonitorOptional;
    this.silentFeedbackOptional = silentFeedbackOptional;
    this.instanceId = instanceId;
    this.accountSourceOptional = accountSourceOptional;
    this.flags = flags;
    this.experimentationConfigOptional = experimentationConfigOptional;
  }

  @Provides
  @Singleton
  static FileGroupsMetadata provideFileGroupsMetadata(
      SharedPreferencesFileGroupsMetadata fileGroupsMetadata) {
    return fileGroupsMetadata;
  }

  @Provides
  @Singleton
  static SharedFilesMetadata provideSharedFilesMetadata(
      SharedPreferencesSharedFilesMetadata sharedFilesMetadata) {
    return sharedFilesMetadata;
  }

  @Provides
  @Singleton
  EventLogger provideEventLogger() {
    return eventLogger;
  }

  @Provides
  @Singleton
  SilentFeedback providesSilentFeedback() {
    if (this.silentFeedbackOptional.isPresent()) {
      return this.silentFeedbackOptional.get();
    } else {
      return (throwable, description, args) -> {
        // No-op SilentFeedback.
      };
    }
  }

  @Provides
  @Singleton
  Optional<AccountSource> provideAccountSourceOptional(@ApplicationContext Context context) {
    return this.accountSourceOptional;
  }

  @Provides
  @Singleton
  static TimeSource provideTimeSource() {
    return System::currentTimeMillis;
  }

  @Provides
  @Singleton
  @InstanceId
  Optional<String> provideInstanceId() {
    return this.instanceId;
  }

  @Provides
  @Singleton
  NetworkUsageMonitor provideNetworkUsageMonitor() {
    return this.networkUsageMonitor;
  }

  @Provides
  @Singleton
  // TODO: We don't need to have @Singleton here and few other places in this class
  // since it comes from the this instance. We should remove this since it could increase APK size.
  Optional<DownloadProgressMonitor> provideDownloadProgressMonitor() {
    return this.downloadProgressMonitorOptional;
  }

  @Provides
  @Singleton
  SynchronousFileStorage provideSynchronousFileStorage() {
    return this.fileStorage;
  }

  @Provides
  @Singleton
  Flags provideFlags() {
    return this.flags;
  }

  @Provides
  Optional<ExperimentationConfig> provideExperimentationConfigOptional() {
    return this.experimentationConfigOptional;
  }

  @Provides
  @Singleton
  static FuturesUtil provideFuturesUtil(@SequentialControlExecutor Executor sequentialExecutor) {
    return new FuturesUtil(sequentialExecutor);
  }

  @Provides
  @Singleton
  static LoggingStateStore provideLoggingStateStore() {
    return new NoOpLoggingState();
  }

  @Provides
  static DownloadStageManager provideDownloadStageManager(
      FileGroupsMetadata fileGroupsMetadata,
      Optional<ExperimentationConfig> experimentationConfigOptional,
      @SequentialControlExecutor Executor executor,
      Flags flags) {
    return new NoOpDownloadStageManager();
  }
}
