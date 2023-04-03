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
import com.google.android.libraries.mobiledatadownload.AddFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.AggregateException;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DeltaFile;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;
import java.util.ArrayList;
import java.util.List;

/** Shared functions for ManifestConfig. */
public final class ManifestConfigHelper {
  public static final String URL_TEMPLATE_CHECKSUM_PLACEHOLDER = "{checksum}";

  private static final String TAG = "ManifestConfigHelper";

  private final MobileDataDownload mobileDataDownload;
  private final Optional<ManifestConfigOverrider> overriderOptional;
  private final List<Account> accounts;
  private final boolean addGroupsWithVariantId;

  /** Creates a new helper for converting manifest configs into data file groups. */
  ManifestConfigHelper(
      MobileDataDownload mobileDataDownload,
      Optional<ManifestConfigOverrider> overriderOptional,
      List<Account> accounts,
      boolean addGroupsWithVariantId) {
    this.mobileDataDownload = mobileDataDownload;
    this.overriderOptional = overriderOptional;
    this.accounts = accounts;
    this.addGroupsWithVariantId = addGroupsWithVariantId;
  }

  /**
   * Reads file groups from {@link ManifestConfig} and adds to MDD after applying the {@link
   * ManifestConfigOverrider} if it's present.
   *
   * <p>This static method encapsulates shared logic between a few populators:
   *
   * <ul>
   *   <li>{@link ManifestFileGroupPopulator}
   *   <li>{@link ManifestConfigFlagPopulator}
   *   <li>{@link LocalManifestFileGroupPopulator}
   *   <li>{@link EmbeddedAssetManifestPopulator}
   * </ul>
   *
   * @param mobileDataDownload The MDD instance
   * @param manifestConfig The proto that contains configs for file groups and modifiers
   * @param overriderOptional An optional overrider that takes manifest config and returns a list of
   *     file groups to be added ot MDD
   * @param accounts A list of accounts that the parsed file groups should be associated with
   * @param addGroupsWithVariantId whether variantId should be included when adding the parsed file
   *     groups
   */
  static ListenableFuture<Void> refreshFromManifestConfig(
      MobileDataDownload mobileDataDownload,
      ManifestConfig manifestConfig,
      Optional<ManifestConfigOverrider> overriderOptional,
      List<Account> accounts,
      boolean addGroupsWithVariantId) {
    ManifestConfigHelper helper =
        new ManifestConfigHelper(
            mobileDataDownload, overriderOptional, accounts, addGroupsWithVariantId);
    return PropagatedFluentFuture.from(helper.applyOverrider(manifestConfig))
        .transformAsync(helper::addAllFileGroups, MoreExecutors.directExecutor())
        .catchingAsync(
            AggregateException.class,
            ex -> Futures.immediateVoidFuture(),
            MoreExecutors.directExecutor());
  }

  /** Adds the specified list of file groups to MDD. */
  ListenableFuture<Void> addAllFileGroups(List<DataFileGroup> fileGroups) {
    List<ListenableFuture<Boolean>> addFileGroupFutures = new ArrayList<>();
    Optional<String> variantId = Optional.absent();

    for (DataFileGroup dataFileGroup : fileGroups) {
      if (dataFileGroup == null || dataFileGroup.getGroupName().isEmpty()) {
        continue;
      }

      // Include variantId if variant is present and helper is configured to do so
      if (addGroupsWithVariantId && !dataFileGroup.getVariantId().isEmpty()) {
        variantId = Optional.of(dataFileGroup.getVariantId());
      }

      AddFileGroupRequest.Builder addFileGroupRequestBuilder =
          AddFileGroupRequest.newBuilder()
              .setDataFileGroup(dataFileGroup)
              .setVariantIdOptional(variantId);

      // Add once without any account
      ListenableFuture<Boolean> addFileGroupFuture =
          mobileDataDownload.addFileGroup(addFileGroupRequestBuilder.build());
      attachLoggingCallback(
          addFileGroupFuture,
          dataFileGroup.getGroupName(),
          /* account= */ Optional.absent(),
          variantId);
      addFileGroupFutures.add(addFileGroupFuture);

      // Add for each account
      for (Account account : accounts) {
        ListenableFuture<Boolean> addFileGroupFutureWithAccount =
            mobileDataDownload.addFileGroup(
                addFileGroupRequestBuilder.setAccountOptional(Optional.of(account)).build());
        attachLoggingCallback(
            addFileGroupFutureWithAccount,
            dataFileGroup.getGroupName(),
            Optional.of(account),
            variantId);
        addFileGroupFutures.add(addFileGroupFutureWithAccount);
      }
    }
    return PropagatedFutures.whenAllComplete(addFileGroupFutures)
        .call(
            () -> {
              AggregateException.throwIfFailed(addFileGroupFutures, "Failed to add file groups");
              return null;
            },
            MoreExecutors.directExecutor());
  }

