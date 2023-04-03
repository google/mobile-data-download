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

import android.accounts.Account;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mdi.download.MetadataProto.GroupKey;
import com.google.android.libraries.mobiledatadownload.AddFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.DownloadFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.GetFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.GetFileGroupsByFilterRequest;
import com.google.android.libraries.mobiledatadownload.ImportFilesRequest;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.ReadDataFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.RemoveFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.RemoveFileGroupsByFilterRequest;
import com.google.android.libraries.mobiledatadownload.RemoveFileGroupsByFilterResponse;
import com.google.android.libraries.mobiledatadownload.SingleFileDownloadRequest;
import com.google.android.libraries.mobiledatadownload.TaskScheduler.ConstraintOverrides;
import com.google.android.libraries.mobiledatadownload.UsageEvent;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Table;
import com.google.common.flogger.GoogleLogger;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Fake implementation of {@link MobileDataDownload}.
 *
 * <p>FakeMobileDataDownload is thread-safe. All the apis part of MobileDataDownload interface can
 * be invoked from multiple threads safely. Thread safety for helper functions (like setUpFileGroup,
 * setThrowable, setThrowableOnFileGroup, get*Params apis etc) is not provided. To avoid race
 * conditions, all the set up functions should be invoked at the beginning of the test before
 * testing the business logic and get*Params apis should be invoked only after all the pending tasks
 * are done. Refer <internal> to wait for all the pending background asynchronous tasks to complete.
 */
public final class FakeMobileDataDownload implements MobileDataDownload {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final List<AddFileGroupRequest> addFileGroupParamsList = new ArrayList<>();
  private final List<ClientFileGroup> downloadedFileGroupList = new ArrayList<>();
  private final List<GetFileGroupRequest> getFileGroupParamsList = new ArrayList<>();
  private final List<String> handleTaskParamsList = new ArrayList<>();
  private final List<ClientFileGroup> pendingFileGroupList = new ArrayList<>();
  private final Map<MethodType, Throwable> throwableMap = new EnumMap<>(MethodType.class);
  private final Table<MethodType, GroupKey, Throwable> methodTypeGroupKeyToThrowableTable =
      HashBasedTable.create();
  private final List<DownloadFileGroupRequest> downloadFileGroupParamsList = new ArrayList<>();
  private final List<DownloadFileGroupRequest> downloadFileGroupWithForegroundServiceParamsList =
      new ArrayList<>();
  private final List<RemoveFileGroupRequest> removeFileGroupParamsList = new ArrayList<>();
  private final Map<String, byte[]> remoteFilesMap = new HashMap<>();

  private final Optional<SynchronousFileStorage> storageOptional;
  private final Executor sequentialControlExecutor;

  /** Enum for different MDD methods. Used to set Throwable. */
  public enum MethodType {
    ADD_FILE_GROUP,
    GET_FILE_GROUP,
    REMOVE_FILE_GROUP,
    DOWNLOAD_FILE,
    DOWNLOAD_FILE_FOREGROUND,
  }

  /** {@code storageOptional} must be present to download files set through setUpRemoteFile. */
  FakeMobileDataDownload(Optional<SynchronousFileStorage> storageOptional, Executor executor) {
    this.storageOptional = storageOptional;
    this.sequentialControlExecutor = MoreExecutors.newSequentialExecutor(executor);
  }

  public static FakeMobileDataDownload createFakeMddWithFileStorage(
      SynchronousFileStorage storage) {
    return new FakeMobileDataDownload(
        Optional.of(storage), MoreExecutors.newSequentialExecutor(MoreExecutors.directExecutor()));
  }

