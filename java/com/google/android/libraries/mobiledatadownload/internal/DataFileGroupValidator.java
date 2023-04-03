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
package com.google.android.libraries.mobiledatadownload.internal;

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile.DiffDecoder;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.TransformProto.Transforms;

/** DataFileGroupValidator - validates the passed in DataFileGroup */
public class DataFileGroupValidator {

  private static final String TAG = "DataFileGroupValidator";

  /**
   * Checks if the data file group that we received is a valid group. A group is valid if all the
   * data required to download it is present. If any field that is not required is set, it will be
   * ignored.
   *
   * <p>This is just a sanity check. For example, it doesn't check if the file is present at the
   * given location.
   *
   * @param dataFileGroup The data file group on which sanity check needs to be done.
   * @return true if the group is valid.
   */
  // TODO(b/124072754): Change to package private once all code is refactored.
  public static boolean isValidGroup(
      DataFileGroupInternal dataFileGroup, Context context, Flags flags) {
    // Check if the group name is empty.
    if (dataFileGroup.getGroupName().isEmpty()) {
      LogUtil.e("%s Group name missing in added group", TAG);
      return false;
    }

    if (dataFileGroup.getGroupName().contains(MddConstants.SPLIT_CHAR)) {
      LogUtil.e("%s Group name = %s contains '|'", TAG, dataFileGroup.getGroupName());
      return false;
    }

    if (dataFileGroup.getOwnerPackage().contains(MddConstants.SPLIT_CHAR)) {
      LogUtil.e("%s Owner package = %s contains '|'", TAG, dataFileGroup.getOwnerPackage());
      return false;
    }

    // Check if any file is missing any of the required fields.
    for (DataFile dataFile : dataFileGroup.getFileList()) {
      if (dataFile.getFileId().isEmpty()
          || dataFile.getFileId().contains(MddConstants.SPLIT_CHAR)
          || !isValidDataFile(dataFile)) {
        LogUtil.e(
            "%s File details missing in added group = %s, file id = %s",
            TAG, dataFileGroup.getGroupName(), dataFile.getFileId());
        return false;
      }
      if (!hasValidTransforms(dataFileGroup, dataFile, flags)) {
        return false;
      }
      if (!hasValidDeltaFiles(dataFileGroup.getGroupName(), dataFile)) {
        return false;
      }
      // Check if sideloaded files are present and if sideloading is enabled
      if (FileGroupUtil.isSideloadedFile(dataFile) && !flags.enableSideloading()) {
        LogUtil.e(
            "%s File detected as sideloaded, but sideloading is not enabled. group = %s, file id ="
                + " %s, file url = %s",
            TAG, dataFileGroup.getGroupName(), dataFile.getFileId(), dataFile.getUrlToDownload());
        return false;
      }
    }

    // Check if a file id is repeated.
    for (int i = 0; i < dataFileGroup.getFileCount(); i++) {
      for (int j = i + 1; j < dataFileGroup.getFileCount(); j++) {
        if (dataFileGroup.getFile(i).getFileId().equals(dataFileGroup.getFile(j).getFileId())) {
          LogUtil.e(
              "%s Repeated file id in added group = %s, file id = %s",
              TAG, dataFileGroup.getGroupName(), dataFileGroup.getFile(i).getFileId());
          return false;
        }
      }
    }

    if (dataFileGroup
            .getDownloadConditions()
            .getDeviceNetworkPolicy()
            .equals(DeviceNetworkPolicy.DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK)
        && dataFileGroup.getDownloadConditions().getDownloadFirstOnWifiPeriodSecs() <= 0) {
      LogUtil.e(
          "%s For DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK policy, "
              + "the download_first_on_wifi_period_secs must be > 0",
          TAG);
      return false;
    }

    if (!Migrations.isMigratedToNewFileKey(context)
        && dataFileGroup.getAllowedReadersEnum().equals(AllowedReaders.ALL_APPS)) {
      LogUtil.e(
          "%s For AllowedReaders ALL_APPS policy, the device should be migrated to new key", TAG);
      return false;
    }

    return true;
  }

  private static boolean hasValidTransforms(
      DataFileGroupInternal dataFileGroup, DataFile dataFile, Flags flags) {
    // Verify for Download transforms
    if (dataFile.hasDownloadTransforms()) {
      if (!isValidTransforms(dataFile.getDownloadTransforms())) {
        return false;
      }
      if (!hasValidZipDownloadTransform(dataFileGroup.getGroupName(), dataFile, flags)) {
        return false;
      }
      if (dataFile.getChecksumType() != ChecksumType.NONE
          && !dataFile.hasDownloadedFileChecksum()) {
        LogUtil.e(
            "Download checksum must be provided. Group = %s, file id = %s",
            dataFileGroup.getGroupName(), dataFile.getFileId());
        return false;
      }
    }
    // Verify for Read transforms
    if (dataFile.hasReadTransforms() && !isValidTransforms(dataFile.getReadTransforms())) {
      return false;
    }
    return true;
  }

