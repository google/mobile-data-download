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

import android.accounts.Account;
import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.AddFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.FileGroupPopulator;
import com.google.android.libraries.mobiledatadownload.GetFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadProtoOpener;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;

/**
 * FileGroupPopulator that reads a file from MDD, containing all slices before filtering.
 *
 * <p>All slices are given to each account dependent and independent filter provided in constructor
 * to perform filtering. Remaining slices returned from each filter will be grouped, deduplicated
 * and formed as a file group and added back to MDD for download. If there is an error in a filter,
 * it can choose one of the following 2 options:
 *
 * <ul>
 *   <li>Return the error in a failed future and as a result it will fail the whole filtering
 *       populator. Pick this option if you think this failure should block all other filters.
 *   <li>Wrap the filter with {@link
 *       com.google.android.libraries.mobiledatadownload.populator.filters.NonThrowingAccountDependentFilter}
 *       to swallow the error and return an empty list in a successful future. Pick this option if
 *       you think this failure should not block all other filters.
 * </ul>
 *
 * <p>Check {@link AccountDependentFilter} and {@link AccountIndependentFilter} for more details.
 */
public final class FilteringPopulator implements FileGroupPopulator {

  private static final String TAG = "FilteringPopulator";

  /** Builder for {@link FilteringPopulator}. */
  public static final class Builder {

    private ImmutableList<AccountIndependentFilter> accountIndependentFilters;
    private ImmutableList<AccountDependentFilter> accountDependentFilters;
    private SynchronousFileStorage fileStorage;
    private String allSlicesFileGroupName;
    private Supplier<ListenableFuture<List<Account>>> accountsSupplier;
    private boolean enableAccountIndependentFileGroup;

    public Builder setAccountDependentFilters(
        ImmutableList<AccountDependentFilter> accountDependentFilters) {
      this.accountDependentFilters = accountDependentFilters;
      return this;
    }

    public Builder setAccountIndependentFilters(
        ImmutableList<AccountIndependentFilter> accountIndependentFilters) {
      this.accountIndependentFilters = accountIndependentFilters;
      return this;
    }

    public Builder setFileStorage(SynchronousFileStorage fileStorage) {
      this.fileStorage = fileStorage;
      return this;
    }

    public Builder setAllSlicesFileGroupName(String allSlicesFileGroupName) {
      this.allSlicesFileGroupName = allSlicesFileGroupName;
      return this;
    }

    public Builder setAccountSupplier(Supplier<ListenableFuture<List<Account>>> accountsSupplier) {
      this.accountsSupplier = accountsSupplier;
      return this;
    }

    public Builder setEnableAccountIndependentFileGroup(boolean enableAccountIndependentFileGroup) {
      this.enableAccountIndependentFileGroup = enableAccountIndependentFileGroup;
      return this;
    }

    public FilteringPopulator build() {
      Preconditions.checkState(fileStorage != null, "Must call setFileStorage() before build().");
      Preconditions.checkState(
          allSlicesFileGroupName != null, "Must call setAllSlicesFileGroupName() before build().");
      Preconditions.checkState(
          accountsSupplier != null, "Must call setAccountSupplier() before build().");

      if (accountIndependentFilters == null) {
        this.accountIndependentFilters = ImmutableList.of();
      }

      if (accountDependentFilters == null) {
        this.accountDependentFilters = ImmutableList.of();
      }

      return new FilteringPopulator(this);
    }
  }

  private final ImmutableList<AccountIndependentFilter> accountIndependentFilters;
  private final ImmutableList<AccountDependentFilter> accountDependentFilters;
  private final SynchronousFileStorage fileStorage;
  private final String allSlicesFileGroupName;
  private final Supplier<ListenableFuture<List<Account>>> accountsSupplier;
  private final boolean enableAccountIndependentFileGroup;

  /** Returns a Builder for {@link FilteringPopulator}. */
  public static Builder builder() {
    return new Builder();
  }

  // The allSlicesFileGroupName should point to a file group in MDD, that contains only
  // a single file. Any subsequent files after that will be ignored by this class.
  // The file is assumed to be a DataFileGroup proto in binary format whose DataFile list will be
  // filtered.  The thus filtered DataFileGroup will be added to MDD.
  public FilteringPopulator(Builder builder) {
    this.accountIndependentFilters = builder.accountIndependentFilters;
    this.accountDependentFilters = builder.accountDependentFilters;
    this.fileStorage = builder.fileStorage;
    this.allSlicesFileGroupName = builder.allSlicesFileGroupName;
    this.accountsSupplier = builder.accountsSupplier;
    this.enableAccountIndependentFileGroup = builder.enableAccountIndependentFileGroup;
  }

