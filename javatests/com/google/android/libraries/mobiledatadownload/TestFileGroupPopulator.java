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
import android.text.TextUtils;
import android.util.Log;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;

/** Test FileGroup Populator. */
public class TestFileGroupPopulator implements FileGroupPopulator {

  private static final String TAG = "MDD TestFileGroupPopulator";

  static final String FILE_GROUP_NAME = "test-group";
  static final String FILE_ID = "test-file-id";
  static final int FILE_SIZE = 554;
  static final String FILE_CHECKSUM = "a1cba9d87b1440f41ce9e7da38c43e1f6bd7d5df";
  static final String FILE_URL = "https://www.gstatic.com/suggest-dev/odws1_empty.jar";

  private final Context context;

  public TestFileGroupPopulator(Context context) {
    this.context = context;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
    Log.d(TAG, "Adding file groups " + FILE_GROUP_NAME);

    DataFileGroup dataFileGroup =
        createDataFileGroup(
            FILE_GROUP_NAME,
            context.getPackageName(),
            new String[] {FILE_ID},
            new int[] {FILE_SIZE},
            new String[] {FILE_CHECKSUM},
            new String[] {FILE_URL},
            DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);

    ListenableFuture<Boolean> addFileGroupFuture =
        mobileDataDownload.addFileGroup(
            AddFileGroupRequest.newBuilder().setDataFileGroup(dataFileGroup).build());
    return Futures.transform(
        addFileGroupFuture,
        result -> {
          if (result.booleanValue()) {
            Log.d(TAG, "Added file groups " + dataFileGroup.getGroupName());
          } else {
            Log.d(TAG, "Failed to add file group " + dataFileGroup.getGroupName());
          }
          return null;
        },
        MoreExecutors.directExecutor());
  }

  // A helper function to create a DataFilegroup.
  static DataFileGroup createDataFileGroup(
      String groupName,
      String ownerPackage,
      String[] fileId,
      int[] byteSize,
      String[] checksum,
      String[] url,
      DeviceNetworkPolicy deviceNetworkPolicy) {
    if (fileId.length != byteSize.length
        || fileId.length != checksum.length
        || fileId.length != url.length) {
      throw new IllegalArgumentException();
    }

    DataFileGroup.Builder dataFileGroupBuilder =
        DataFileGroup.newBuilder()
            .setGroupName(groupName)
            .setOwnerPackage(ownerPackage)
            .setDownloadConditions(
                DownloadConditions.newBuilder().setDeviceNetworkPolicy(deviceNetworkPolicy));

    for (int i = 0; i < fileId.length; ++i) {
      DataFile.Builder fileBuilder =
          DataFile.newBuilder()
              .setFileId(fileId[i])
              .setByteSize(byteSize[i])
              .setChecksum(checksum[i])
              .setUrlToDownload(url[i]);
      if (checksum[i].isEmpty()) {
        fileBuilder.setChecksumType(ChecksumType.NONE);
      }
      dataFileGroupBuilder.addFile(fileBuilder.build());
    }

    return dataFileGroupBuilder.build();
  }

  // Allows to configure android-shared and non-android-shared files.
  static DataFileGroup createDataFileGroup(
      String groupName,
      String ownerPackage,
      String[] fileId,
      int[] byteSize,
      String[] checksum,
      String[] androidSharingChecksum,
      String[] url,
      DeviceNetworkPolicy deviceNetworkPolicy) {
    if (fileId.length != byteSize.length
        || fileId.length != checksum.length
        || fileId.length != url.length) {
      throw new IllegalArgumentException();
    }

    DataFileGroup.Builder dataFileGroupBuilder =
        DataFileGroup.newBuilder()
            .setGroupName(groupName)
            .setOwnerPackage(ownerPackage)
            .setDownloadConditions(
                DownloadConditions.newBuilder().setDeviceNetworkPolicy(deviceNetworkPolicy));

    for (int i = 0; i < fileId.length; ++i) {
      DataFile.Builder fileBuilder =
          DataFile.newBuilder()
              .setFileId(fileId[i])
              .setByteSize(byteSize[i])
              .setChecksum(checksum[i])
              .setUrlToDownload(url[i]);
      if (checksum[i].isEmpty()) {
        fileBuilder.setChecksumType(ChecksumType.NONE);
      }
      if (!TextUtils.isEmpty(androidSharingChecksum[i])) {
        fileBuilder
            .setAndroidSharingType(DataFile.AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE)
            .setAndroidSharingChecksumType(DataFile.AndroidSharingChecksumType.SHA2_256)
            .setAndroidSharingChecksum(androidSharingChecksum[i]);
      }
      dataFileGroupBuilder.addFile(fileBuilder.build());
    }
    return dataFileGroupBuilder.build();
  }
}
