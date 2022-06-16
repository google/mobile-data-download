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

import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateAsyncCallable;
import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateAsyncFunction;
import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateCallable;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Pair;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.TaskScheduler.ConstraintOverrides;
import com.google.android.libraries.mobiledatadownload.TaskScheduler.NetworkState;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.foreground.NotificationUtil;
import com.google.android.libraries.mobiledatadownload.internal.MobileDataDownloadManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.MddLiteConversionUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.ProtoConversionUtil;
import com.google.android.libraries.mobiledatadownload.lite.Downloader;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ExecutionSequencer;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.protobuf.Any;
import com.google.protobuf.GeneratedMessageLite;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
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

  // This executor will execute tasks sequentially.
  private final Executor sequentialControlExecutor;
  // ExecutionSequencer will execute a ListenableFuture and its Futures.transforms before taking the
  // next task (<internal>). Most of MDD API should use
  // ExecutionSequencer to guarantee Metadata synchronization. Currently only downloadFileGroup and
  // handleTask APIs do not use ExecutionSequencer since their execution could take long time and
  // using ExecutionSequencer would block other APIs.
  private final ExecutionSequencer futureSerializer = ExecutionSequencer.create();
  private final Optional<DownloadProgressMonitor> downloadMonitorOptional;
  private final Optional<Class<?>> foregroundDownloadServiceClassOptional;
  private final AsyncFunction<DataFileGroupInternal, Boolean> customFileGroupValidator;

  // Synchronization will be done through sequentialControlExecutor
  // Keep all the on-going foreground downloads.
  @VisibleForTesting
  final Map<String, ListenableFuture<ClientFileGroup>> keyToListenableFuture = new HashMap<>();

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
      Optional<CustomFileGroupValidator> customValidatorOptional) {
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
      return unused -> Futures.immediateFuture(true);
    }

    return internalFileGroup ->
        Futures.transformAsync(
            createClientFileGroup(
                internalFileGroup,
                /* account= */ null,
                ClientFileGroup.Status.PENDING_CUSTOM_VALIDATION,
                /* preserveZipDirectories= */ false,
                mobileDataDownloadManager,
                executor,
                fileStorage),
            propagateAsyncFunction(
                clientFileGroup -> validatorOptional.get().validateFileGroup(clientFileGroup)),
            executor);
  }

  @Override
  public ListenableFuture<Boolean> addFileGroup(AddFileGroupRequest addFileGroupRequest) {
    return futureSerializer.submitAsync(
        propagateAsyncCallable(
            () -> {
              LogUtil.d(
                  "%s: Adding for download group = '%s', variant = '%s' and associating it with"
                      + " account = '%s', variant = '%s'",
                  TAG,
                  addFileGroupRequest.dataFileGroup().getGroupName(),
                  addFileGroupRequest.dataFileGroup().getVariantId(),
                  String.valueOf(addFileGroupRequest.accountOptional().orNull()),
                  String.valueOf(addFileGroupRequest.variantIdOptional().orNull()));

              DataFileGroup dataFileGroup = addFileGroupRequest.dataFileGroup();

              // Ensure that the owner package is always set as the host app.
              if (!dataFileGroup.hasOwnerPackage()) {
                dataFileGroup =
                    dataFileGroup.toBuilder().setOwnerPackage(context.getPackageName()).build();
              } else if (!context.getPackageName().equals(dataFileGroup.getOwnerPackage())) {
                LogUtil.e(
                    "%s: Added group = '%s' with wrong owner package: '%s' v.s. '%s' ",
                    TAG,
                    dataFileGroup.getGroupName(),
                    context.getPackageName(),
                    dataFileGroup.getOwnerPackage());
                return Futures.immediateFuture(false);
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
                DataFileGroupInternal dataFileGroupInternal =
                    ProtoConversionUtil.convert(dataFileGroup);
                return mobileDataDownloadManager.addGroupForDownloadInternal(
                    groupKeyBuilder.build(), dataFileGroupInternal, customFileGroupValidator);
              } catch (InvalidProtocolBufferException e) {
                // TODO(b/118137672): Consider rethrow exception instead of returning false.
                LogUtil.e(
                    e, "%s: Unable to convert from DataFileGroup to DataFileGroupInternal.", TAG);
                return Futures.immediateFuture(false);
              }
            }),
        sequentialControlExecutor);
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
          return Futures.transform(
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
            FluentFuture.from(mobileDataDownloadManager.getAllFreshGroups())
                .transformAsync(
                    allFreshGroups -> {
                      ImmutableSet.Builder<GroupKey> groupKeysToRemoveBuilder =
                          ImmutableSet.builder();
                      for (Pair<GroupKey, DataFileGroupInternal> keyDataFileGroupPair :
                          allFreshGroups) {
                        if (applyRemoveFileGroupsFilter(
                            removeFileGroupsByFilterRequest, keyDataFileGroupPair)) {
                          // Remove downloaded status so pending/downloaded versions of the same
                          // group are treated as one.
                          groupKeysToRemoveBuilder.add(
                              keyDataFileGroupPair.first.toBuilder().clearDownloaded().build());
                        }
                      }
                      ImmutableSet<GroupKey> groupKeysToRemove = groupKeysToRemoveBuilder.build();
                      if (groupKeysToRemove.isEmpty()) {
                        return Futures.immediateFuture(
                            RemoveFileGroupsByFilterResponse.newBuilder()
                                .setRemovedFileGroupsCount(0)
                                .build());
                      }
                      return Futures.transform(
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
      Pair<GroupKey, DataFileGroupInternal> keyDataFileGroupPair) {
    // If request filters by account, ensure account is present and is equal
    Optional<Account> accountOptional = removeFileGroupsByFilterRequest.accountOptional();
    if (!accountOptional.isPresent() && keyDataFileGroupPair.first.hasAccount()) {
      // Account must explicitly be provided in order to remove account associated file groups.
      return false;
    }
    if (accountOptional.isPresent()
        && !AccountUtil.serialize(accountOptional.get())
            .equals(keyDataFileGroupPair.first.getAccount())) {
      return false;
    }

    return true;
  }

  // TODO: Futures.immediateFuture(null) uses a different annotation for Nullable.
  @SuppressWarnings("nullness")
  @Override
  public ListenableFuture<ClientFileGroup> getFileGroup(GetFileGroupRequest getFileGroupRequest) {
    return futureSerializer.submitAsync(
        () -> {
          GroupKey.Builder groupKeyBuilder =
              GroupKey.newBuilder()
                  .setGroupName(getFileGroupRequest.groupName())
                  .setOwnerPackage(context.getPackageName());

          if (getFileGroupRequest.accountOptional().isPresent()) {
            groupKeyBuilder.setAccount(
                AccountUtil.serialize(getFileGroupRequest.accountOptional().get()));
          }

          if (getFileGroupRequest.variantIdOptional().isPresent()) {
            groupKeyBuilder.setVariantId(getFileGroupRequest.variantIdOptional().get());
          }

          GroupKey groupKey = groupKeyBuilder.build();
          return Futures.transformAsync(
              mobileDataDownloadManager.getFileGroup(groupKey, /*downloaded=*/ true),
              dataFileGroup ->
                  createClientFileGroupAndLogQueryStats(
                      groupKey,
                      dataFileGroup,
                      /*downloaded=*/ true,
                      getFileGroupRequest.preserveZipDirectories()),
              sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  private ListenableFuture<ClientFileGroup> createClientFileGroupAndLogQueryStats(
      GroupKey groupKey,
      @Nullable DataFileGroupInternal dataFileGroup,
      boolean downloaded,
      boolean preserveZipDirectories) {
    return Futures.transform(
        createClientFileGroup(
            dataFileGroup,
            groupKey.hasAccount() ? groupKey.getAccount() : null,
            downloaded ? ClientFileGroup.Status.DOWNLOADED : ClientFileGroup.Status.PENDING,
            preserveZipDirectories,
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
      MobileDataDownloadManager manager,
      Executor executor,
      SynchronousFileStorage fileStorage) {
    if (dataFileGroup == null) {
      return Futures.immediateFuture(null);
    }
    ClientFileGroup.Builder clientFileGroupBuilderInit =
        ClientFileGroup.newBuilder()
            .setGroupName(dataFileGroup.getGroupName())
            .setOwnerPackage(dataFileGroup.getOwnerPackage())
            .setVersionNumber(dataFileGroup.getFileGroupVersionNumber())
            .setBuildId(dataFileGroup.getBuildId())
            .setVariantId(dataFileGroup.getVariantId())
            .setStatus(status)
            .addAllLocale(dataFileGroup.getLocaleList());

    if (account != null) {
      clientFileGroupBuilderInit.setAccount(account);
    }

    if (dataFileGroup.hasCustomMetadata()) {
      clientFileGroupBuilderInit.setCustomMetadata(dataFileGroup.getCustomMetadata());
    }

    ListenableFuture<ClientFileGroup.Builder> clientFileGroupBuilderFuture =
        Futures.immediateFuture(clientFileGroupBuilderInit);
    for (DataFile dataFile : dataFileGroup.getFileList()) {
      clientFileGroupBuilderFuture =
          Futures.transformAsync(
              clientFileGroupBuilderFuture,
              clientFileGroupBuilder -> {
                if (status == ClientFileGroup.Status.DOWNLOADED
                    || status == ClientFileGroup.Status.PENDING_CUSTOM_VALIDATION) {
                  return Futures.transformAsync(
                      manager.getDataFileUri(dataFile, dataFileGroup),
                      fileUri -> {
                        if (fileUri == null) {
                          return Futures.immediateFailedFuture(
                              DownloadException.builder()
                                  .setDownloadResultCode(
                                      DownloadResultCode.DOWNLOADED_FILE_NOT_FOUND_ERROR)
                                  .setMessage("getDataFileUri() resolved to null")
                                  .build());
                        }
                        try {
                          if (!preserveZipDirectories && fileStorage.isDirectory(fileUri)) {
                            String rootPath = fileUri.getPath();
                            if (rootPath != null) {
                              clientFileGroupBuilder.addAllFile(
                                  listAllClientFilesOfDirectory(fileStorage, fileUri, rootPath));
                            }
                          } else {
                            clientFileGroupBuilder.addFile(
                                createClientFile(
                                    dataFile.getFileId(),
                                    dataFile.getByteSize(),
                                    dataFile.getDownloadedFileByteSize(),
                                    fileUri.toString(),
                                    dataFile.hasCustomMetadata()
                                        ? dataFile.getCustomMetadata()
                                        : null));
                          }
                        } catch (IOException e) {
                          LogUtil.e(e, "Failed to list files under directory:" + fileUri);
                        }
                        return Futures.immediateFuture(clientFileGroupBuilder);
                      },
                      executor);
                } else {
                  clientFileGroupBuilder.addFile(
                      createClientFile(
                          dataFile.getFileId(),
                          dataFile.getByteSize(),
                          dataFile.getDownloadedFileByteSize(),
                          /* uri = */ null,
                          dataFile.hasCustomMetadata() ? dataFile.getCustomMetadata() : null));
                  return Futures.immediateFuture(clientFileGroupBuilder);
                }
              },
              executor);
    }

    return FluentFuture.from(clientFileGroupBuilderFuture)
        .transform(GeneratedMessageLite.Builder::build, executor)
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
            Futures.transformAsync(
                mobileDataDownloadManager.getAllFreshGroups(),
                allFreshGroups -> {
                  ListenableFuture<ImmutableList.Builder<ClientFileGroup>>
                      clientFileGroupsBuilderFuture =
                          Futures.immediateFuture(ImmutableList.<ClientFileGroup>builder());
                  for (Pair<GroupKey, DataFileGroupInternal> keyDataFileGroupPair :
                      allFreshGroups) {
                    clientFileGroupsBuilderFuture =
                        Futures.transformAsync(
                            clientFileGroupsBuilderFuture,
                            clientFileGroupsBuilder -> {
                              GroupKey groupKey = keyDataFileGroupPair.first;
                              DataFileGroupInternal dataFileGroup = keyDataFileGroupPair.second;
                              if (applyFilter(
                                  getFileGroupsByFilterRequest, groupKey, dataFileGroup)) {
                                return Futures.transform(
                                    createClientFileGroupAndLogQueryStats(
                                        groupKey,
                                        dataFileGroup,
                                        groupKey.getDownloaded(),
                                        getFileGroupsByFilterRequest.preserveZipDirectories()),
                                    clientFileGroup -> {
                                      if (clientFileGroup != null) {
                                        clientFileGroupsBuilder.add(clientFileGroup);
                                      }
                                      return clientFileGroupsBuilder;
                                    },
                                    sequentialControlExecutor);
                              }
                              return Futures.immediateFuture(clientFileGroupsBuilder);
                            },
                            sequentialControlExecutor);
                  }

                  return Futures.transform(
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
   * Creates {@link IcingDataDownloadFileGroupStats} from {@link ClientFileGroup} for remote logging
   * purposes.
   */
  private static Void createFileGroupDetails(ClientFileGroup clientFileGroup) {
    return null;
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

    ListenableFuture<ClientFileGroup> downloadFuture =
        Futures.submitAsync(
            () -> {
              if (downloadFileGroupRequest.listenerOptional().isPresent()) {
                if (downloadMonitorOptional.isPresent()) {
                  downloadMonitorOptional
                      .get()
                      .addDownloadListener(
                          groupName, downloadFileGroupRequest.listenerOptional().get());
                } else {
                  return Futures.immediateFailedFuture(
                      DownloadException.builder()
                          .setDownloadResultCode(
                              DownloadResultCode.DOWNLOAD_MONITOR_NOT_PROVIDED_ERROR)
                          .setMessage(
                              "downloadFileGroup: DownloadListener is present but Download Monitor"
                                  + " is not provided!")
                          .build());
                }
              }

              Optional<DownloadConditions> downloadConditions =
                  downloadFileGroupRequest.downloadConditionsOptional().isPresent()
                      ? Optional.of(
                          ProtoConversionUtil.convert(
                              downloadFileGroupRequest.downloadConditionsOptional().get()))
                      : Optional.absent();
              ListenableFuture<DataFileGroupInternal> downloadFileGroupFuture =
                  mobileDataDownloadManager.downloadFileGroup(
                      groupKey, downloadConditions, customFileGroupValidator);

              return Futures.transformAsync(
                  downloadFileGroupFuture,
                  dataFileGroup -> {
                    return Futures.transform(
                        createClientFileGroup(
                            dataFileGroup,
                            downloadFileGroupRequest.accountOptional().isPresent()
                                ? AccountUtil.serialize(
                                    downloadFileGroupRequest.accountOptional().get())
                                : null,
                            ClientFileGroup.Status.DOWNLOADED,
                            downloadFileGroupRequest.preserveZipDirectories(),
                            mobileDataDownloadManager,
                            sequentialControlExecutor,
                            fileStorage),
                        Preconditions::checkNotNull,
                        sequentialControlExecutor);
                  },
                  sequentialControlExecutor);
            },
            sequentialControlExecutor);

    ListenableFuture<ClientFileGroup> transformFuture =
        Futures.transform(
            downloadFuture,
            clientFileGroup -> {
              if (downloadFileGroupRequest.listenerOptional().isPresent()) {
                downloadFileGroupRequest.listenerOptional().get().onComplete(clientFileGroup);
                if (downloadMonitorOptional.isPresent()) {
                  downloadMonitorOptional.get().removeDownloadListener(groupName);
                }
              }
              return clientFileGroup;
            },
            sequentialControlExecutor);

    Futures.addCallback(
        transformFuture,
        new FutureCallback<ClientFileGroup>() {
          @Override
          public void onSuccess(ClientFileGroup result) {}

          @Override
          public void onFailure(Throwable t) {
            if (downloadFileGroupRequest.listenerOptional().isPresent()
                && downloadMonitorOptional.isPresent()) {
              downloadMonitorOptional.get().removeDownloadListener(groupName);
            }
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
      return Futures.immediateFailedFuture(
          new IllegalArgumentException(
              "downloadFileGroupWithForegroundService: ForegroundDownloadService is not"
                  + " provided!"));
    }

    if (!downloadMonitorOptional.isPresent()) {
      return Futures.immediateFailedFuture(
          DownloadException.builder()
              .setDownloadResultCode(DownloadResultCode.DOWNLOAD_MONITOR_NOT_PROVIDED_ERROR)
              .setMessage(
                  "downloadFileGroupWithForegroundService: Download Monitor is not provided!")
              .build());
    }

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

    ListenableFuture<ClientFileGroup> downloadFuture =
        Futures.transformAsync(
            // Check if requested file group has already been downloaded
            tryToGetDownloadedFileGroup(downloadFileGroupRequest),
            downloadedFileGroupOptional -> {
              // If the file group has already been downloaded, return that one.
              if (downloadedFileGroupOptional.isPresent()) {
                return Futures.immediateFuture(downloadedFileGroupOptional.get());
              }

              // if there is the same on-going request, return that one.
              if (keyToListenableFuture.containsKey(downloadFileGroupRequest.groupName())) {
                // keyToListenableFuture.get must return Non-null since we check the containsKey
                // above.
                // checkNotNull is to suppress false alarm about @Nullable result.
                return Preconditions.checkNotNull(
                    keyToListenableFuture.get(downloadFileGroupRequest.groupName()));
              }

              // Only start the foreground download service when this is the first download
              // request.
              if (keyToListenableFuture.isEmpty()) {
                NotificationUtil.startForegroundDownloadService(
                    context,
                    foregroundDownloadServiceClassOptional.get(),
                    downloadFileGroupRequest.groupName());
              }

              DownloadListener downloadListenerWithNotification =
                  createDownloadListenerWithNotification(downloadFileGroupRequest);
              // The downloadMonitor will trigger the DownloadListener.
              downloadMonitorOptional
                  .get()
                  .addDownloadListener(
                      downloadFileGroupRequest.groupName(), downloadListenerWithNotification);

              Optional<DownloadConditions> downloadConditions =
                  downloadFileGroupRequest.downloadConditionsOptional().isPresent()
                      ? Optional.of(
                          ProtoConversionUtil.convert(
                              downloadFileGroupRequest.downloadConditionsOptional().get()))
                      : Optional.absent();
              ListenableFuture<DataFileGroupInternal> downloadFileGroupFuture =
                  mobileDataDownloadManager.downloadFileGroup(
                      groupKey, downloadConditions, customFileGroupValidator);

              ListenableFuture<ClientFileGroup> transformFuture =
                  Futures.transformAsync(
                      downloadFileGroupFuture,
                      dataFileGroup -> {
                        return Futures.transform(
                            createClientFileGroup(
                                dataFileGroup,
                                downloadFileGroupRequest.accountOptional().isPresent()
                                    ? AccountUtil.serialize(
                                        downloadFileGroupRequest.accountOptional().get())
                                    : null,
                                ClientFileGroup.Status.DOWNLOADED,
                                downloadFileGroupRequest.preserveZipDirectories(),
                                mobileDataDownloadManager,
                                sequentialControlExecutor,
                                fileStorage),
                            Preconditions::checkNotNull,
                            sequentialControlExecutor);
                      },
                      sequentialControlExecutor);

              Futures.addCallback(
                  transformFuture,
                  new FutureCallback<ClientFileGroup>() {
                    @Override
                    public void onSuccess(ClientFileGroup clientFileGroup) {
                      // Currently the MobStore monitor does not support onSuccess so we have to add
                      // callback to the download future here.
                      // TODO(b/148057674): Use the same logic as MDDLite to keep the foreground
                      // download service alive until the client's onComplete finishes.
                      downloadListenerWithNotification.onComplete(clientFileGroup);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                      // Currently the MobStore monitor does not support onFailure so we have to add
                      // callback to the download future here.
                      downloadListenerWithNotification.onFailure(t);
                    }
                  },
                  sequentialControlExecutor);

              keyToListenableFuture.put(downloadFileGroupRequest.groupName(), transformFuture);
              return transformFuture;
            },
            sequentialControlExecutor);

    return downloadFuture;
  }

  /** Helper method to check if file group has been downloaded and return it early. */
  private ListenableFuture<Optional<ClientFileGroup>> tryToGetDownloadedFileGroup(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    String groupName = downloadFileGroupRequest.groupName();
    GroupKey.Builder groupKeyBuilder =
        GroupKey.newBuilder().setGroupName(groupName).setOwnerPackage(context.getPackageName());

    if (downloadFileGroupRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(
          AccountUtil.serialize(downloadFileGroupRequest.accountOptional().get()));
    }
    boolean isDownloadListenerPresent = downloadFileGroupRequest.listenerOptional().isPresent();
    GroupKey groupKey = groupKeyBuilder.build();

    // Get pending and downloaded versions to tell if we should return downloaded version early
    ListenableFuture<Pair<DataFileGroupInternal, DataFileGroupInternal>> fileGroupVersionsFuture =
        Futures.transformAsync(
            mobileDataDownloadManager.getFileGroup(groupKey, /* downloaded = */ false),
            pendingDataFileGroup ->
                Futures.transform(
                    mobileDataDownloadManager.getFileGroup(groupKey, /* downloaded = */ true),
                    downloadedDataFileGroup ->
                        Pair.create(pendingDataFileGroup, downloadedDataFileGroup),
                    sequentialControlExecutor),
            sequentialControlExecutor);

    return Futures.transformAsync(
        fileGroupVersionsFuture,
        fileGroupVersionsPair -> {
          // if pending version is not null, return absent
          if (fileGroupVersionsPair.first != null) {
            return Futures.immediateFuture(Optional.absent());
          }
          // If both groups are null, return group not found failure
          if (fileGroupVersionsPair.second == null) {
            // TODO(b/174808410): Add Logging
            // file group is not pending nor downloaded -- return failure.
            DownloadException failure =
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.GROUP_NOT_FOUND_ERROR)
                    .setMessage("Nothing to download for file group: " + groupKey.getGroupName())
                    .build();
            if (isDownloadListenerPresent) {
              downloadFileGroupRequest.listenerOptional().get().onFailure(failure);
            }
            return Futures.immediateFailedFuture(failure);
          }

          DataFileGroupInternal downloadedDataFileGroup = fileGroupVersionsPair.second;

          // Notify download listener (if present) that file group has been downloaded.
          if (isDownloadListenerPresent) {
            downloadMonitorOptional
                .get()
                .addDownloadListener(
                    downloadFileGroupRequest.groupName(),
                    downloadFileGroupRequest.listenerOptional().get());
          }
          FluentFuture<Optional<ClientFileGroup>> transformFuture =
              FluentFuture.from(
                      createClientFileGroup(
                          downloadedDataFileGroup,
                          downloadFileGroupRequest.accountOptional().isPresent()
                              ? AccountUtil.serialize(
                                  downloadFileGroupRequest.accountOptional().get())
                              : null,
                          ClientFileGroup.Status.DOWNLOADED,
                          downloadFileGroupRequest.preserveZipDirectories(),
                          mobileDataDownloadManager,
                          sequentialControlExecutor,
                          fileStorage))
                  .transform(Preconditions::checkNotNull, sequentialControlExecutor)
                  .transform(
                      clientFileGroup -> {
                        if (isDownloadListenerPresent) {
                          downloadFileGroupRequest
                              .listenerOptional()
                              .get()
                              .onComplete(clientFileGroup);
                          downloadMonitorOptional.get().removeDownloadListener(groupName);
                        }
                        return Optional.of(clientFileGroup);
                      },
                      sequentialControlExecutor);
          transformFuture.addCallback(
              new FutureCallback<Optional<ClientFileGroup>>() {
                @Override
                public void onSuccess(Optional<ClientFileGroup> result) {}

                @Override
                public void onFailure(Throwable t) {
                  if (isDownloadListenerPresent) {
                    downloadMonitorOptional.get().removeDownloadListener(groupName);
                  }
                }
              },
              sequentialControlExecutor);

          return transformFuture;
        },
        sequentialControlExecutor);
  }

  private DownloadListener createDownloadListenerWithNotification(
      DownloadFileGroupRequest downloadRequest) {
    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);

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
          downloadRequest.groupName(),
          notification,
          notificationKey);

      notificationManager.notify(notificationKey, notification.build());
    }

    return new DownloadListener() {
      @Override
      public void onProgress(long currentSize) {
        sequentialControlExecutor.execute(
            () -> {
              // There can be a race condition, where onPausedForConnectivity can be called
              // after onComplete or onFailure which removes the future and the notification.
              if (keyToListenableFuture.containsKey(downloadRequest.groupName())
                  && downloadRequest.showNotifications()
                      == DownloadFileGroupRequest.ShowNotifications.ALL) {
                notification
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setProgress(
                        downloadRequest.groupSizeBytes(),
                        (int) currentSize,
                        /* indeterminate = */ downloadRequest.groupSizeBytes() <= 0);
                notificationManager.notify(notificationKey, notification.build());
              }
              if (downloadRequest.listenerOptional().isPresent()) {
                downloadRequest.listenerOptional().get().onProgress(currentSize);
              }
            });
      }

      @Override
      public void pausedForConnectivity() {
        sequentialControlExecutor.execute(
            () -> {
              // There can be a race condition, where pausedForConnectivity can be called
              // after onComplete or onFailure which removes the future and the notification.
              if (keyToListenableFuture.containsKey(downloadRequest.groupName())
                  && downloadRequest.showNotifications()
                      == DownloadFileGroupRequest.ShowNotifications.ALL) {
                notification
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setContentText(NotificationUtil.getDownloadPausedMessage(context))
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setOngoing(true)
                    // hide progress bar.
                    .setProgress(0, 0, false);
                notificationManager.notify(notificationKey, notification.build());
              }

              if (downloadRequest.listenerOptional().isPresent()) {
                downloadRequest.listenerOptional().get().pausedForConnectivity();
              }
            });
      }

      @Override
      public void onComplete(ClientFileGroup clientFileGroup) {
        sequentialControlExecutor.execute(
            () -> {
              // Clear the notification action.
              if (downloadRequest.showNotifications()
                  == DownloadFileGroupRequest.ShowNotifications.ALL) {
                notification.mActions.clear();

                NotificationUtil.cancelNotificationForKey(context, downloadRequest.groupName());
              }

              keyToListenableFuture.remove(downloadRequest.groupName());
              // If there is no other on-going foreground download, shutdown the
              // ForegroundDownloadService
              if (keyToListenableFuture.isEmpty()) {
                NotificationUtil.stopForegroundDownloadService(
                    context, foregroundDownloadServiceClassOptional.get());
              }

              if (downloadRequest.listenerOptional().isPresent()) {
                downloadRequest.listenerOptional().get().onComplete(clientFileGroup);
              }

              downloadMonitorOptional.get().removeDownloadListener(downloadRequest.groupName());
            });
      }

      @Override
      public void onFailure(Throwable t) {
        sequentialControlExecutor.execute(
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
              keyToListenableFuture.remove(downloadRequest.groupName());

              // If there is no other on-going foreground download, shutdown the
              // ForegroundDownloadService
              if (keyToListenableFuture.isEmpty()) {
                NotificationUtil.stopForegroundDownloadService(
                    context, foregroundDownloadServiceClassOptional.get());
              }

              if (downloadRequest.listenerOptional().isPresent()) {
                downloadRequest.listenerOptional().get().onFailure(t);
              }
              downloadMonitorOptional.get().removeDownloadListener(downloadRequest.groupName());
            });
      }
    };
  }

  @Override
  public void cancelForegroundDownload(String downloadKey) {
    LogUtil.d("%s: CancelForegroundDownload for key = %s", TAG, downloadKey);
    sequentialControlExecutor.execute(
        () -> {
          if (keyToListenableFuture.containsKey(downloadKey)) {
            keyToListenableFuture.get(downloadKey).cancel(true);
          } else {
            // downloadKey is not a file group, attempt cancel with internal MDD Lite instance in
            // case it's a single file uri (cancel call is a noop if internal MDD Lite doesn't know
            // about it).
            singleFileDownloader.cancelForegroundDownload(downloadKey);
          }
        });
  }

  @Override
  public void schedulePeriodicTasks() {
    schedulePeriodicTasksInternal(Optional.absent());
  }

  @Override
  public ListenableFuture<Void> schedulePeriodicBackgroundTasks() {
    return futureSerializer.submit(
        propagateCallable(
            () -> {
              schedulePeriodicTasksInternal(/* constraintOverridesMap = */ Optional.absent());
              return null;
            }),
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> schedulePeriodicBackgroundTasks(
      Optional<Map<String, ConstraintOverrides>> constraintOverridesMap) {
    return futureSerializer.submit(
        propagateCallable(
            () -> {
              schedulePeriodicTasksInternal(constraintOverridesMap);
              return null;
            }),
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
  public ListenableFuture<Void> handleTask(String tag) {
    // All work done here that touches metadata (MobileDataDownloadManager) should be serialized
    // through sequentialControlExecutor.
    switch (tag) {
      case TaskScheduler.MAINTENANCE_PERIODIC_TASK:
        return futureSerializer.submitAsync(
            mobileDataDownloadManager::maintenance, sequentialControlExecutor);

      case TaskScheduler.CHARGING_PERIODIC_TASK:
        ListenableFuture<Void> refreshFileGroupsFuture = refreshFileGroups();
        return Futures.transformAsync(
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
        return Futures.immediateFailedFuture(
            new IllegalArgumentException("Unknown task tag sent to MDD.handleTask() " + tag));
    }
  }

  private ListenableFuture<Void> refreshAndDownload(boolean onWifi) {
    // We will do 2 passes to support 2-step downloads. In each step, we will refresh and then
    // download.
    return FluentFuture.from(refreshFileGroups())
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

    return Futures.whenAllComplete(refreshFutures).call(() -> null, sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> maintenance() {
    return handleTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK);
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

    return Futures.immediateVoidFuture();
  }
}