  /** @deprecated use {@link Builder} instead */
  @Deprecated
  public FilteringPopulator(
      ImmutableList<AccountDependentFilter> accountDependentFilters,
      SynchronousFileStorage fileStorage,
      String allSlicesFileGroupName,
      Supplier<ListenableFuture<List<Account>>> accountsSupplier) {
    this.accountIndependentFilters = ImmutableList.of();
    this.accountDependentFilters = accountDependentFilters;
    this.fileStorage = fileStorage;
    this.allSlicesFileGroupName = allSlicesFileGroupName;
    this.accountsSupplier = accountsSupplier;
    this.enableAccountIndependentFileGroup = false;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
    LogUtil.d("%s: Refresh file groups config with MDD", TAG);

    ListenableFuture<Void> refreshFileGroupFuture =
        PropagatedFluentFuture.from(
                mobileDataDownload.getFileGroup(
                    GetFileGroupRequest.newBuilder().setGroupName(allSlicesFileGroupName).build()))
            .transformAsync(
                clientFileGroup -> addSlicesToMdd(mobileDataDownload, clientFileGroup),
                MoreExecutors.directExecutor());

    PropagatedFutures.addCallback(
        refreshFileGroupFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            LogUtil.d("%s: Finished refreshing file group", TAG);
          }

          @Override
          public void onFailure(Throwable t) {
            LogUtil.e(t, "%s: Failed to add file group", TAG);
          }
        },
        MoreExecutors.directExecutor());

    return refreshFileGroupFuture;
  }

  private ListenableFuture<Void> addSlicesToMdd(
      MobileDataDownload mobileDataDownload, @Nullable ClientFileGroup clientFileGroup) {
    if (clientFileGroup == null || clientFileGroup.getFileCount() == 0) {
      LogUtil.d("%s: All slices file group unavailable or empty.", TAG);
      return Futures.immediateFuture(null);
    }

    String fileUri = clientFileGroup.getFile(0).getFileUri();
    DataFileGroup allSlicesFileGroup;
    try {
      allSlicesFileGroup =
          fileStorage.open(
              Uri.parse(fileUri), ReadProtoOpener.create(DataFileGroup.getDefaultInstance()));
    } catch (IOException e) {
      LogUtil.e(e, "%s: Failed to read file using mobstore and parsing to proto", TAG);
      return Futures.immediateFailedFuture(e);
    }

    List<DataFile> filesToFilter = allSlicesFileGroup.getFileList();

    // Get common datafiles from account-agnostic accountIndependentFilters
    ImmutableList.Builder<ListenableFuture<List<DataFile>>> sharedFuturesListBuilder =
        ImmutableList.<ListenableFuture<List<DataFile>>>builder();
    for (AccountIndependentFilter filter : accountIndependentFilters) {
      try {
        sharedFuturesListBuilder.add(filter.apply(filesToFilter));
      } catch (Exception e) {
        // TODO(b/145724682) Log exceptions to clearcut
        LogUtil.e(e, "%s: Failed to apply filter", TAG);
        return Futures.immediateFailedFuture(e);
      }
    }
    ImmutableList<ListenableFuture<List<DataFile>>> sharedFuturesList =
        sharedFuturesListBuilder.build();

    return PropagatedFutures.transformAsync(
        accountsSupplier.get(),
        accountList -> {
          ImmutableList.Builder<ListenableFuture<Void>> addFileGroupsToMddFutures =
              ImmutableList.<ListenableFuture<Void>>builder();
          if (enableAccountIndependentFileGroup) {
            addFileGroupsToMddFutures.add(
                addAccountIndependentFileGroup(
                    mobileDataDownload, allSlicesFileGroup, sharedFuturesList));
          }
          for (Account account : accountList) {
            addFileGroupsToMddFutures.add(
                addAccountDependentFileGroup(
                    mobileDataDownload,
                    account,
                    filesToFilter,
                    allSlicesFileGroup,
                    sharedFuturesList));
          }
          return PropagatedFutures.whenAllSucceed(addFileGroupsToMddFutures.build())
              .call(() -> null, MoreExecutors.directExecutor());
        },
        MoreExecutors.directExecutor());
  }

  private static ListenableFuture<Void> addAccountIndependentFileGroup(
      MobileDataDownload mobileDataDownload,
      DataFileGroup allSlicesFileGroup,
      ImmutableList<ListenableFuture<List<DataFile>>> sharedFuturesList) {
    return PropagatedFluentFuture.from(
            mergeDataFilesAndCreateFileGroup(allSlicesFileGroup, sharedFuturesList))
        .transformAsync(
            slicesToAddFileGroup ->
                mobileDataDownload.addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setDataFileGroup(slicesToAddFileGroup)
                        .build()),
            MoreExecutors.directExecutor())
        .transform(
            result -> {
              if (result.booleanValue()) {
                LogUtil.d("%s: Added public filegroup to MDD", TAG);
              } else {
                LogUtil.d("%s: Didn't add public filegroup to MDD", TAG);
              }
              return null;
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<Void> addAccountDependentFileGroup(
      MobileDataDownload mobileDataDownload,
      Account account,
      List<DataFile> filesToFilter,
      DataFileGroup allSlicesFileGroup,
      ImmutableList<ListenableFuture<List<DataFile>>> sharedFuturesList) {

    ImmutableList.Builder<ListenableFuture<List<DataFile>>> futuresListBuilder =
        ImmutableList.<ListenableFuture<List<DataFile>>>builder();

    for (AccountDependentFilter filter : accountDependentFilters) {
      try {
        futuresListBuilder.add(filter.apply(account, filesToFilter));
      } catch (Exception e) {
        // TODO(b/145724682) Log exceptions to clearcut
        LogUtil.e(e, "%s: Failed to apply filter", TAG);
        return Futures.immediateFailedFuture(e);
      }
    }

    return PropagatedFluentFuture.from(
            mergeDataFilesAndCreateFileGroup(
                allSlicesFileGroup, futuresListBuilder.addAll(sharedFuturesList).build()))
        .transformAsync(
            slicesToAddFileGroup ->
                mobileDataDownload.addFileGroup(
                    AddFileGroupRequest.newBuilder()
                        .setAccountOptional(Optional.of(account))
                        .setDataFileGroup(slicesToAddFileGroup)
                        .build()),
            MoreExecutors.directExecutor())
        .transform(
            result -> {
              if (result.booleanValue()) {
                LogUtil.d("%s: Added file group to MDD", TAG);
              } else {
                LogUtil.d("%s: Didn't add file group to MDD", TAG);
              }
              return null;
            },
            MoreExecutors.directExecutor());
  }

  private static ListenableFuture<DataFileGroup> mergeDataFilesAndCreateFileGroup(
      DataFileGroup allSlicesFileGroup,
      ImmutableList<ListenableFuture<List<DataFile>>> futuresList) {
    return PropagatedFutures.whenAllSucceed(futuresList)
        .call(
            () -> {
              ImmutableSet.Builder<DataFile> filteredFilesBuilder =
                  ImmutableSet.<DataFile>builder();
              for (ListenableFuture<List<DataFile>> future : futuresList) {
                filteredFilesBuilder.addAll(Futures.getDone(future));
              }

              return allSlicesFileGroup.toBuilder()
                  .clearFile()
                  .addAllFile(filteredFilesBuilder.build())
                  .build();
            },
            MoreExecutors.directExecutor());
  }

  // TODO(b/144746701) Rename to AccountDependentFilter
  /**
   * Interface for account sensitive file filters.
   *
   * <p>A AccountDependentFilter implementation is sensitive to account and filtering data files
   * based on account related data.
   *
   * <p>A Filter implementation should consider returning a failed future or a successful future
   * with an empty DataFile list in cases of errors or exceptions. Returning a failed future will
   * fail the populator and prevent results from other successful filters from being added to MDD.
   * While returning a successful future with an empty data file list will just have the populator
   * skip expected results from the current filter.
   */
  public interface AccountDependentFilter {
    ListenableFuture<List<DataFile>> apply(Account account, List<DataFile> filesToFilter);
  }

  /**
   * Interface for account agnostic file filters.
   *
   * <p>An AccountIndependentFilter implementation is agnostic to account and filters without
   * account related data.
   *
   * <p>An AccountIndependentFilter implementation should consider returning a failed future or a
   * successful future with an empty DataFile list in cases of errors or exceptions. Returning a
   * failed future will fail the populator and prevent results from other successful filters from
   * being added to MDD. While returning a successful future with an empty data file list will just
   * have the populator skip expected results from the current filter.
   */
  public interface AccountIndependentFilter {
    ListenableFuture<List<DataFile>> apply(List<DataFile> filesToFilter);
  }
}
