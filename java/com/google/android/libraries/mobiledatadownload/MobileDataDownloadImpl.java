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

import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateAsyncFunction;
import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateRunnable;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.android.libraries.mdi.download.MetadataProto.DataFile;
import com.google.android.libraries.mdi.download.MetadataProto.DataFileGroupInternal;
import com.google.android.libraries.mdi.download.MetadataProto.DownloadConditions;
import com.google.android.libraries.mdi.download.MetadataProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.android.libraries.mdi.download.MetadataProto.GroupKey;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.TaskScheduler.ConstraintOverrides;
import com.google.android.libraries.mobiledatadownload.TaskScheduler.NetworkState;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.foreground.ForegroundDownloadKey;
import com.google.android.libraries.mobiledatadownload.foreground.NotificationUtil;
import com.google.android.libraries.mobiledatadownload.internal.DownloadGroupState;
import com.google.android.libraries.mobiledatadownload.internal.ExceptionToMddResultMapper;
import com.google.android.libraries.mobiledatadownload.internal.MddConstants;
import com.google.android.libraries.mobiledatadownload.internal.MobileDataDownloadManager;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupPair;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.DownloadFutureMap;
import com.google.android.libraries.mobiledatadownload.internal.util.MddLiteConversionUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.ProtoConversionUtil;
import com.google.android.libraries.mobiledatadownload.lite.Downloader;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedExecutionSequencer;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.LogProto.DataDownloadFileGroupStats;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;

/**
 * Default implementation for {@link
 * com.google.android.libraries.mobiledatadownload.MobileDataDownload}.
 */
class MobileDataDownloadImpl implements MobileDataDownload {

  private static final String TAG = "MobileDataDownload";
  private static final long DUMP_DEBUG_INFO_TIMEOUT = 3;

  private final Context context;
  private final EventLogger eventLogger;
  private final List<FileGroupPopulator> fileGroupPopulatorList;
  private final Optional<TaskScheduler> taskSchedulerOptional;
  private final MobileDataDownloadManager mobileDataDownloadManager;
  private final SynchronousFileStorage fileStorage;
  private final Flags flags;
  private final Downloader singleFileDownloader;

  // Track all the on-going foreground downloads. This map is keyed by ForegroundDownloadKey.
  private final DownloadFutureMap<ClientFileGroup> foregroundDownloadFutureMap;

  // Track all on-going background download requests started by downloadFileGroup. This map is keyed
  // by ForegroundDownloadKey so request can be kept in sync with foregroundDownloadFutureMap.
  private final DownloadFutureMap<ClientFileGroup> downloadFutureMap;

  // This executor will execute tasks sequentially.
  private final Executor sequentialControlExecutor;
  // ExecutionSequencer will execute a ListenableFuture and its Futures.transforms before taking the
  // next task (<internal>). Most of MDD API should use
  // ExecutionSequencer to guarantee Metadata synchronization. Currently only downloadFileGroup and
  // handleTask APIs do not use ExecutionSequencer since their execution could take long time and
  // using ExecutionSequencer would block other APIs.
  private final PropagatedExecutionSequencer futureSerializer =
      PropagatedExecutionSequencer.create();
  private final Optional<DownloadProgressMonitor> downloadMonitorOptional;
  private final Optional<Class<?>> foregroundDownloadServiceClassOptional;
  private final AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator;
  private final TimeSource timeSource;

  MobileDataDownloadImpl(
      Context context,
      EventLogger eventLogger,
      MobileDataDownloadManager mobileDataDownloadManager,
      Executor sequentialControlExecutor,
      List<FileGroupPopulator> fileGroupPopulatorList,
      Optional<TaskScheduler> taskSchedulerOptional,
      SynchronousFileStorage fileStorage,
      Optional<DownloadProgressMonitor> downloadMonitorOptional,
      Optional<Class<?>> foregroundDownloadServiceClassOptional,
      Flags flags,
      Downloader singleFileDownloader,
      Optional<CustomFileGroupValidator> customValidatorOptional,
      TimeSource timeSource) {
    this.context = context;
    this.eventLogger = eventLogger;
    this.fileGroupPopulatorList = fileGroupPopulatorList;
    this.taskSchedulerOptional = taskSchedulerOptional;
    this.sequentialControlExecutor = sequentialControlExecutor;
    this.mobileDataDownloadManager = mobileDataDownloadManager;
    this.fileStorage = fileStorage;
    this.downloadMonitorOptional = downloadMonitorOptional;
    this.foregroundDownloadServiceClassOptional = foregroundDownloadServiceClassOptional;
    this.flags = flags;
    this.singleFileDownloader = singleFileDownloader;
    this.customFileGroupValidator =
        createCustomFileGroupValidator(
            customValidatorOptional,
            mobileDataDownloadManager,
            sequentialControlExecutor,
            fileStorage);
    this.downloadFutureMap = DownloadFutureMap.create(sequentialControlExecutor);
    this.foregroundDownloadFutureMap =
        DownloadFutureMap.create(
            sequentialControlExecutor,
            createCallbacksForForegroundService(context, foregroundDownloadServiceClassOptional));
    this.timeSource = timeSource;
  }

  // Wraps the custom validator because the validation at a lower level of the stack where
  // the ClientFileGroup is not available, yet ClientFileGroup is the client-facing API we'd
  // like to expose.
  private static AsyncFunction<DataFileGroupInternal, Boolean> createCustomFileGroupValidator(
      Optional<CustomFileGroupValidator> validatorOptional,
      MobileDataDownloadManager mobileDataDownloadManager,
      Executor executor,
      SynchronousFileStorage fileStorage) {
    if (!validatorOptional.isPresent()) {
      return unused -> immediateFuture(true);
    }

    return internalFileGroup ->
        PropagatedFutures.transformAsync(
            createClientFileGroup(
                internalFileGroup,
                /* account= */ null,
                ClientFileGroup.Status.PENDING_CUSTOM_VALIDATION,
                /* preserveZipDirectories= */ false,
                /* verifyIsolatedStructure= */ true,
                mobileDataDownloadManager,
                executor,
                fileStorage),
            propagateAsyncFunction(
                clientFileGroup -> validatorOptional.get().validateFileGroup(clientFileGroup)),
            executor);
  }

  /**
   * Functional interface used as callback for logging file group stats. Used to create file group
   * stats from the result of the future.
   *
   * @see attachMddApiLogging
   */
  private interface StatsFromApiResultCreator<T> {
    DataDownloadFileGroupStats create(T result);
  }

  /**
   * Functional interface used as callback when logging API result. Used to get the API result code
   * from the result of the API future if it succeeds.
   *
   * <p>Note: The need for this is due to {@link addFileGroup} returning false instead of an
   * exception if it fails. For other APIs with proper exception handling, it should suffice to
   * immediately return the success code.
   *
   * <p>TODO(b/143572409): Remove once addGroupForDownload is updated to return void.
   *
   * @see attachMddApiLogging
   */
  private interface ResultCodeFromApiResultGetter<T> {
    int get(T result);
  }

  /**
   * Helper function used to log mdd api stats. Adds FutureCallback to the {@code resultFuture}
   * which is the result of mdd api call and logs in onSuccess and onFailure functions of callback.
   *
   * @param apiName Code of the api being logged.
   * @param resultFuture Future result of the api call.
   * @param startTimeNs start time in ns.
   * @param defaultFileGroupStats Initial file group stats.
   * @param statsCreator This functional interface is invoked from the onSuccess of FutureCallback
   *     with the result of the future. File group stats returned here is merged with the initial
   *     stats and logged.
   */
  private <T> void attachMddApiLogging(
      int apiName,
      ListenableFuture<T> resultFuture,
      long startTimeNs,
      DataDownloadFileGroupStats defaultFileGroupStats,
      StatsFromApiResultCreator<T> statsCreator,
      ResultCodeFromApiResultGetter<T> resultCodeGetter) {
    // Using listener instead of transform since we need to log even if the future fails.
    // Note: Listener is being registered on directexecutor for accurate latency measurement.
    resultFuture.addListener(
        propagateRunnable(
            () -> {
              long latencyNs = timeSource.elapsedRealtimeNanos() - startTimeNs;
              // Log the stats asynchronously.
              // Note: To avoid adding latency to mdd api calls, log asynchronously.
              var unused =
                  PropagatedFutures.submit(
                      () -> {
                        int resultCode;
                        T result = null;
                        DataDownloadFileGroupStats fileGroupStats = defaultFileGroupStats;
                        try {
                          result = Futures.getDone(resultFuture);
                          resultCode = resultCodeGetter.get(result);
                        } catch (Throwable t) {
                          resultCode = ExceptionToMddResultMapper.map(t);
                        }

                        // Merge stats created from result of api with the default stats.
                        if (result != null) {
                          fileGroupStats =
                              fileGroupStats.toBuilder()
                                  .mergeFrom(statsCreator.create(result))
                                  .build();
                        }

                        Void resultLog = null;

                        eventLogger.logMddLibApiResultLog(resultLog);
                      },
                      sequentialControlExecutor);
            }),
        directExecutor());
  }

