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
package com.google.android.libraries.mobiledatadownload;

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.account.AccountManagerAccountSource;
import com.google.android.libraries.mobiledatadownload.delta.DeltaDecoder;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.dagger.ApplicationContextModule;
import com.google.android.libraries.mobiledatadownload.internal.dagger.DaggerStandaloneComponent;
import com.google.android.libraries.mobiledatadownload.internal.dagger.DownloaderModule;
import com.google.android.libraries.mobiledatadownload.internal.dagger.ExecutorsModule;
import com.google.android.libraries.mobiledatadownload.internal.dagger.MainMddLibModule;
import com.google.android.libraries.mobiledatadownload.internal.dagger.StandaloneComponent;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogSampler;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.logging.MddEventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.NoOpEventLogger;
import com.google.android.libraries.mobiledatadownload.lite.Downloader;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitor;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * A builder for {@link MobileDataDownload}.
 *
 * <p>
 *
 * <p>WARNING: Only one object should be built. Otherwise, there may be locking errors on the
 * underlying database and unnecessary memory consumption.
 *
 * <p>Furthermore, there may be interference between scheduled task.
 */
public final class MobileDataDownloadBuilder {
  private static final String TAG = "MobileDataDownloadBuilder";

  private final DaggerStandaloneComponent.Builder componentBuilder;

  private Context context;
  private ListeningExecutorService controlExecutor;
  private final List<FileGroupPopulator> fileGroupPopulatorList = new ArrayList<>();
  private Optional<TaskScheduler> taskSchedulerOptional = Optional.absent();
  private SynchronousFileStorage fileStorage;
  private NetworkUsageMonitor networkUsageMonitor;
  private Optional<DownloadProgressMonitor> downloadMonitorOptional = Optional.absent();
  private Supplier<FileDownloader> fileDownloaderSupplier;
  private Optional<DeltaDecoder> deltaDecoderOptional = Optional.absent();
  private Optional<Configurator> configurator = Optional.absent();
  private Optional<Logger> loggerOptional = Optional.absent();
  private Optional<SilentFeedback> silentFeedbackOptional = Optional.absent();
  private Optional<String> instanceIdOptional = Optional.absent();
  private Optional<Class<?>> foregroundDownloadServiceClassOptional = Optional.absent();
  private Optional<Flags> flagsOptional = Optional.absent();
  private Optional<AccountSource> accountSourceOptional = Optional.absent();
  private boolean useDefaultAccountSource = true;
  private Optional<CustomFileGroupValidator> customFileGroupValidatorOptional = Optional.absent();
  private Optional<ExperimentationConfig> experimentationConfigOptional = Optional.absent();

  public static MobileDataDownloadBuilder newBuilder() {
    return new MobileDataDownloadBuilder();
  }

  private MobileDataDownloadBuilder() {
    componentBuilder = DaggerStandaloneComponent.builder();
  }

  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setContext(Context context) {
    this.context = context.getApplicationContext();
    return this;
  }

  /**
   * Set Unique Instance ID of this instance of MobileDataDownload. Instance ID must be non-empty
   * and [a-z] (lower case).
   *
   * <p>Most apps should use @Singleton MDD. If an app wants to use multiple instances of MDD,
   * please be aware of following caveats: Each instance of MDD will have its own metadata, base
   * directory, and periodic backbround tasks. There is no sharing and no-dedup between instances.
   * Please talk to <internal>@ before using this.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setInstanceIdOptional(Optional<String> instanceIdOptional) {
    this.instanceIdOptional = instanceIdOptional;
    return this;
  }

  /**
   * Set the Control Executor which will manage MDD meta data.
   *
   * <p>NOTE: Control Executor must not be single thread executor otherwise it could lead to
   * deadlock or other side effects.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setControlExecutor(ListeningExecutorService controlExecutor) {
    Preconditions.checkNotNull(controlExecutor);
    // Executor that will execute tasks sequentially.
    this.controlExecutor = controlExecutor;
    return this;
  }

  /**
   * Sets a config populator that will be used by MDD to periodically refresh the data file groups.
   *
   * <p>If this is not set, then the client is responsible for refreshing the list of file groups in
   * MDD as and when they see fit.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder addFileGroupPopulator(FileGroupPopulator fileGroupPopulator) {
    this.fileGroupPopulatorList.add(fileGroupPopulator);
    return this;
  }

  /**
   * Add a list of config populator that will be used by MDD to periodically refresh the data file
   * groups.
   *
   * <p>If this is not set, then the client is responsible for refreshing the list of file groups in
   * MDD as and when they see fit.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder addFileGroupPopulators(
      ImmutableList<FileGroupPopulator> fileGroupPopulators) {
    this.fileGroupPopulatorList.addAll(fileGroupPopulators);
    return this;
  }

  /**
   * Set the task scheduler that will be used by MDD to schedule periodic and one-off tasks. Clients
   * can use GCM, FJD or Work Manager to schedule tasks, and then forward the notification to {@link
   * MobileDataDownload#handleTask(String)}.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setTaskScheduler(Optional<TaskScheduler> taskSchedulerOptional) {
    this.taskSchedulerOptional = taskSchedulerOptional;
    return this;
  }

  /** Set the optional Configurator which if present will be used by MDD to configure its flags. */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setConfiguratorOptional(Optional<Configurator> configurator) {
    this.configurator = configurator;
    return this;
  }