  private static List<ClientFileGroup> getMatchingFileGroups(
      GroupKey groupKey, List<ClientFileGroup> fileGroupList) {
    logger.atConfig().log("#getMatchingFileGroups: %s, %s", groupKey, fileGroupList);
    List<ClientFileGroup> filteredFileGroupList = new ArrayList<>();
    for (ClientFileGroup fileGroup : fileGroupList) {
      // Check for group name match.
      if (groupKey.hasGroupName() && !groupKey.getGroupName().equals(fileGroup.getGroupName())) {
        continue;
      }

      // Check for owner_package match.
      if (groupKey.hasOwnerPackage()
          && !groupKey.getOwnerPackage().equals(fileGroup.getOwnerPackage())) {
        continue;
      }

      // Check for account match.
      if (groupKey.hasAccount() && !groupKey.getAccount().equals(fileGroup.getAccount())) {
        continue;
      }

      // Check for variant id match.
      if (groupKey.hasVariantId() && !groupKey.getVariantId().equals(fileGroup.getVariantId())) {
        continue;
      }

      filteredFileGroupList.add(fileGroup);
    }

    return filteredFileGroupList;
  }

  /**
   * Sets {@link ClientFileGroup} instance to use in getFileGroup, getFileGroupsByFilter and
   * downloadFileGroup methods.
   *
   * <p>getFileGroup, getFileGroupsByFilter, downloadFileGroup methods will look for a match in all
   * the file groups set using this api before returning the result.
   *
   * @param clientFileGroup ClientFileGroup instance.
   * @param downloaded if true, assumes the ClientFileGroup instance is downloaded, else download is
   *     pending.
   */
  public void setUpFileGroup(ClientFileGroup clientFileGroup, boolean downloaded) {
    if (downloaded) {
      downloadedFileGroupList.add(
          clientFileGroup.toBuilder().setStatus(ClientFileGroup.Status.DOWNLOADED).build());
    } else {
      pendingFileGroupList.add(
          clientFileGroup.toBuilder().setStatus(ClientFileGroup.Status.PENDING).build());
    }
  }

  /**
   * Returns the list of parameters that addFileGroup method was invocated with.
   *
   * @return List of all the requests of type {@link AddFileGroupRequest} that addFileGroup method
   *     was called with.
   */
  public ImmutableList<AddFileGroupRequest> getAddFileGroupParamsList() {
    return ImmutableList.copyOf(addFileGroupParamsList);
  }

  /**
   * Returns the list of parameters that removeFileGroup method was invocated with.
   *
   * @return List of all the requests of type {@link RemoveFileGroupRequest} that removeFileGroup
   *     method was called with.
   */
  public ImmutableList<RemoveFileGroupRequest> getRemoveFileGroupParamsList() {
    return ImmutableList.copyOf(removeFileGroupParamsList);
  }

  /**
   * Returns the list of parameters that downloadFileGroup method was invocated with.
   *
   * @return List of all the requests of type {@link DownloadFileGroupRequest} that
   *     downloadFileGroup method was called with.
   */
  public ImmutableList<DownloadFileGroupRequest> getDownloadFileGroupParamsList() {
    return ImmutableList.copyOf(downloadFileGroupParamsList);
  }

  /**
   * Returns the list of parameters that downloadFileGroupWithForegroundService method was invocated
   * with.
   *
   * @return List of all the requests of type {@link DownloadFileGroupRequest} that
   *     downloadFileGroup method was called with.
   */
  public ImmutableList<DownloadFileGroupRequest>
      getDownloadFileGroupWithForegroundServiceParamsList() {
    return ImmutableList.copyOf(downloadFileGroupWithForegroundServiceParamsList);
  }

  /**
   * Returns the list of parameters that getFileGroup method was invocated with.
   *
   * @return List of all the requests of type {@link GetFileGroupRequest} that getFileGroup method
   *     was called with.
   */
  public ImmutableList<GetFileGroupRequest> getGetFileGroupParamsList() {
    return ImmutableList.copyOf(getFileGroupParamsList);
  }

  /** Returns the list of parameters that handleTask method was invocated with. */
  public ImmutableList<String> getHandleTaskParamsList() {
    return ImmutableList.copyOf(handleTaskParamsList);
  }

  /**
   * Sets {@code throwable} to throw on invocation of a method identified by {@code methodType}
   *
   * @param methodType enum to identify method.
   * @param throwable Throwable to throw on method's invocation.
   */
  public void setThrowable(MethodType methodType, Throwable throwable) {
    this.throwableMap.put(methodType, throwable);
  }