  @Override
  public ListenableFuture<Boolean> addFileGroup(AddFileGroupRequest addFileGroupRequest) {
    long startTimeNs = timeSource.elapsedRealtimeNanos();

    ListenableFuture<Boolean> resultFuture =
        futureSerializer.submitAsync(
            () -> addFileGroupHelper(addFileGroupRequest), sequentialControlExecutor);

    DataDownloadFileGroupStats defaultFileGroupStats =
        DataDownloadFileGroupStats.newBuilder()
            .setFileGroupName(addFileGroupRequest.dataFileGroup().getGroupName())
            .setBuildId(addFileGroupRequest.dataFileGroup().getBuildId())
            .setVariantId(addFileGroupRequest.dataFileGroup().getVariantId())
            .setHasAccount(addFileGroupRequest.accountOptional().isPresent())
            .setFileGroupVersionNumber(
                addFileGroupRequest.dataFileGroup().getFileGroupVersionNumber())
            .setOwnerPackage(addFileGroupRequest.dataFileGroup().getOwnerPackage())
            .setFileCount(addFileGroupRequest.dataFileGroup().getFileCount())
            .build();
    attachMddApiLogging(
        0,
        resultFuture,
        startTimeNs,
        defaultFileGroupStats,
        /* statsCreator= */ unused -> defaultFileGroupStats,
        /* resultCodeGetter= */ succeeded -> succeeded ? 0 : 0);

    return resultFuture;
  }

  private ListenableFuture<Boolean> addFileGroupHelper(AddFileGroupRequest addFileGroupRequest) {
    LogUtil.d(
        "%s: Adding for download group = '%s', variant = '%s', buildId = '%d' and"
            + " associating it with account = '%s', variant = '%s'",
        TAG,
        addFileGroupRequest.dataFileGroup().getGroupName(),
        addFileGroupRequest.dataFileGroup().getVariantId(),
        addFileGroupRequest.dataFileGroup().getBuildId(),
        String.valueOf(addFileGroupRequest.accountOptional().orNull()),
        String.valueOf(addFileGroupRequest.variantIdOptional().orNull()));

    DataFileGroup dataFileGroup = addFileGroupRequest.dataFileGroup();

    // Ensure that the owner package is always set as the host app.
    if (!dataFileGroup.hasOwnerPackage()) {
      dataFileGroup = dataFileGroup.toBuilder().setOwnerPackage(context.getPackageName()).build();
    } else if (!context.getPackageName().equals(dataFileGroup.getOwnerPackage())) {
      LogUtil.e(
          "%s: Added group = '%s' with wrong owner package: '%s' v.s. '%s' ",
          TAG,
          dataFileGroup.getGroupName(),
          context.getPackageName(),
          dataFileGroup.getOwnerPackage());
      return immediateFuture(false);
    }

    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder()
            .setGroupName(dataFileGroup.getGroupName())
            .setOwnerPackage(dataFileGroup.getOwnerPackage());

    if (addFileGroupRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(
          AccountUtil.serialize(addFileGroupRequest.accountOptional().get()));
    }

    if (addFileGroupRequest.variantIdOptional().isPresent()) {
      groupKeyBuilder.setVariantId(addFileGroupRequest.variantIdOptional().get());
    }

    try {
      DataFileGroupInternal dataFileGroupInternal = ProtoConversionUtil.convert(dataFileGroup);
      return mobileDataDownloadManager.addGroupForDownloadInternal(
          groupKeyBuilder.build(), dataFileGroupInternal, customFileGroupValidator);
    } catch (InvalidProtocolBufferException e) {
      // TODO(b/118137672): Consider rethrow exception instead of returning false.
      LogUtil.e(e, "%s: Unable to convert from DataFileGroup to DataFileGroupInternal.", TAG);
      return immediateFuture(false);
    }
  }