  private static boolean hasValidZipDownloadTransform(
      String groupName, DataFile dataFile, Flags flags) {
    if (FileGroupUtil.hasZipDownloadTransform(dataFile)) {
      if (!flags.enableZipFolder()) {
        LogUtil.e(
            "Feature enableZipFolder is not enabled. Group = %s, file id = %s",
            groupName, dataFile.getFileId());
        return false;
      }
      if (dataFile.getDownloadTransforms().getTransformCount() > 1) {
        LogUtil.e(
            "Download zip folder transform cannot not be applied with other transforms. Group ="
                + " %s, file id = %s",
            groupName, dataFile.getFileId());
        return false;
      }
      if (!"*".equals(dataFile.getDownloadTransforms().getTransform(0).getZip().getTarget())) {
        LogUtil.e(
            "Download zip folder transform can only have * as target. Group = %s, file id = %s",
            groupName, dataFile.getFileId());
        return false;
      }
    }
    return true;
  }

  private static boolean isValidTransforms(Transforms transforms) {
    try {
      TransformProtos.toEncodedFragment(transforms);
      return true;
    } catch (IllegalArgumentException illegalArgumentException) {
      LogUtil.e(illegalArgumentException, "Invalid transform specification");
      return false;
    }
  }

  private static boolean hasValidDeltaFiles(String groupName, DataFile dataFile) {
    for (DeltaFile deltaFile : dataFile.getDeltaFileList()) {
      if (!isValidDeltaFile(deltaFile)) {
        LogUtil.e(
            "%s Delta File of Datafile details missing in added group = %s, file id = %s"
                + ", delta file UrlToDownload = %s.",
            TAG, groupName, dataFile.getFileId(), deltaFile.getUrlToDownload());
        return false;
      }
    }
    return true;
  }

  private static boolean isValidDataFile(DataFile dataFile) {
    // When a data file has zip transform, downloaded file checksum is used for identifying the data
    // file; otherwise, checksum is used.
    boolean hasNonEmptyChecksum;
    if (FileGroupUtil.hasZipDownloadTransform(dataFile)) {
      hasNonEmptyChecksum =
          dataFile.hasDownloadedFileChecksum() && !dataFile.getDownloadedFileChecksum().isEmpty();
    } else {
      hasNonEmptyChecksum = dataFile.hasChecksum() && !dataFile.getChecksum().isEmpty();
    }

    boolean validChecksum;
    switch (dataFile.getChecksumType()) {
        // Default stands for SHA1.
      case DEFAULT:
        validChecksum = hasNonEmptyChecksum;
        break;
      case NONE:
        validChecksum = !hasNonEmptyChecksum;
        break;
      default:
        validChecksum = false;
    }

    // File checksum is not needed for zip folder download transforms.
    validChecksum |= FileGroupUtil.hasZipDownloadTransform(dataFile) && !hasNonEmptyChecksum;

    boolean validAndroidSharingConfig =
        dataFile.getAndroidSharingChecksumType() == DataFile.AndroidSharingChecksumType.SHA2_256
            ? !dataFile.getAndroidSharingChecksum().isEmpty()
            : true;

    return !dataFile.getUrlToDownload().isEmpty()
        && !dataFile.getUrlToDownload().contains(MddConstants.SPLIT_CHAR)
        && dataFile.getByteSize() >= 0
        && validChecksum
        && validAndroidSharingConfig
        && !FileGroupUtil.getFileChecksum(dataFile).contains(MddConstants.SPLIT_CHAR);
  }

  private static boolean isValidDeltaFile(DeltaFile deltaFile) {
    return !deltaFile.getUrlToDownload().isEmpty()
        && !deltaFile.getUrlToDownload().contains(MddConstants.SPLIT_CHAR)
        && deltaFile.hasByteSize()
        && deltaFile.getByteSize() >= 0
        && !deltaFile.getChecksum().isEmpty()
        && !deltaFile.getChecksum().contains(MddConstants.SPLIT_CHAR)
        && deltaFile.hasDiffDecoder()
        && !deltaFile.getDiffDecoder().equals(DiffDecoder.UNSPECIFIED)
        && deltaFile.hasBaseFile()
        && !deltaFile.getBaseFile().getChecksum().isEmpty()
        && !deltaFile.getBaseFile().getChecksum().contains(MddConstants.SPLIT_CHAR);
  }
}