  /**
   * Sets {@code throwable} to throw on invocation of method identified by {@code methodType} when
   * the properties set using {@code groupName}, {@code variantIdOptional}, {@code accountOptional}
   * matches with the filegroup on which the method is invoked.
   *
   * @param methodType enum to identify method.
   * @param groupName Name of the file group.
   * @param accountOptional Account of the file group. Setting this is optional.
   * @param variantIdOptional Variant Id of the file group. Setting this is optional.
   * @param throwable Throwable to throw.
   *     <p>If throwable is set using both #setThrowable and #setThrowableOnFileGroup for a method,
   *     priority is given to throwable set through the latter.
   */
  public void setThrowableOnFileGroup(
      MethodType methodType,
      String groupName,
      Optional<Account> accountOptional,
      Optional<String> variantIdOptional,
      Throwable throwable) {
    if (methodType != MethodType.GET_FILE_GROUP) {
      throw new IllegalArgumentException(
          "setThrowableOnFileGroup is currently only supported for getFileGroup method.");
    }
    GroupKey.Builder groupKeyBuilder = GroupKey.newBuilder();
    groupKeyBuilder.setGroupName(groupName);
    if (accountOptional.isPresent()) {
      groupKeyBuilder.setAccount(AccountUtil.serialize(accountOptional.get()));
    }
    if (variantIdOptional.isPresent()) {
      groupKeyBuilder.setVariantId(variantIdOptional.get());
    }
    methodTypeGroupKeyToThrowableTable.put(methodType, groupKeyBuilder.build(), throwable);
  }

  /**
   * Set file corresponding to a url.
   *
   * <p>Used by downloadFile and downloadFileWithForegroundService. If the
   * SingleFileDownloadRequest#urlToDownload matches any of the set url, file is created at
   * SingleFileDownloadRequest#destinationFileUri with the corresponding set content.
   *
   * <p>Setting content for an already existing url will replace the existing contents.
   */
  public void setUpRemoteFile(String urlToDownload, byte[] content) {
    // NOTE: If a client is using AssetFileBackend, then the corresponding test assets can be
    // used here if the parameter type is Uri instead of byte[].
    // Note: Here byte[] will be stored in memory. Uri avoids this and supports large files cleanly.
    remoteFilesMap.put(urlToDownload, content);
  }

  @Override
  public ListenableFuture<Boolean> addFileGroup(AddFileGroupRequest addFileGroupRequest) {
    logger.atInfo().log("#addFileGroup: %s", addFileGroupRequest);
    Throwable addFileGroupThrowable = throwableMap.get(MethodType.ADD_FILE_GROUP);
    if (addFileGroupThrowable != null) {
      return Futures.immediateFailedFuture(addFileGroupThrowable);
    }
    addFileGroupParamsList.add(addFileGroupRequest);

    // Let addFileGroup induce realistic behavior.
    // Wrap in background executor because this might do disk reads.
    return PropagatedFutures.submitAsync(
        () -> {
          setUpFileGroup(toClientFileGroup(addFileGroupRequest), false);
          return Futures.immediateFuture(true);
        },
        sequentialControlExecutor);
  }

  private ClientFileGroup toClientFileGroup(AddFileGroupRequest addFileGroupRequest) {
    ClientFileGroup.Builder clientFileGroupBuilder =
        ClientFileGroup.newBuilder()
            .setGroupName(addFileGroupRequest.dataFileGroup().getGroupName());
    if (addFileGroupRequest.accountOptional().isPresent()) {
      clientFileGroupBuilder.setAccount(
          AccountUtil.serialize(addFileGroupRequest.accountOptional().get()));
    }
    if (addFileGroupRequest.dataFileGroup().hasOwnerPackage()) {
      clientFileGroupBuilder.setOwnerPackage(addFileGroupRequest.dataFileGroup().getOwnerPackage());
    }
    if (addFileGroupRequest.variantIdOptional().isPresent()) {
      clientFileGroupBuilder.setVariantId(addFileGroupRequest.variantIdOptional().get());
    }
    for (DataFile dataFile : addFileGroupRequest.dataFileGroup().getFileList()) {
      ClientFile.Builder clientFileBuilder =
          ClientFile.newBuilder().setFileId(dataFile.getFileId());
      if (dataFile.hasUrlToDownload()) {
        String urlToDownload = dataFile.getUrlToDownload();
        clientFileBuilder.setFileUri(getMobstoreUriForRemoteFile(urlToDownload).toString());
        maybeSetUpFileAtUri(urlToDownload);
      }
      clientFileGroupBuilder.addFile(clientFileBuilder);
    }

    return clientFileGroupBuilder.build();
  }