  // TODO: Change to return ListenableFuture<Void>.
  @Override
  public ListenableFuture<Boolean> removeFileGroup(RemoveFileGroupRequest removeFileGroupRequest) {
    return futureSerializer.submitAsync(
        () -> {
          GroupKey.Builder groupKeyBuilder =
              GroupKey.newBuilder()
                  .setGroupName(removeFileGroupRequest.groupName())
                  .setOwnerPackage(context.getPackageName());
          if (removeFileGroupRequest.accountOptional().isPresent()) {
            groupKeyBuilder.setAccount(
                AccountUtil.serialize(removeFileGroupRequest.accountOptional().get()));
          }
          if (removeFileGroupRequest.variantIdOptional().isPresent()) {
            groupKeyBuilder.setVariantId(removeFileGroupRequest.variantIdOptional().get());
          }

          GroupKey groupKey = groupKeyBuilder.build();
          return PropagatedFutures.transform(
              mobileDataDownloadManager.removeFileGroup(
                  groupKey, removeFileGroupRequest.pendingOnly()),
              voidArg -> true,
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<RemoveFileGroupsByFilterResponse> removeFileGroupsByFilter(
      RemoveFileGroupsByFilterRequest removeFileGroupsByFilterRequest) {
    return futureSerializer.submitAsync(
        () ->
            PropagatedFluentFuture.from(mobileDataDownloadManager.getAllFreshGroups())
                .transformAsync(
                    allFreshGroupKeyAndGroups -> {
                      ImmutableSet.Builder<GroupKey> groupKeysToRemoveBuilder =
                          ImmutableSet.builder();
                      for (GroupKeyAndGroup groupKeyAndGroup : allFreshGroupKeyAndGroups) {
                        if (applyRemoveFileGroupsFilter(
                            removeFileGroupsByFilterRequest, groupKeyAndGroup)) {
                          // Remove downloaded status so pending/downloaded versions of the same
                          // group are treated as one.
                          groupKeysToRemoveBuilder.add(
                              groupKeyAndGroup.groupKey().toBuilder().clearDownloaded().build());
                        }
                      }
                      ImmutableSet<GroupKey> groupKeysToRemove = groupKeysToRemoveBuilder.build();
                      if (groupKeysToRemove.isEmpty()) {
                        return immediateFuture(
                            RemoveFileGroupsByFilterResponse.newBuilder()
                                .setRemovedFileGroupsCount(0)
                                .build());
                      }
                      return PropagatedFutures.transform(
                          mobileDataDownloadManager.removeFileGroups(groupKeysToRemove.asList()),
                          unused ->
                              RemoveFileGroupsByFilterResponse.newBuilder()
                                  .setRemovedFileGroupsCount(groupKeysToRemove.size())
                                  .build(),
                          sequentialControlExecutor);
                    },
                    sequentialControlExecutor),
        sequentialControlExecutor);
  }

  // Perform filtering using options from RemoveFileGroupsByFilterRequest
  private static boolean applyRemoveFileGroupsFilter(
      RemoveFileGroupsByFilterRequest removeFileGroupsByFilterRequest,
      GroupKeyAndGroup groupKeyAndGroup) {
    // If request filters by account, ensure account is present and is equal
    Optional<Account> accountOptional = removeFileGroupsByFilterRequest.accountOptional();
    if (!accountOptional.isPresent() && groupKeyAndGroup.groupKey().hasAccount()) {
      // Account must explicitly be provided in order to remove account associated file groups.
      return false;
    }
    if (accountOptional.isPresent()
        && !AccountUtil.serialize(accountOptional.get())
            .equals(groupKeyAndGroup.groupKey().getAccount())) {
      return false;
    }

    return true;
  }

  /**
   * Helper function to create {@link DataDownloadFileGroupStats} object from {@link
   * GetFileGroupRequest} for getFileGroup() logging.
   *
   * <p>Used when the matching file group is not found or a failure occurred.
   * file_group_version_number and build_id are set to -1 by default.
   */
  private DataDownloadFileGroupStats createFileGroupStatsFromGetFileGroupRequest(
      GetFileGroupRequest getFileGroupRequest) {
    DataDownloadFileGroupStats.Builder fileGroupStatsBuilder =
        DataDownloadFileGroupStats.newBuilder();
    fileGroupStatsBuilder.setFileGroupName(getFileGroupRequest.groupName());
    if (getFileGroupRequest.variantIdOptional().isPresent()) {
      fileGroupStatsBuilder.setVariantId(getFileGroupRequest.variantIdOptional().get());
    }
    if (getFileGroupRequest.accountOptional().isPresent()) {
      fileGroupStatsBuilder.setHasAccount(true);
    } else {
      fileGroupStatsBuilder.setHasAccount(false);
    }

    fileGroupStatsBuilder.setFileGroupVersionNumber(
        MddConstants.FILE_GROUP_NOT_FOUND_FILE_GROUP_VERSION_NUMBER);
    fileGroupStatsBuilder.setBuildId(MddConstants.FILE_GROUP_NOT_FOUND_BUILD_ID);

    return fileGroupStatsBuilder.build();
  }

  // TODO: Futures.immediateFuture(null) uses a different annotation for Nullable.
  @SuppressWarnings("nullness")
  @Override
  public ListenableFuture<ClientFileGroup> getFileGroup(GetFileGroupRequest getFileGroupRequest) {
    long startTimeNs = timeSource.elapsedRealtimeNanos();

    ListenableFuture<ClientFileGroup> resultFuture =
        futureSerializer.submitAsync(
            () -> {
              GroupKey groupKey =
                  createGroupKey(
                      getFileGroupRequest.groupName(),
                      getFileGroupRequest.accountOptional(),
                      getFileGroupRequest.variantIdOptional());
              return PropagatedFutures.transformAsync(
                  mobileDataDownloadManager.getFileGroup(groupKey, /* downloaded= */ true),
                  dataFileGroup ->
                      createClientFileGroupAndLogQueryStats(
                          groupKey,
                          dataFileGroup,
                          /* downloaded= */ true,
                          getFileGroupRequest.preserveZipDirectories(),
                          getFileGroupRequest.verifyIsolatedStructure()),
                  sequentialControlExecutor);
            },
            sequentialControlExecutor);

    attachMddApiLogging(
        0,
        resultFuture,
        startTimeNs,
        createFileGroupStatsFromGetFileGroupRequest(getFileGroupRequest),
        /* statsCreator= */ result -> createFileGroupDetails(result),
        /* resultCodeGetter= */ unused -> 0);
    return resultFuture;
  }

  @SuppressWarnings("nullness")
  @Override
  public ListenableFuture<DataFileGroup> readDataFileGroup(
      ReadDataFileGroupRequest readDataFileGroupRequest) {
    return futureSerializer.submitAsync(
        () -> {
          GroupKey groupKey =
              createGroupKey(
                  readDataFileGroupRequest.groupName(),
                  readDataFileGroupRequest.accountOptional(),
                  readDataFileGroupRequest.variantIdOptional());
          return PropagatedFutures.transformAsync(
              mobileDataDownloadManager.getFileGroup(groupKey, /* downloaded= */ true),
              internalFileGroup -> immediateFuture(ProtoConversionUtil.reverse(internalFileGroup)),
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  private GroupKey createGroupKey(
      String groupName, Optional<Account> accountOptional, Optional<String> variantOptional) {
    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setGroupName(groupName).setOwnerPackage(context.getPackageName());

    if (accountOptional.isPresent()) {
      groupKeyBuilder.setAccount(AccountUtil.serialize(accountOptional.get()));
    }

    if (variantOptional.isPresent()) {
      groupKeyBuilder.setVariantId(variantOptional.get());
    }

    return groupKeyBuilder.build();
  }

  private ListenableFuture<ClientFileGroup> createClientFileGroupAndLogQueryStats(
      GroupKey groupKey,
      @Nullable DataFileGroupInternal dataFileGroup,
      boolean downloaded,
      boolean preserveZipDirectories,
      boolean verifyIsolatedStructure) {
    return PropagatedFutures.transform(
        createClientFileGroup(
            dataFileGroup,
            groupKey.hasAccount() ? groupKey.getAccount() : null,
            downloaded ? ClientFileGroup.Status.DOWNLOADED : ClientFileGroup.Status.PENDING,
            preserveZipDirectories,
            verifyIsolatedStructure,
            mobileDataDownloadManager,
            sequentialControlExecutor,
            fileStorage),
        clientFileGroup -> {
          if (clientFileGroup != null) {
            eventLogger.logMddQueryStats(createFileGroupDetails(clientFileGroup));
          }
          return clientFileGroup;
        },
        sequentialControlExecutor);
  }

  @SuppressWarnings("nullness")
  private static ListenableFuture<ClientFileGroup> createClientFileGroup(
      @Nullable DataFileGroupInternal dataFileGroup,
      @Nullable String account,
      ClientFileGroup.Status status,
      boolean preserveZipDirectories,
      boolean verifyIsolatedStructure,
      MobileDataDownloadManager manager,
      Executor executor,
      SynchronousFileStorage fileStorage) {
    if (dataFileGroup == null) {
      return immediateFuture(null);
    }
    ClientFileGroup.Builder clientFileGroupBuilder =
        ClientFileGroup.newBuilder()
            .setGroupName(dataFileGroup.getGroupName())
            .setOwnerPackage(dataFileGroup.getOwnerPackage())
            .setVersionNumber(dataFileGroup.getFileGroupVersionNumber())
            .setCustomProperty(dataFileGroup.getCustomProperty())
            .setBuildId(dataFileGroup.getBuildId())
            .setVariantId(dataFileGroup.getVariantId())
            .setStatus(status)
            .addAllLocale(dataFileGroup.getLocaleList());

    if (account != null) {
      clientFileGroupBuilder.setAccount(account);
    }

    if (dataFileGroup.hasCustomMetadata()) {
      clientFileGroupBuilder.setCustomMetadata(dataFileGroup.getCustomMetadata());
    }

    List<DataFile> dataFiles = dataFileGroup.getFileList();
    ListenableFuture<Void> addOnDeviceUrisFuture = immediateVoidFuture();
    if (status == ClientFileGroup.Status.DOWNLOADED
        || status == ClientFileGroup.Status.PENDING_CUSTOM_VALIDATION) {
      addOnDeviceUrisFuture =
          PropagatedFluentFuture.from(
                  manager.getDataFileUris(dataFileGroup, verifyIsolatedStructure))
              .transformAsync(
                  dataFileUriMap -> {
                    for (DataFile dataFile : dataFiles) {
                      if (!dataFileUriMap.containsKey(dataFile)) {
                        return immediateFailedFuture(
                            DownloadException.builder()
                                .setDownloadResultCode(
                                    DownloadResultCode.DOWNLOADED_FILE_NOT_FOUND_ERROR)
                                .setMessage("getDataFileUris() resolved to null")
                                .build());
                      }
                      Uri uri = dataFileUriMap.get(dataFile);

                      try {
                        if (!preserveZipDirectories && fileStorage.isDirectory(uri)) {
                          String rootPath = uri.getPath();
                          if (rootPath != null) {
                            clientFileGroupBuilder.addAllFile(
                                listAllClientFilesOfDirectory(fileStorage, uri, rootPath));
                          }
                        } else {
                          clientFileGroupBuilder.addFile(
                              createClientFile(
                                  dataFile.getFileId(),
                                  dataFile.getByteSize(),
                                  dataFile.getDownloadedFileByteSize(),
                                  uri.toString(),
                                  dataFile.hasCustomMetadata()
                                      ? dataFile.getCustomMetadata()
                                      : null));
                        }
                      } catch (IOException e) {
                        LogUtil.e(e, "Failed to list files under directory:" + uri);
                      }
                    }
                    return immediateVoidFuture();
                  },
                  executor);
    } else {
      for (DataFile dataFile : dataFiles) {
        clientFileGroupBuilder.addFile(
            createClientFile(
                dataFile.getFileId(),
                dataFile.getByteSize(),
                dataFile.getDownloadedFileByteSize(),
                /* uri= */ null,
                dataFile.hasCustomMetadata() ? dataFile.getCustomMetadata() : null));
      }
    }

    return PropagatedFluentFuture.from(addOnDeviceUrisFuture)
        .transform(unused -> clientFileGroupBuilder.build(), executor)
        .catching(DownloadException.class, exn -> null, executor);
  }

  private static ClientFile createClientFile(
      String fileId,
      int byteSize,
      int downloadByteSize,
      @Nullable String uri,
      @Nullable Any customMetadata) {
    ClientFile.Builder clientFileBuilder =
        ClientFile.newBuilder().setFileId(fileId).setFullSizeInBytes(byteSize);
    if (downloadByteSize > 0) {
      // Files with downloaded transforms like compress and zip could have different downloaded
      // file size than the final file size on disk. Return the downloaded file size for client to
      // track and calculate the download progress.
      clientFileBuilder.setDownloadSizeInBytes(downloadByteSize);
    }
    if (uri != null) {
      clientFileBuilder.setFileUri(uri);
    }
    if (customMetadata != null) {
      clientFileBuilder.setCustomMetadata(customMetadata);
    }
    return clientFileBuilder.build();
  }

  private static List<ClientFile> listAllClientFilesOfDirectory(
      SynchronousFileStorage fileStorage, Uri dirUri, String rootDir) throws IOException {
    List<ClientFile> clientFileList = new ArrayList<>();
    for (Uri childUri : fileStorage.children(dirUri)) {
      if (fileStorage.isDirectory(childUri)) {
        clientFileList.addAll(listAllClientFilesOfDirectory(fileStorage, childUri, rootDir));
      } else {
        String childPath = childUri.getPath();
        if (childPath != null) {
          ClientFile clientFile =
              ClientFile.newBuilder()
                  .setFileId(childPath.replaceFirst(rootDir, ""))
                  .setFullSizeInBytes((int) fileStorage.fileSize(childUri))
                  .setFileUri(childUri.toString())
                  .build();
          clientFileList.add(clientFile);
        }
      }
    }
    return clientFileList;
  }

  @Override
  public ListenableFuture<ImmutableList<ClientFileGroup>> getFileGroupsByFilter(
      GetFileGroupsByFilterRequest getFileGroupsByFilterRequest) {
    return futureSerializer.submitAsync(
        () ->
            PropagatedFutures.transformAsync(
                mobileDataDownloadManager.getAllFreshGroups(),
                allFreshGroupKeyAndGroups -> {
                  ListenableFuture<ImmutableList.Builder<ClientFileGroup>>
                      clientFileGroupsBuilderFuture =
                          immediateFuture(ImmutableList.<ClientFileGroup>builder());
                  for (GroupKeyAndGroup groupKeyAndGroup : allFreshGroupKeyAndGroups) {
                    clientFileGroupsBuilderFuture =
                        PropagatedFutures.transformAsync(
                            clientFileGroupsBuilderFuture,
                            clientFileGroupsBuilder -> {
                              GroupKey groupKey = groupKeyAndGroup.groupKey();
                              DataFileGroupInternal dataFileGroup =
                                  groupKeyAndGroup.dataFileGroup();
                              if (applyFilter(
                                  getFileGroupsByFilterRequest, groupKey, dataFileGroup)) {
                                return PropagatedFutures.transform(
                                    createClientFileGroupAndLogQueryStats(
                                        groupKey,
                                        dataFileGroup,
                                        groupKey.getDownloaded(),
                                        getFileGroupsByFilterRequest.preserveZipDirectories(),
                                        getFileGroupsByFilterRequest.verifyIsolatedStructure()),
                                    clientFileGroup -> {
                                      if (clientFileGroup != null) {
                                        clientFileGroupsBuilder.add(clientFileGroup);
                                      }
                                      return clientFileGroupsBuilder;
                                    },
                                    sequentialControlExecutor);
                              }
                              return immediateFuture(clientFileGroupsBuilder);
                            },
                            sequentialControlExecutor);
                  }

                  return PropagatedFutures.transform(
                      clientFileGroupsBuilderFuture,
                      ImmutableList.Builder::build,
                      sequentialControlExecutor);
                },
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  // Perform filtering using options from GetFileGroupsByFilterRequest
  private static boolean applyFilter(
      GetFileGroupsByFilterRequest getFileGroupsByFilterRequest,
      GroupKey groupKey,
      DataFileGroupInternal fileGroup) {
    if (getFileGroupsByFilterRequest.includeAllGroups()) {
      return true;
    }

    // If request filters by group name, ensure name is equal
    Optional<String> groupNameOptional = getFileGroupsByFilterRequest.groupNameOptional();
    if (groupNameOptional.isPresent()
        && !TextUtils.equals(groupNameOptional.get(), groupKey.getGroupName())) {
      return false;
    }

    // When the caller requests account independent groups only.
    if (getFileGroupsByFilterRequest.groupWithNoAccountOnly()) {
      return !groupKey.hasAccount();
    }

    // When the caller requests account dependent groups as well.
    if (getFileGroupsByFilterRequest.accountOptional().isPresent()
        && !AccountUtil.serialize(getFileGroupsByFilterRequest.accountOptional().get())
            .equals(groupKey.getAccount())) {
      return false;
    }
    return true;
  }

  /**
   * Creates {@link DataDownloadFileGroupStats} from {@link ClientFileGroup} for remote logging
   * purposes.
   */
  private static DataDownloadFileGroupStats createFileGroupDetails(
      ClientFileGroup clientFileGroup) {
    return DataDownloadFileGroupStats.newBuilder()
        .setFileGroupName(clientFileGroup.getGroupName())
        .setOwnerPackage(clientFileGroup.getOwnerPackage())
        .setFileGroupVersionNumber(clientFileGroup.getVersionNumber())
        .setFileCount(clientFileGroup.getFileCount())
        .setVariantId(clientFileGroup.getVariantId())
        .setBuildId(clientFileGroup.getBuildId())
        .build();
  }

  @Override
  public ListenableFuture<Void> importFiles(ImportFilesRequest importFilesRequest) {
    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder()
            .setGroupName(importFilesRequest.groupName())
            .setOwnerPackage(context.getPackageName());

    if (importFilesRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(AccountUtil.serialize(importFilesRequest.accountOptional().get()));
    }

    GroupKey groupKey = groupKeyBuilder.build();

    ImmutableList.Builder<DataFile> updatedDataFileListBuilder =
        ImmutableList.builderWithExpectedSize(importFilesRequest.updatedDataFileList().size());
    for (DownloadConfigProto.DataFile dataFile : importFilesRequest.updatedDataFileList()) {
      updatedDataFileListBuilder.add(ProtoConversionUtil.convertDataFile(dataFile));
    }

    return futureSerializer.submitAsync(
        () ->
            mobileDataDownloadManager.importFiles(
                groupKey,
                importFilesRequest.buildId(),
                importFilesRequest.variantId(),
                updatedDataFileListBuilder.build(),
                importFilesRequest.inlineFileMap(),
                importFilesRequest.customPropertyOptional(),
                customFileGroupValidator),
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> downloadFile(SingleFileDownloadRequest singleFileDownloadRequest) {
    return singleFileDownloader.download(
        MddLiteConversionUtil.convertToDownloadRequest(singleFileDownloadRequest));
  }

  @Override
  public ListenableFuture<ClientFileGroup> downloadFileGroup(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    // Submit the call to sequentialControlExecutor, but don't use futureSerializer. This will
    // ensure that multiple calls are enqueued to the executor in a FIFO order, but these calls
    // won't block each other when the download is in progress.
    return PropagatedFutures.submitAsync(
        () ->
            PropagatedFutures.transformAsync(
                // Check if requested file group has already been downloaded
                getDownloadGroupState(downloadFileGroupRequest),
                downloadGroupState -> {
                  switch (downloadGroupState.getKind()) {
                    case IN_PROGRESS_FUTURE:
                      // If the file group download is in progress, return that future immediately
                      return downloadGroupState.inProgressFuture();
                    case DOWNLOADED_GROUP:
                      // If the file group is already downloaded, return that immediately.
                      return immediateFuture(downloadGroupState.downloadedGroup());
                    case PENDING_GROUP:
                      return downloadPendingFileGroup(downloadFileGroupRequest);
                  }
                  throw new AssertionError(
                      String.format(
                          "received unsupported DownloadGroupState kind %s",
                          downloadGroupState.getKind()));
                },
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  /** Helper method to download a group after it's determined to be pending. */
  private ListenableFuture<ClientFileGroup> downloadPendingFileGroup(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    String groupName = downloadFileGroupRequest.groupName();
    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setGroupName(groupName).setOwnerPackage(context.getPackageName());

    if (downloadFileGroupRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(
          AccountUtil.serialize(downloadFileGroupRequest.accountOptional().get()));
    }
    if (downloadFileGroupRequest.variantIdOptional().isPresent()) {
      groupKeyBuilder.setVariantId(downloadFileGroupRequest.variantIdOptional().get());
    }

    GroupKey groupKey = groupKeyBuilder.build();

    if (downloadFileGroupRequest.listenerOptional().isPresent()) {
      if (downloadMonitorOptional.isPresent()) {
        downloadMonitorOptional
            .get()
            .addDownloadListener(groupName, downloadFileGroupRequest.listenerOptional().get());
      } else {
        return immediateFailedFuture(
            DownloadException.builder()
                .setDownloadResultCode(DownloadResultCode.DOWNLOAD_MONITOR_NOT_PROVIDED_ERROR)
                .setMessage(
                    "downloadFileGroup: DownloadListener is present but Download Monitor"
                        + " is not provided!")
                .build());
      }
    }

    Optional<DownloadConditions> downloadConditions;
    try {
      downloadConditions =
          downloadFileGroupRequest.downloadConditionsOptional().isPresent()
              ? Optional.of(
                  ProtoConversionUtil.convert(
                      downloadFileGroupRequest.downloadConditionsOptional().get()))
              : Optional.absent();
    } catch (InvalidProtocolBufferException e) {
      return immediateFailedFuture(e);
    }

    // Get the key used for the download future map
    ForegroundDownloadKey downloadKey =
        ForegroundDownloadKey.ofFileGroup(
            downloadFileGroupRequest.groupName(),
            downloadFileGroupRequest.accountOptional(),
            downloadFileGroupRequest.variantIdOptional());

    // Create a ListenableFutureTask to delay starting the downloadFuture until we can add the
    // future to our map.
    ListenableFutureTask<Void> startTask = ListenableFutureTask.create(() -> null);
    ListenableFuture<ClientFileGroup> downloadFuture =
        PropagatedFluentFuture.from(startTask)
            .transformAsync(
                unused ->
                    mobileDataDownloadManager.downloadFileGroup(
                        groupKey, downloadConditions, customFileGroupValidator),
                sequentialControlExecutor)
            .transformAsync(
                dataFileGroup ->
                    createClientFileGroup(
                        dataFileGroup,
                        downloadFileGroupRequest.accountOptional().isPresent()
                            ? AccountUtil.serialize(
                                downloadFileGroupRequest.accountOptional().get())
                            : null,
                        ClientFileGroup.Status.DOWNLOADED,
                        downloadFileGroupRequest.preserveZipDirectories(),
                        downloadFileGroupRequest.verifyIsolatedStructure(),
                        mobileDataDownloadManager,
                        sequentialControlExecutor,
                        fileStorage),
                sequentialControlExecutor)
            .transform(Preconditions::checkNotNull, sequentialControlExecutor);

    // Get a handle on the download task so we can get the CFG during transforms
    PropagatedFluentFuture<ClientFileGroup> downloadTaskFuture =
        PropagatedFluentFuture.from(downloadFutureMap.add(downloadKey.toString(), downloadFuture))
            .transformAsync(
                unused -> {
                  // Now that the download future is added, start the task and return the future
                  startTask.run();
                  return downloadFuture;
                },
                sequentialControlExecutor);

    ListenableFuture<ClientFileGroup> transformFuture =
        downloadTaskFuture
            .transformAsync(
                unused -> downloadFutureMap.remove(downloadKey.toString()),
                sequentialControlExecutor)
            .transformAsync(
                unused -> {
                  ClientFileGroup clientFileGroup = getDone(downloadTaskFuture);

                  if (downloadFileGroupRequest.listenerOptional().isPresent()) {
                    try {
                      downloadFileGroupRequest.listenerOptional().get().onComplete(clientFileGroup);
                    } catch (Exception e) {
                      LogUtil.w(
                          e,
                          "%s: Listener onComplete failed for group %s",
                          TAG,
                          clientFileGroup.getGroupName());
                    }
                    if (downloadMonitorOptional.isPresent()) {
                      downloadMonitorOptional.get().removeDownloadListener(groupName);
                    }
                  }
                  return immediateFuture(clientFileGroup);
                },
                sequentialControlExecutor);

    PropagatedFutures.addCallback(
        transformFuture,
        new FutureCallback<ClientFileGroup>() {
          @Override
          public void onSuccess(ClientFileGroup result) {}

          @Override
          public void onFailure(Throwable t) {
            if (downloadFileGroupRequest.listenerOptional().isPresent()) {
              downloadFileGroupRequest.listenerOptional().get().onFailure(t);

              if (downloadMonitorOptional.isPresent()) {
                downloadMonitorOptional.get().removeDownloadListener(groupName);
              }
            }

            // Remove future from map
            ListenableFuture<Void> unused = downloadFutureMap.remove(downloadKey.toString());
          }
        },
        sequentialControlExecutor);

    return transformFuture;
  }

  @Override
  public ListenableFuture<Void> downloadFileWithForegroundService(
      SingleFileDownloadRequest singleFileDownloadRequest) {
    return singleFileDownloader.downloadWithForegroundService(
        MddLiteConversionUtil.convertToDownloadRequest(singleFileDownloadRequest));
  }

  @Override
  public ListenableFuture<ClientFileGroup> downloadFileGroupWithForegroundService(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    LogUtil.d("%s: downloadFileGroupWithForegroundService start.", TAG);
    if (!foregroundDownloadServiceClassOptional.isPresent()) {
      return immediateFailedFuture(
          new IllegalArgumentException(
              "downloadFileGroupWithForegroundService: ForegroundDownloadService is not"
                  + " provided!"));
    }

    if (!downloadMonitorOptional.isPresent()) {
      return immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.DOWNLOAD_MONITOR_NOT_PROVIDED_ERROR)
              .setMessage(
                  "downloadFileGroupWithForegroundService: Download Monitor is not provided!")
              .build());
    }

    // Submit the call to sequentialControlExecutor, but don't use futureSerializer. This will
    // ensure that multiple calls are enqueued to the executor in a FIFO order, but these calls
    // won't block each other when the download is in progress.
    return PropagatedFutures.submitAsync(
        () ->
            PropagatedFutures.transformAsync(
                // Check if requested file group has already been downloaded
                getDownloadGroupState(downloadFileGroupRequest),
                downloadGroupState -> {
                  switch (downloadGroupState.getKind()) {
                    case IN_PROGRESS_FUTURE:
                      // If the file group download is in progress, return that future immediately
                      return downloadGroupState.inProgressFuture();
                    case DOWNLOADED_GROUP:
                      // If the file group is already downloaded, return that immediately
                      return immediateFuture(downloadGroupState.downloadedGroup());
                    case PENDING_GROUP:
                      return downloadPendingFileGroupWithForegroundService(
                          downloadFileGroupRequest, downloadGroupState.pendingGroup());
                  }
                  throw new AssertionError(
                      String.format(
                          "received unsupported DownloadGroupState kind %s",
                          downloadGroupState.getKind()));
                },
                sequentialControlExecutor),
        sequentialControlExecutor);
  }

  /**
   * Helper method to download a file group in the foreground after it has been confirmed to be
   * pending.
   */
  private ListenableFuture<ClientFileGroup> downloadPendingFileGroupWithForegroundService(
      DownloadFileGroupRequest downloadFileGroupRequest, DataFileGroupInternal pendingGroup) {
    // It's OK to recreate the NotificationChannel since it can also be used to restore a
    // deleted channel and to update an existing channel's name, description, group, and/or
    // importance.
    NotificationUtil.createNotificationChannel(context);

    String groupName = downloadFileGroupRequest.groupName();
    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setGroupName(groupName).setOwnerPackage(context.getPackageName());

    if (downloadFileGroupRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(
          AccountUtil.serialize(downloadFileGroupRequest.accountOptional().get()));
    }
    if (downloadFileGroupRequest.variantIdOptional().isPresent()) {
      groupKeyBuilder.setVariantId(downloadFileGroupRequest.variantIdOptional().get());
    }

    GroupKey groupKey = groupKeyBuilder.build();
    ForegroundDownloadKey foregroundDownloadKey =
        ForegroundDownloadKey.ofFileGroup(
            groupName,
            downloadFileGroupRequest.accountOptional(),
            downloadFileGroupRequest.variantIdOptional());

    DownloadListener downloadListenerWithNotification =
        createDownloadListenerWithNotification(downloadFileGroupRequest, pendingGroup);
    // The downloadMonitor will trigger the DownloadListener.
    downloadMonitorOptional
        .get()
        .addDownloadListener(
            downloadFileGroupRequest.groupName(), downloadListenerWithNotification);

    Optional<DownloadConditions> downloadConditions;
    try {
      downloadConditions =
          downloadFileGroupRequest.downloadConditionsOptional().isPresent()
              ? Optional.of(
                  ProtoConversionUtil.convert(
                      downloadFileGroupRequest.downloadConditionsOptional().get()))
              : Optional.absent();
    } catch (InvalidProtocolBufferException e) {
      return immediateFailedFuture(e);
    }

    // Create a ListenableFutureTask to delay starting the downloadFuture until we can add the
    // future to our map.
    ListenableFutureTask<Void> startTask = ListenableFutureTask.create(() -> null);
    PropagatedFluentFuture<ClientFileGroup> downloadFileGroupFuture =
        PropagatedFluentFuture.from(startTask)
            .transformAsync(
                unused ->
                    mobileDataDownloadManager.downloadFileGroup(
                        groupKey, downloadConditions, customFileGroupValidator),
                sequentialControlExecutor)
            .transformAsync(
                dataFileGroup ->
                    createClientFileGroup(
                        dataFileGroup,
                        downloadFileGroupRequest.accountOptional().isPresent()
                            ? AccountUtil.serialize(
                                downloadFileGroupRequest.accountOptional().get())
                            : null,
                        ClientFileGroup.Status.DOWNLOADED,
                        downloadFileGroupRequest.preserveZipDirectories(),
                        downloadFileGroupRequest.verifyIsolatedStructure(),
                        mobileDataDownloadManager,
                        sequentialControlExecutor,
                        fileStorage),
                sequentialControlExecutor)
            .transform(Preconditions::checkNotNull, sequentialControlExecutor);

    ListenableFuture<ClientFileGroup> transformFuture =
        PropagatedFutures.transformAsync(
            foregroundDownloadFutureMap.add(
                foregroundDownloadKey.toString(), downloadFileGroupFuture),
            unused -> {
              // Now that the download future is added, start the task and return the future
              startTask.run();
              return downloadFileGroupFuture;
            },
            sequentialControlExecutor);

    PropagatedFutures.addCallback(
        transformFuture,
        new FutureCallback<ClientFileGroup>() {
          @Override
          public void onSuccess(ClientFileGroup clientFileGroup) {
            // Currently the MobStore monitor does not support onSuccess so we have to add
            // callback to the download future here.
            try {
              downloadListenerWithNotification.onComplete(clientFileGroup);
            } catch (Exception e) {
              LogUtil.w(
                  e,
                  "%s: Listener onComplete failed for group %s",
                  TAG,
                  clientFileGroup.getGroupName());
            }
          }

          @Override
          public void onFailure(Throwable t) {
            // Currently the MobStore monitor does not support onFailure so we have to add
            // callback to the download future here.
            downloadListenerWithNotification.onFailure(t);
          }
        },
        sequentialControlExecutor);

    return transformFuture;
  }

  /** Helper method to return a {@link DownloadGroupState} for the given request. */
  private ListenableFuture<DownloadGroupState> getDownloadGroupState(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    ForegroundDownloadKey foregroundDownloadKey =
        ForegroundDownloadKey.ofFileGroup(
            downloadFileGroupRequest.groupName(),
            downloadFileGroupRequest.accountOptional(),
            downloadFileGroupRequest.variantIdOptional());

    String groupName = downloadFileGroupRequest.groupName();
    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setGroupName(groupName).setOwnerPackage(context.getPackageName());

    if (downloadFileGroupRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(
          AccountUtil.serialize(downloadFileGroupRequest.accountOptional().get()));
    }

    if (downloadFileGroupRequest.variantIdOptional().isPresent()) {
      groupKeyBuilder.setVariantId(downloadFileGroupRequest.variantIdOptional().get());
    }

    boolean isDownloadListenerPresent = downloadFileGroupRequest.listenerOptional().isPresent();
    GroupKey groupKey = groupKeyBuilder.build();

    return futureSerializer.submitAsync(
        () -> {
          ListenableFuture<Optional<ListenableFuture<ClientFileGroup>>>
              foregroundDownloadFutureOptional =
                  foregroundDownloadFutureMap.get(foregroundDownloadKey.toString());
          ListenableFuture<Optional<ListenableFuture<ClientFileGroup>>>
              backgroundDownloadFutureOptional =
                  downloadFutureMap.get(foregroundDownloadKey.toString());

          return PropagatedFutures.whenAllSucceed(
                  foregroundDownloadFutureOptional, backgroundDownloadFutureOptional)
              .callAsync(
                  () -> {
                    if (getDone(foregroundDownloadFutureOptional).isPresent()) {
                      return immediateFuture(
                          DownloadGroupState.ofInProgressFuture(
                              getDone(foregroundDownloadFutureOptional).get()));
                    } else if (getDone(backgroundDownloadFutureOptional).isPresent()) {
                      return immediateFuture(
                          DownloadGroupState.ofInProgressFuture(
                              getDone(backgroundDownloadFutureOptional).get()));
                    }

                    // Get pending and downloaded versions to tell if we should return downloaded
                    // version early
                    ListenableFuture<GroupPair> fileGroupVersionsFuture =
                        PropagatedFutures.transformAsync(
                            mobileDataDownloadManager.getFileGroup(
                                groupKey, /* downloaded= */ false),
                            pendingDataFileGroup ->
                                PropagatedFutures.transform(
                                    mobileDataDownloadManager.getFileGroup(
                                        groupKey, /* downloaded= */ true),
                                    downloadedDataFileGroup ->
                                        GroupPair.create(
                                            pendingDataFileGroup, downloadedDataFileGroup),
                                    sequentialControlExecutor),
                            sequentialControlExecutor);

                    return PropagatedFutures.transformAsync(
                        fileGroupVersionsFuture,
                        fileGroupVersionsPair -> {
                          // if pending version is not null, return pending version
                          if (fileGroupVersionsPair.pendingGroup() != null) {
                            return immediateFuture(
                                DownloadGroupState.ofPendingGroup(
                                    checkNotNull(fileGroupVersionsPair.pendingGroup())));
                          }
                          // If both groups are null, return group not found failure
                          if (fileGroupVersionsPair.downloadedGroup() == null) {
                            // TODO(b/174808410): Add Logging
                            // file group is not pending nor downloaded -- return failure.
                            DownloadException failure =
                                DownloadException.builder()
                                    .setDownloadResultCode(DownloadResultCode.GROUP_NOT_FOUND_ERROR)
                                    .setMessage(
                                        "Nothing to download for file group: "
                                            + groupKey.getGroupName())
                                    .build();
                            if (isDownloadListenerPresent) {
                              downloadFileGroupRequest.listenerOptional().get().onFailure(failure);
                            }
                            return immediateFailedFuture(failure);
                          }

                          DataFileGroupInternal downloadedDataFileGroup =
                              checkNotNull(fileGroupVersionsPair.downloadedGroup());

                          // Notify download listener (if present) that file group has been
                          // downloaded.
                          if (isDownloadListenerPresent) {
                            downloadMonitorOptional
                                .get()
                                .addDownloadListener(
                                    downloadFileGroupRequest.groupName(),
                                    downloadFileGroupRequest.listenerOptional().get());
                          }
                          PropagatedFluentFuture<ClientFileGroup> transformFuture =
                              PropagatedFluentFuture.from(
                                      createClientFileGroup(
                                          downloadedDataFileGroup,
                                          downloadFileGroupRequest.accountOptional().isPresent()
                                              ? AccountUtil.serialize(
                                                  downloadFileGroupRequest.accountOptional().get())
                                              : null,
                                          ClientFileGroup.Status.DOWNLOADED,
                                          downloadFileGroupRequest.preserveZipDirectories(),
                                          downloadFileGroupRequest.verifyIsolatedStructure(),
                                          mobileDataDownloadManager,
                                          sequentialControlExecutor,
                                          fileStorage))
                                  .transform(Preconditions::checkNotNull, sequentialControlExecutor)
                                  .transform(
                                      clientFileGroup -> {
                                        if (isDownloadListenerPresent) {
                                          try {
                                            downloadFileGroupRequest
                                                .listenerOptional()
                                                .get()
                                                .onComplete(clientFileGroup);
                                          } catch (Exception e) {
                                            LogUtil.w(
                                                e,
                                                "%s: Listener onComplete failed for group %s",
                                                TAG,
                                                clientFileGroup.getGroupName());
                                          }
                                          downloadMonitorOptional
                                              .get()
                                              .removeDownloadListener(groupName);
                                        }
                                        return clientFileGroup;
                                      },
                                      sequentialControlExecutor);
                          transformFuture.addCallback(
                              new FutureCallback<ClientFileGroup>() {
                                @Override
                                public void onSuccess(ClientFileGroup result) {}

                                @Override
                                public void onFailure(Throwable t) {
                                  if (isDownloadListenerPresent) {
                                    downloadMonitorOptional.get().removeDownloadListener(groupName);
                                  }
                                }
                              },
                              sequentialControlExecutor);

                          // Use directExecutor here since we are performing a trivial operation.
                          return transformFuture.transform(
                              DownloadGroupState::ofDownloadedGroup, directExecutor());
                        },
                        sequentialControlExecutor);
                  },
                  sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  private DownloadListener createDownloadListenerWithNotification(
      DownloadFileGroupRequest downloadRequest, DataFileGroupInternal fileGroup) {

    String networkPausedMessage = getNetworkPausedMessage(downloadRequest, fileGroup);

    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    ForegroundDownloadKey foregroundDownloadKey =
        ForegroundDownloadKey.ofFileGroup(
            downloadRequest.groupName(),
            downloadRequest.accountOptional(),
            downloadRequest.variantIdOptional());

    NotificationCompat.Builder notification =
        NotificationUtil.createNotificationBuilder(
            context,
            downloadRequest.groupSizeBytes(),
            downloadRequest.contentTitleOptional().or(downloadRequest.groupName()),
            downloadRequest.contentTextOptional().or(downloadRequest.groupName()));
    int notificationKey = NotificationUtil.notificationKeyForKey(downloadRequest.groupName());

    if (downloadRequest.showNotifications() == DownloadFileGroupRequest.ShowNotifications.ALL) {
      NotificationUtil.createCancelAction(
          context,
          foregroundDownloadServiceClassOptional.get(),
          foregroundDownloadKey.toString(),
          notification,
          notificationKey);

      notificationManager.notify(notificationKey, notification.build());
    }

    return new DownloadListener() {
      @Override
      public void onProgress(long currentSize) {
        // TODO(b/229123693): return this future once DownloadListener has an async api.
        // There can be a race condition, where onProgress can be called
        // after onComplete or onFailure which removes the future and the notification.
        // Check foregroundDownloadFutureMap first before updating notification.
        ListenableFuture<?> unused =
            PropagatedFutures.transformAsync(
                foregroundDownloadFutureMap.containsKey(foregroundDownloadKey.toString()),
                futureInProgress -> {
                  if (futureInProgress
                      && downloadRequest.showNotifications()
                          == DownloadFileGroupRequest.ShowNotifications.ALL) {
                    notification
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setProgress(
                            downloadRequest.groupSizeBytes(),
                            (int) currentSize,
                            /* indeterminate= */ downloadRequest.groupSizeBytes() <= 0);
                    notificationManager.notify(notificationKey, notification.build());
                  }
                  if (downloadRequest.listenerOptional().isPresent()) {
                    downloadRequest.listenerOptional().get().onProgress(currentSize);
                  }
                  return immediateVoidFuture();
                },
                sequentialControlExecutor);
      }

      @Override
      public void pausedForConnectivity() {
        // TODO(b/229123693): return this future once DownloadListener has an async api.
        // There can be a race condition, where pausedForConnectivity can be called
        // after onComplete or onFailure which removes the future and the notification.
        // Check foregroundDownloadFutureMap first before updating notification.
        ListenableFuture<?> unused =
            PropagatedFutures.transformAsync(
                foregroundDownloadFutureMap.containsKey(foregroundDownloadKey.toString()),
                futureInProgress -> {
                  if (futureInProgress
                      && downloadRequest.showNotifications()
                          == DownloadFileGroupRequest.ShowNotifications.ALL) {
                    notification
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentText(networkPausedMessage)
                        .setSmallIcon(android.R.drawable.stat_sys_download)
                        .setOngoing(true)
                        // hide progress bar.
                        .setProgress(0, 0, false);
                    notificationManager.notify(notificationKey, notification.build());
                  }
                  if (downloadRequest.listenerOptional().isPresent()) {
                    downloadRequest.listenerOptional().get().pausedForConnectivity();
                  }
                  return immediateVoidFuture();
                },
                sequentialControlExecutor);
      }

      @Override
      public void onComplete(ClientFileGroup clientFileGroup) {
        // TODO(b/229123693): return this future once DownloadListener has an async api.
        ListenableFuture<?> unused =
            PropagatedFutures.submitAsync(
                () -> {
                  boolean onCompleteFailed = false;
                  if (downloadRequest.listenerOptional().isPresent()) {
                    try {
                      downloadRequest.listenerOptional().get().onComplete(clientFileGroup);
                    } catch (Exception e) {
                      LogUtil.w(
                          e,
                          "%s: Delegate onComplete failed for group %s, showing failure"
                              + " notification.",
                          TAG,
                          clientFileGroup.getGroupName());
                      onCompleteFailed = true;
                    }
                  }

                  // Clear the notification action.
                  if (downloadRequest.showNotifications()
                      == DownloadFileGroupRequest.ShowNotifications.ALL) {
                    notification.mActions.clear();

                    if (onCompleteFailed) {
                      // Show download failed in notification.
                      notification
                          .setCategory(NotificationCompat.CATEGORY_STATUS)
                          .setContentText(NotificationUtil.getDownloadFailedMessage(context))
                          .setOngoing(false)
                          .setSmallIcon(android.R.drawable.stat_sys_warning)
                          // hide progress bar.
                          .setProgress(0, 0, false);

                      notificationManager.notify(notificationKey, notification.build());
                    } else {
                      NotificationUtil.cancelNotificationForKey(
                          context, downloadRequest.groupName());
                    }
                  }

                  downloadMonitorOptional.get().removeDownloadListener(downloadRequest.groupName());

                  return foregroundDownloadFutureMap.remove(foregroundDownloadKey.toString());
                },
                sequentialControlExecutor);
      }

      @Override
      public void onFailure(Throwable t) {
        // TODO(b/229123693): return this future once DownloadListener has an async api.
        ListenableFuture<?> unused =
            PropagatedFutures.submitAsync(
                () -> {
                  if (downloadRequest.showNotifications()
                      == DownloadFileGroupRequest.ShowNotifications.ALL) {
                    // Clear the notification action.
                    notification.mActions.clear();

                    // Show download failed in notification.
                    notification
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setContentText(NotificationUtil.getDownloadFailedMessage(context))
                        .setOngoing(false)
                        .setSmallIcon(android.R.drawable.stat_sys_warning)
                        // hide progress bar.
                        .setProgress(0, 0, false);

                    notificationManager.notify(notificationKey, notification.build());
                  }

                  if (downloadRequest.listenerOptional().isPresent()) {
                    downloadRequest.listenerOptional().get().onFailure(t);
                  }
                  downloadMonitorOptional.get().removeDownloadListener(downloadRequest.groupName());

                  return foregroundDownloadFutureMap.remove(foregroundDownloadKey.toString());
                },
                sequentialControlExecutor);
      }
    };
  }

  // Helper method to get the correct network paused message
  private String getNetworkPausedMessage(
      DownloadFileGroupRequest downloadRequest, DataFileGroupInternal fileGroup) {
    DeviceNetworkPolicy networkPolicyForDownload =
        fileGroup.getDownloadConditions().getDeviceNetworkPolicy();
    if (downloadRequest.downloadConditionsOptional().isPresent()) {
      try {
        networkPolicyForDownload =
            ProtoConversionUtil.convert(downloadRequest.downloadConditionsOptional().get())
                .getDeviceNetworkPolicy();
      } catch (InvalidProtocolBufferException unused) {
        // Do nothing -- we will rely on the file group's network policy.
      }
    }

    switch (networkPolicyForDownload) {
      case DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK: // fallthrough
      case DOWNLOAD_ONLY_ON_WIFI:
        return NotificationUtil.getDownloadPausedWifiMessage(context);
      default:
        return NotificationUtil.getDownloadPausedMessage(context);
    }
  }

  @Override
  public void cancelForegroundDownload(String downloadKey) {
    LogUtil.d("%s: CancelForegroundDownload for key = %s", TAG, downloadKey);
    ListenableFuture<?> unused =
        PropagatedFutures.transformAsync(
            foregroundDownloadFutureMap.get(downloadKey),
            downloadFuture -> {
              if (downloadFuture.isPresent()) {
                LogUtil.v(
                    "%s: CancelForegroundDownload future found for key = %s, cancelling...",
                    TAG, downloadKey);
                downloadFuture.get().cancel(false);
              }
              return immediateVoidFuture();
            },
            sequentialControlExecutor);
    // Attempt cancel with internal MDD Lite instance in case it's a single file uri (cancel call is
    // a noop if internal MDD Lite doesn't know about it).
    singleFileDownloader.cancelForegroundDownload(downloadKey);
  }

  @Override
  public void schedulePeriodicTasks() {
    schedulePeriodicTasksInternal(Optional.absent());
  }

  @Override
  public ListenableFuture<Void> schedulePeriodicBackgroundTasks() {
    return futureSerializer.submit(
        () -> {
          schedulePeriodicTasksInternal(/* constraintOverridesMap= */ Optional.absent());
          return null;
        },
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> schedulePeriodicBackgroundTasks(
      Optional<Map<String, ConstraintOverrides>> constraintOverridesMap) {
    return futureSerializer.submit(
        () -> {
          schedulePeriodicTasksInternal(constraintOverridesMap);
          return null;
        },
        sequentialControlExecutor);
  }

  private void schedulePeriodicTasksInternal(
      Optional<Map<String, ConstraintOverrides>> constraintOverridesMap) {
    if (!taskSchedulerOptional.isPresent()) {
      LogUtil.e(
          "%s: Called schedulePeriodicTasksInternal when taskScheduler is not provided.", TAG);
      return;
    }

    TaskScheduler taskScheduler = taskSchedulerOptional.get();

    // Schedule task that runs on charging without any network, every 6 hours.
    taskScheduler.schedulePeriodicTask(
        TaskScheduler.CHARGING_PERIODIC_TASK,
        flags.chargingGcmTaskPeriod(),
        NetworkState.NETWORK_STATE_ANY,
        getConstraintOverrides(constraintOverridesMap, TaskScheduler.CHARGING_PERIODIC_TASK));

    // Schedule maintenance task that runs on charging, once every day.
    // This task should run even if mdd is disabled, to handle cleanup.
    taskScheduler.schedulePeriodicTask(
        TaskScheduler.MAINTENANCE_PERIODIC_TASK,
        flags.maintenanceGcmTaskPeriod(),
        NetworkState.NETWORK_STATE_ANY,
        getConstraintOverrides(constraintOverridesMap, TaskScheduler.MAINTENANCE_PERIODIC_TASK));

    // Schedule task that runs on cellular+charging, every 6 hours.
    taskScheduler.schedulePeriodicTask(
        TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK,
        flags.cellularChargingGcmTaskPeriod(),
        NetworkState.NETWORK_STATE_CONNECTED,
        getConstraintOverrides(
            constraintOverridesMap, TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK));

    // Schedule task that runs on wifi+charging, every 6 hours.
    taskScheduler.schedulePeriodicTask(
        TaskScheduler.WIFI_CHARGING_PERIODIC_TASK,
        flags.wifiChargingGcmTaskPeriod(),
        NetworkState.NETWORK_STATE_UNMETERED,
        getConstraintOverrides(constraintOverridesMap, TaskScheduler.WIFI_CHARGING_PERIODIC_TASK));
  }

  private static Optional<ConstraintOverrides> getConstraintOverrides(
      Optional<Map<String, ConstraintOverrides>> constraintOverridesMap,
      String maintenancePeriodicTask) {
    return constraintOverridesMap.isPresent()
        ? Optional.fromNullable(constraintOverridesMap.get().get(maintenancePeriodicTask))
        : Optional.absent();
  }

  @Override
  public ListenableFuture<Void> cancelPeriodicBackgroundTasks() {
    return futureSerializer.submit(
        () -> {
          cancelPeriodicTasksInternal();
          return null;
        },
        sequentialControlExecutor);
  }

  private void cancelPeriodicTasksInternal() {
    if (!taskSchedulerOptional.isPresent()) {
      LogUtil.w("%s: Called cancelPeriodicTasksInternal when taskScheduler is not provided.", TAG);
      return;
    }

    TaskScheduler taskScheduler = taskSchedulerOptional.get();

    taskScheduler.cancelPeriodicTask(TaskScheduler.CHARGING_PERIODIC_TASK);
    taskScheduler.cancelPeriodicTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK);
    taskScheduler.cancelPeriodicTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK);
    taskScheduler.cancelPeriodicTask(TaskScheduler.WIFI_CHARGING_PERIODIC_TASK);
  }

  @Override
  public ListenableFuture<Void> handleTask(String tag) {
    // All work done here that touches metadata (MobileDataDownloadManager) should be serialized
    // through sequentialControlExecutor.
    switch (tag) {
      case TaskScheduler.MAINTENANCE_PERIODIC_TASK:
        return futureSerializer.submitAsync(
            mobileDataDownloadManager::maintenance, sequentialControlExecutor);

      case TaskScheduler.CHARGING_PERIODIC_TASK:
        ListenableFuture<Void> refreshFileGroupsFuture = refreshFileGroups();
        return PropagatedFutures.transformAsync(
            refreshFileGroupsFuture,
            propagateAsyncFunction(
                v -> mobileDataDownloadManager.verifyAllPendingGroups(customFileGroupValidator)),
            sequentialControlExecutor);

      case TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK:
        return refreshAndDownload(false /*onWifi*/);

      case TaskScheduler.WIFI_CHARGING_PERIODIC_TASK:
        return refreshAndDownload(true /*onWifi*/);

      default:
        LogUtil.d("%s: gcm task doesn't belong to MDD", TAG);
        return immediateFailedFuture(
            new IllegalArgumentException("Unknown task tag sent to MDD.handleTask() " + tag));
    }
  }

  private ListenableFuture<Void> refreshAndDownload(boolean onWifi) {
    // We will do 2 passes to support 2-step downloads. In each step, we will refresh and then
    // download.
    return PropagatedFluentFuture.from(refreshFileGroups())
        .transformAsync(
            v ->
                mobileDataDownloadManager.downloadAllPendingGroups(
                    onWifi, customFileGroupValidator),
            sequentialControlExecutor)
        .transformAsync(v -> refreshFileGroups(), sequentialControlExecutor)
        .transformAsync(
            v ->
                mobileDataDownloadManager.downloadAllPendingGroups(
                    onWifi, customFileGroupValidator),
            sequentialControlExecutor);
  }

  private ListenableFuture<Void> refreshFileGroups() {
    List<ListenableFuture<Void>> refreshFutures = new ArrayList<>();
    for (FileGroupPopulator fileGroupPopulator : fileGroupPopulatorList) {
      refreshFutures.add(fileGroupPopulator.refreshFileGroups(this));
    }

    return PropagatedFutures.whenAllComplete(refreshFutures)
        .call(() -> null, sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> maintenance() {
    return handleTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK);
  }

  @Override
  public ListenableFuture<Void> collectGarbage() {
    return futureSerializer.submitAsync(
        mobileDataDownloadManager::removeExpiredGroupsAndFiles, sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> clear() {
    return futureSerializer.submitAsync(
        mobileDataDownloadManager::clear, sequentialControlExecutor);
  }

  // incompatible argument for parameter msg of e.
  // incompatible types in return.
  @Override
  public String getDebugInfoAsString() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintWriter writer = new PrintWriter(out);
    try {
      // Okay to block here because this method is for debugging only.
      mobileDataDownloadManager.dump(writer).get(DUMP_DEBUG_INFO_TIMEOUT, TimeUnit.SECONDS);
      writer.println("==== MOBSTORE_DEBUG_INFO ====");
      writer.print(fileStorage.getDebugInfo());
    } catch (ExecutionException | TimeoutException e) {
      String errString = String.format("%s: Couldn't get debug info: %s", TAG, e);
      LogUtil.e(errString);
      return errString;
    } catch (InterruptedException e) {
      // see <internal>
      Thread.currentThread().interrupt();
      String errString = String.format("%s: Couldn't get debug info: %s", TAG, e);
      LogUtil.e(errString);
      return errString;
    }
    writer.flush();
    return out.toString();
  }

  @Override
  public ListenableFuture<Void> reportUsage(UsageEvent usageEvent) {
    eventLogger.logMddUsageEvent(createFileGroupDetails(usageEvent.clientFileGroup()), null);

    return immediateVoidFuture();
  }

  private static DownloadFutureMap.StateChangeCallbacks createCallbacksForForegroundService(
      Context context, Optional<Class<?>> foregroundDownloadServiceClassOptional) {
    return new DownloadFutureMap.StateChangeCallbacks() {
      @Override
      public void onAdd(String key, int newSize) {
        // Only start foreground service if this is the first future we are adding.
        if (newSize == 1 && foregroundDownloadServiceClassOptional.isPresent()) {
          NotificationUtil.startForegroundDownloadService(
              context, foregroundDownloadServiceClassOptional.get(), key);
        }
      }

      @Override
      public void onRemove(String key, int newSize) {
        // Only stop foreground service if there are no more futures remaining.
        if (newSize == 0 && foregroundDownloadServiceClassOptional.isPresent()) {
          NotificationUtil.stopForegroundDownloadService(
              context, foregroundDownloadServiceClassOptional.get(), key);
        }
      }
    };
  }
}