  private void attachLoggingCallback(
      ListenableFuture<Boolean> addFileGroupFuture,
      String groupName,
      Optional<Account> account,
      Optional<String> variant) {
    PropagatedFutures.addCallback(
        addFileGroupFuture,
        new FutureCallback<Boolean>() {
          @Override
          public void onSuccess(Boolean result) {
            if (result.booleanValue()) {
              LogUtil.d(
                  "%s: Added file group %s with account: %s, variant: %s",
                  TAG,
                  groupName,
                  String.valueOf(account.orNull()),
                  String.valueOf(variant.orNull()));
            } else {
              LogUtil.d(
                  "%s: Failed to add file group %s with account: %s, variant: %s",
                  TAG,
                  groupName,
                  String.valueOf(account.orNull()),
                  String.valueOf(variant.orNull()));
            }
          }

          @Override
          public void onFailure(Throwable t) {
            LogUtil.e(
                t,
                "%s: Failed to add file group %s with account: %s, variant: %s",
                TAG,
                groupName,
                String.valueOf(account.orNull()),
                String.valueOf(variant.orNull()));
          }
        },
        MoreExecutors.directExecutor());
  }

  /** Applies the overrider to the manifest config to generate a list of file groups for adding. */
  ListenableFuture<List<DataFileGroup>> applyOverrider(ManifestConfig manifestConfig) {
    if (overriderOptional.isPresent()) {
      return overriderOptional.get().override(maybeApplyFileUrlTemplate(manifestConfig));
    }
    List<DataFileGroup> results = new ArrayList<>();
    for (ManifestConfig.Entry entry : maybeApplyFileUrlTemplate(manifestConfig).getEntryList()) {
      results.add(entry.getDataFileGroup());
    }
    return Futures.immediateFuture(results);
  }

  /**
   * If file_url_template is populated and file url_to_download field is empty in the {@code
   * ManifestConfig} manifestConfig then construct the url_to_download field using the template.
   *
   * <p>NOTE: If file_url_template is empty then the files are expected to have the complete
   * download URL, validate and throw an {@link IllegalArgumentException} if url_to_download is not
   * populated.
   */
  public static ManifestConfig maybeApplyFileUrlTemplate(ManifestConfig manifestConfig) {
    if (!manifestConfig.hasUrlTemplate()
        || manifestConfig.getUrlTemplate().getFileUrlTemplate().isEmpty()) {
      return validateManifestConfigFileUrls(manifestConfig);
    }
    String fileDownloadUrlTemplate = manifestConfig.getUrlTemplate().getFileUrlTemplate();
    ManifestConfig.Builder updatedManifestConfigBuilder = manifestConfig.toBuilder().clearEntry();

    for (ManifestConfig.Entry entry : manifestConfig.getEntryList()) {
      DataFileGroup.Builder dataFileGroupBuilder = entry.getDataFileGroup().toBuilder().clearFile();
      for (DataFile dataFile : entry.getDataFileGroup().getFileList()) {
        DataFile.Builder dataFileBuilder = dataFile.toBuilder().clearDeltaFile();

        if (dataFile.getUrlToDownload().isEmpty()) {
          dataFileBuilder.setUrlToDownload(
              fileDownloadUrlTemplate.replace(
                  URL_TEMPLATE_CHECKSUM_PLACEHOLDER, dataFile.getChecksum()));
        }

        for (DeltaFile deltaFile : dataFile.getDeltaFileList()) {
          dataFileBuilder.addDeltaFile(
              deltaFile.getUrlToDownload().isEmpty()
                  ? deltaFile.toBuilder()
                      .setUrlToDownload(
                          fileDownloadUrlTemplate.replace(
                              URL_TEMPLATE_CHECKSUM_PLACEHOLDER, deltaFile.getChecksum()))
                      .build()
                  : deltaFile);
        }

        dataFileGroupBuilder.addFile(dataFileBuilder);
      }
      updatedManifestConfigBuilder.addEntry(
          entry.toBuilder().setDataFileGroup(dataFileGroupBuilder));
    }
    return updatedManifestConfigBuilder.build();
  }

  /**
   * Validates that all the files in {@code ManifestConfig} manifestConfig have the url_to_download
   * populated.
   */
  private static ManifestConfig validateManifestConfigFileUrls(ManifestConfig manifestConfig) {
    for (ManifestConfig.Entry entry : manifestConfig.getEntryList()) {
      for (DataFile dataFile : entry.getDataFileGroup().getFileList()) {
        if (dataFile.getUrlToDownload().isEmpty()) {
          throw new IllegalArgumentException(
              String.format("DataFile %s url_to_download is missing.", dataFile.getFileId()));
        }
        for (DeltaFile deltaFile : dataFile.getDeltaFileList()) {
          if (deltaFile.getUrlToDownload().isEmpty()) {
            throw new IllegalArgumentException(
                String.format(
                    "DeltaFile for file %s url_to_download is missing.", dataFile.getFileId()));
          }
        }
      }
    }
    return manifestConfig;
  }
}