  private void maybeSetUpFileAtUri(String urlToDownload) {
    if (storageOptional.isPresent() && remoteFilesMap.containsKey(urlToDownload)) {
      try {
        Uri mobstoreUri = getMobstoreUriForRemoteFile(urlToDownload);
        storageOptional
            .get()
            .open(mobstoreUri, WriteStreamOpener.create())
            .write(remoteFilesMap.get(urlToDownload));
        logger.atInfo().log(
            "Writing file for URL %s to Mobstore URI: %s", urlToDownload, mobstoreUri);
      } catch (IOException e) {
        logger.atSevere().withCause(e).log("Mobstore file write failed");
      }
    } else {
      logger.atConfig().log(
          "No file set for %s. Consider using #setUpRemoteFile if a download is requested.",
          urlToDownload);
    }
  }

  private static Uri getMobstoreUriForRemoteFile(String urlToDownload) {
    return AndroidUri.builder(ApplicationProvider.getApplicationContext())
        .setModule("fakemddtest")
        .setRelativePath(String.valueOf(Integer.valueOf(urlToDownload.hashCode())))
        .build();
  }

  @Override
  public ListenableFuture<Boolean> removeFileGroup(RemoveFileGroupRequest removeFileGroupRequest) {
    Throwable removeFileGroupThrowable = throwableMap.get(MethodType.REMOVE_FILE_GROUP);
    if (removeFileGroupThrowable != null) {
      return Futures.immediateFailedFuture(removeFileGroupThrowable);
    }
    removeFileGroupParamsList.add(removeFileGroupRequest);
    return PropagatedFutures.submitAsync(
        () -> Futures.immediateFuture(true), sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<RemoveFileGroupsByFilterResponse> removeFileGroupsByFilter(
      RemoveFileGroupsByFilterRequest removeFileGroupsByFilterRequest) {
    return PropagatedFutures.submitAsync(
        () ->
            Futures.immediateFuture(
                RemoveFileGroupsByFilterResponse.newBuilder().setRemovedFileGroupsCount(0).build()),
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<DataFileGroup> readDataFileGroup(
      ReadDataFileGroupRequest readDataFileGroupRequest) {
    return Futures.immediateFailedFuture(new UnsupportedOperationException());
  }

  @Override
  public ListenableFuture<ClientFileGroup> getFileGroup(GetFileGroupRequest getFileGroupRequest) {
    // Construct GroupKey from getFileGroupRequest.
    GroupKey.Builder groupKeyBuilder = GroupKey.newBuilder();
    groupKeyBuilder.setGroupName(getFileGroupRequest.groupName());
    if (getFileGroupRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(
          AccountUtil.serialize(getFileGroupRequest.accountOptional().get()));
    }
    if (getFileGroupRequest.variantIdOptional().isPresent()) {
      groupKeyBuilder.setVariantId(getFileGroupRequest.variantIdOptional().get());
    }
    GroupKey groupKey = groupKeyBuilder.build();

    // Throw exception if a throwable is set.
    Throwable getFileGroupThrowable =
        methodTypeGroupKeyToThrowableTable.get(MethodType.GET_FILE_GROUP, groupKey);
    if (getFileGroupThrowable == null) {
      getFileGroupThrowable = throwableMap.get(MethodType.GET_FILE_GROUP);
    }
    if (getFileGroupThrowable != null) {
      return Futures.immediateFailedFuture(getFileGroupThrowable);
    }
    getFileGroupParamsList.add(getFileGroupRequest);
    return PropagatedFutures.submitAsync(
        () -> {
          List<ClientFileGroup> fileGroupList =
              getMatchingFileGroups(groupKeyBuilder.build(), downloadedFileGroupList);
          return Futures.immediateFuture(Iterables.getFirst(fileGroupList, null));
        },
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<ImmutableList<ClientFileGroup>> getFileGroupsByFilter(
      GetFileGroupsByFilterRequest getFileGroupsByFilterRequest) {
    return PropagatedFutures.submitAsync(
        () -> {
          List<ClientFileGroup> allFileGroups = new ArrayList<>(downloadedFileGroupList);
          allFileGroups.addAll(pendingFileGroupList);

          if (getFileGroupsByFilterRequest.includeAllGroups()) {
            return Futures.immediateFuture(ImmutableList.copyOf(allFileGroups));
          }

          GroupKey.Builder groupKeyBuilder = GroupKey.newBuilder();
          if (getFileGroupsByFilterRequest.groupNameOptional().isPresent()) {
            groupKeyBuilder.setGroupName(getFileGroupsByFilterRequest.groupNameOptional().get());
          }
          if (getFileGroupsByFilterRequest.accountOptional().isPresent()) {
            groupKeyBuilder.setAccount(
                AccountUtil.serialize(getFileGroupsByFilterRequest.accountOptional().get()));
          }

          return Futures.immediateFuture(
              ImmutableList.copyOf(getMatchingFileGroups(groupKeyBuilder.build(), allFileGroups)));
        },
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Void> importFiles(ImportFilesRequest importFilesRequest) {
    return Futures.immediateVoidFuture();
  }

  /**
   * If a file is set using setUpRemoteFile for {@code urlToDownload}, the contents will be copied
   * to {@code destinationFileUri}.
   */
  private void downloadFileIfSet(String urlToDownload, Uri destinationFileUri) throws IOException {
    if (!remoteFilesMap.containsKey(urlToDownload)) {
      logger.atWarning().log(
          "No file set for %s using setUpRemoteFile. Download request is a no-op.", urlToDownload);
      return;
    }

    if (!storageOptional.isPresent()) {
      logger.atSevere().log("Storage not set.");
      return;
    }

    try (OutputStream out =
        storageOptional.get().open(destinationFileUri, WriteStreamOpener.create())) {
      out.write(remoteFilesMap.get(urlToDownload));
    }
  }

  /**
   * Copies file to the singleFileDownloadRequest#destinationFileUri if set using {@code
   * setUpRemoteFile}
   *
   * <p>Storage needs to be present to copy the file to destinationFileUri and corresponding backend
   * needs to be added to the storage. Throws UnsupportedFileStorageOperation if corresponding
   * backend is not set.
   */
  @Override
  public ListenableFuture<Void> downloadFile(SingleFileDownloadRequest singleFileDownloadRequest) {
    Throwable throwable = throwableMap.get(MethodType.DOWNLOAD_FILE);
    if (throwable != null) {
      return Futures.immediateFailedFuture(throwable);
    }
    return PropagatedFutures.submitAsync(
        () -> {
          try {
            downloadFileIfSet(
                singleFileDownloadRequest.urlToDownload(),
                singleFileDownloadRequest.destinationFileUri());
          } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
          }

          return Futures.immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<ClientFileGroup> downloadFileGroup(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    logger.atInfo().log("#downloadFileGroup: %s", downloadFileGroupRequest);
    downloadFileGroupParamsList.add(downloadFileGroupRequest);
    return PropagatedFutures.submitAsync(
        () -> downloadFileGroupInternal(downloadFileGroupRequest), sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<ClientFileGroup> downloadFileGroupWithForegroundService(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    logger.atInfo().log("#downloadFileGroupWithForegroundService: %s", downloadFileGroupRequest);
    downloadFileGroupWithForegroundServiceParamsList.add(downloadFileGroupRequest);
    return PropagatedFutures.submitAsync(
        () -> downloadFileGroupInternal(downloadFileGroupRequest), sequentialControlExecutor);
  }

  private ListenableFuture<ClientFileGroup> downloadFileGroupInternal(
      DownloadFileGroupRequest downloadFileGroupRequest) {
    logger.atConfig().log("#downloadFileGroupInternal: %s", downloadFileGroupRequest);
    GroupKey.Builder groupKeyBuilder = GroupKey.newBuilder();
    groupKeyBuilder.setGroupName(downloadFileGroupRequest.groupName());
    if (downloadFileGroupRequest.accountOptional().isPresent()) {
      groupKeyBuilder.setAccount(
          AccountUtil.serialize(downloadFileGroupRequest.accountOptional().get()));
    }
    if (downloadFileGroupRequest.variantIdOptional().isPresent()) {
      groupKeyBuilder.setVariantId(downloadFileGroupRequest.variantIdOptional().get());
    }

    GroupKey groupKey = groupKeyBuilder.build();

    List<ClientFileGroup> fileGroupList = getMatchingFileGroups(groupKey, downloadedFileGroupList);
    if (!fileGroupList.isEmpty()) {
      return Futures.immediateFuture(fileGroupList.get(0));
    }

    fileGroupList = getMatchingFileGroups(groupKey, pendingFileGroupList);
    // If there is no match found in downloaded list, look for in pending list and update the
    // status.
    if (!fileGroupList.isEmpty()) {
      ClientFileGroup fileGroup = fileGroupList.get(0);
      ClientFileGroup downloadedFileGroup =
          fileGroup.toBuilder().setStatus(ClientFileGroup.Status.DOWNLOADED).build();
      pendingFileGroupList.remove(fileGroup);
      downloadedFileGroupList.add(downloadedFileGroup);
      return Futures.immediateFuture(downloadedFileGroup);
    }

    return Futures.immediateFailedFuture(
        DownloadException.builder()
            .setDownloadResultCode(DownloadResultCode.GROUP_NOT_FOUND_ERROR)
            .build());
  }

  /**
   * Copies file to the singleFileDownloadRequest#destinationFileUri if set using {@code
   * setUpRemoteFile}
   *
   * <p>Storage needs to present to copy the file to destinationFileUri and corresponding backend
   * needs to be added to the storage. Throws UnsupportedFileStorageOperation if corresponding
   * backend is not set.
   */
  @Override
  public ListenableFuture<Void> downloadFileWithForegroundService(
      SingleFileDownloadRequest singleFileDownloadRequest) {
    Throwable throwable = throwableMap.get(MethodType.DOWNLOAD_FILE_FOREGROUND);
    if (throwable != null) {
      return Futures.immediateFailedFuture(throwable);
    }
    return PropagatedFutures.submitAsync(
        () -> {
          try {
            downloadFileIfSet(
                singleFileDownloadRequest.urlToDownload(),
                singleFileDownloadRequest.destinationFileUri());
          } catch (IOException e) {
            return Futures.immediateFailedFuture(e);
          }

          return Futures.immediateVoidFuture();
        },
        sequentialControlExecutor);
  }

  @Override
  public void cancelForegroundDownload(String downloadKey) {}

  @Override
  public ListenableFuture<Void> maintenance() {
    return Futures.immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> collectGarbage() {
    return Futures.immediateVoidFuture();
  }

  @Override
  public void schedulePeriodicTasks() {}

  @Override
  public ListenableFuture<Void> schedulePeriodicBackgroundTasks() {
    return Futures.immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> schedulePeriodicBackgroundTasks(
      Optional<Map<String, ConstraintOverrides>> constraintOverridesMap) {
    return Futures.immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> cancelPeriodicBackgroundTasks() {
    return Futures.immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> handleTask(String tag) {
    handleTaskParamsList.add(tag);
    return Futures.immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> clear() {
    return Futures.immediateVoidFuture();
  }

  @Override
  public String getDebugInfoAsString() {
    return "";
  }

  @Override
  public ListenableFuture<Void> reportUsage(UsageEvent usageEvent) {
    return Futures.immediateVoidFuture();
  }
}
