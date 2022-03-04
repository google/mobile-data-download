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
import android.util.Log;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.mobiledatadownload.TransformProto.ZipTransform;

/** Test FileGroup Populator with zip file which contains 3 files and one sub-folder. */
public class ZipFolderFileGroupPopulator implements FileGroupPopulator {

  private static final String TAG = "MDD ZipFolderFileGroupPopulator";

  static final String FILE_GROUP_NAME = "test-zip-group";
  static final String FILE_ID = "test-zip-file-id";
  static final int FILE_SIZE = 373;
  private static final String FILE_CHECKSUM = "7024b6bcddf2b2897656e9353f7fc715df5ea986";
  private static final String FILE_URL =
      "https://www.gstatic.com/icing/idd/apitest/zip_test_folder.zip";

  private final Context context;

  public ZipFolderFileGroupPopulator(Context context) {
    this.context = context;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
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

  public static DataFileGroup createDataFileGroup(
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
      DataFile file =
          DataFile.newBuilder()
              .setFileId(fileId[i])
              .setByteSize(byteSize[i])
              .setDownloadedFileChecksum(checksum[i])
              .setUrlToDownload(url[i])
              .setDownloadTransforms(
                  Transforms.newBuilder()
                      .addTransform(
                          Transform.newBuilder()
                              .setZip(ZipTransform.newBuilder().setTarget("*").build())))
              .build();
      dataFileGroupBuilder.addFile(file);
    }

    return dataFileGroupBuilder.build();
  }
}