  /** Set the optional Logger which if present will be used by MDD to log events. */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setLoggerOptional(Optional<Logger> logger) {
    this.loggerOptional = logger;
    return this;
  }

  /** Set the flags otherwise default values will be used only. */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setFlagsOptional(Optional<Flags> flags) {
    this.flagsOptional = flags;
    return this;
  }

  /**
   * Set the optional SilentFeedback which if present will be used by MDD to send silent feedbacks.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setSilentFeedbackOptional(
      Optional<SilentFeedback> silentFeedbackOptional) {
    this.silentFeedbackOptional = silentFeedbackOptional;
    return this;
  }

  /**
   * Set the MobStore SynchronousFileStorage. Ideally this should be the same object as the one used
   * by the client app to read files from MDD
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setFileStorage(SynchronousFileStorage fileStorage) {
    this.fileStorage = fileStorage;
    return this;
  }

  /**
   * Set the NetworkUsageMonitor. This NetworkUsageMonitor instance must be the same instance that
   * is registered with SynchronousFileStorage.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setNetworkUsageMonitor(NetworkUsageMonitor networkUsageMonitor) {
    this.networkUsageMonitor = networkUsageMonitor;
    return this;
  }

  /**
   * Set the DownloadProgressMonitor. This DownloadProgressMonitor instance must be the same
   * instance that is registered with SynchronousFileStorage.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setDownloadMonitorOptional(
      Optional<DownloadProgressMonitor> downloadMonitorOptional) {
    this.downloadMonitorOptional = downloadMonitorOptional;
    return this;
  }

  /**
   * Set the FileDownloader Supplier. MDD takes in a Supplier of FileDownload to support lazy
   * instantiation of the FileDownloader
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setFileDownloaderSupplier(
      Supplier<FileDownloader> fileDownloaderSupplier) {
    this.fileDownloaderSupplier = fileDownloaderSupplier;
    return this;
  }

  /** Set the Delta file decoder. */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setDeltaDecoderOptional(
      Optional<DeltaDecoder> deltaDecoderOptional) {
    this.deltaDecoderOptional = deltaDecoderOptional;
    return this;
  }

  /**
   * Set the Foreground Download Service. This foreground service will keep the download alive even
   * if the user navigates away from the host app. This ensures long download can finish.
   *
   * <p>If the host needs to use both MDDLite and Full MDD, the Foreground Download Service can be
   * shared as an optimization. Please talk to <internal>@ on how to setup a shared Foreground
   * Download Service.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setForegroundDownloadServiceOptional(
      Optional<Class<?>> foregroundDownloadServiceClass) {
    this.foregroundDownloadServiceClassOptional = foregroundDownloadServiceClass;
    return this;
  }

  /**
   * Sets the AccountSource that's used to wipeout account-related data at maintenance time. If this
   * method is not called, an account source based on AccountManager will be injected.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setAccountSourceOptional(
      Optional<AccountSource> accountSourceOptional) {
    this.accountSourceOptional = accountSourceOptional;
    useDefaultAccountSource = false;
    return this;
  }

  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setCustomFileGroupValidatorOptional(
      Optional<CustomFileGroupValidator> customFileGroupValidatorOptional) {
    this.customFileGroupValidatorOptional = customFileGroupValidatorOptional;
    return this;
  }

  /**
   * Sets the ExperimentationConfig that's used when propagating experiment ids to external log
   * sources. If this is not called, experiment ids are not propagated. See <internal> for more
   * details.
   */
  @CanIgnoreReturnValue
  public MobileDataDownloadBuilder setExperimentationConfigOptional(
      Optional<ExperimentationConfig> experimentationConfigOptional) {
    this.experimentationConfigOptional = experimentationConfigOptional;
    return this;
  }

  // We use java.util.concurrent.Executor directly to create default Control Executor and
  // Download Executor.

  public MobileDataDownload build() {
    Preconditions.checkNotNull(context);
    Preconditions.checkNotNull(taskSchedulerOptional);
    Preconditions.checkNotNull(fileStorage);

    Preconditions.checkNotNull(networkUsageMonitor);
    Preconditions.checkNotNull(downloadMonitorOptional);
    Preconditions.checkNotNull(fileDownloaderSupplier);
    Preconditions.checkNotNull(customFileGroupValidatorOptional);

    Executor sequentialControlExecutor = MoreExecutors.newSequentialExecutor(controlExecutor);
    if (configurator.isPresent()) {
      // Submit commit task to sequentialControlExecutor to ensure that the commit task finishes
      // before any other API tasks can run.
      ListenableFuture<Void> commitFuture =
          PropagatedFutures.submitAsync(
              () -> configurator.get().commitToFlagSnapshot(), sequentialControlExecutor);

      PropagatedFutures.addCallback(
          commitFuture,
          new FutureCallback<Void>() {
            @Override
            public void onSuccess(Void result) {
              LogUtil.d("%s: Succeeded commitToFlagSnapshot.", TAG);
            }

            @Override
            public void onFailure(Throwable t) {
              LogUtil.w("%s: Failed to commitToFlagSnapshot: %s", TAG, t);
            }
          },
          MoreExecutors.directExecutor() /*fine to use directExecutor since it only print logs*/);
    }

    componentBuilder.applicationContextModule(new ApplicationContextModule(context));

    componentBuilder.executorsModule(new ExecutorsModule(sequentialControlExecutor));

    componentBuilder.downloaderModule(
        new DownloaderModule(deltaDecoderOptional, fileDownloaderSupplier));

    Flags flags = flagsOptional.or(new Flags() {});

    // EventLogger is needed in FrameworkProtoDataStoreModule, which is a sting module. As such it
    // cannot be constructed in our internal dagger module if we want to share the same EventLogger
    // throughout the library.
    final EventLogger eventLogger;
    if (loggerOptional.isPresent()) {
      eventLogger =
          new MddEventLogger(
              context,
              loggerOptional.get(),
              Constants.MDD_LIB_VERSION,
              new LogSampler(flags, new SecureRandom()),
              flags);
    } else {
      eventLogger = new NoOpEventLogger();
    }

    if (useDefaultAccountSource) {
      accountSourceOptional = Optional.of(new AccountManagerAccountSource(context));
    }

    componentBuilder.mainMddLibModule(
        new MainMddLibModule(
            fileStorage,
            networkUsageMonitor,
            eventLogger,
            downloadMonitorOptional,
            silentFeedbackOptional,
            instanceIdOptional,
            accountSourceOptional,
            flags,
            experimentationConfigOptional));

    StandaloneComponent component = componentBuilder.build();

    if (eventLogger instanceof MddEventLogger) {
      ((MddEventLogger) eventLogger).setLoggingStateStore(component.getLoggingStateStore());
    }

    Downloader.Builder singleFileDownloaderBuilder =
        Downloader.newBuilder()
            .setContext(context)
            .setControlExecutor(sequentialControlExecutor)
            .setFileDownloaderSupplier(fileDownloaderSupplier);

    if (downloadMonitorOptional.isPresent()) {
      singleFileDownloaderBuilder.setDownloadMonitor(downloadMonitorOptional.get());
    }

    if (foregroundDownloadServiceClassOptional.isPresent()) {
      singleFileDownloaderBuilder.setForegroundDownloadService(
          foregroundDownloadServiceClassOptional.get());
    }
    Downloader singleFileDownloader = singleFileDownloaderBuilder.build();

    return new MobileDataDownloadImpl(
        context,
        component.getEventLogger(),
        component.getMobileDataDownloadManager(),
        sequentialControlExecutor,
        fileGroupPopulatorList,
        taskSchedulerOptional,
        fileStorage,
        downloadMonitorOptional,
        foregroundDownloadServiceClassOptional,
        flags,
        singleFileDownloader,
        customFileGroupValidatorOptional,
        component.getTimeSource());
  }
}
