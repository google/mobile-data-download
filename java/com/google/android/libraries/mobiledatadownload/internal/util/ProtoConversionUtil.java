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
package com.google.android.libraries.mobiledatadownload.internal.util;

import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DeltaFile;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;

/** The util class that does conversion between protos. */
public final class ProtoConversionUtil {
  private ProtoConversionUtil() {}

  /**
   * Converts external configuration proto {@link DataFileGroup} into internal storage proto {@link
   * DataFileGroupInternal}.
   */
  // TODO(b/176103639): Use automated proto converter instead
  public static DataFileGroupInternal convert(DataFileGroup group)
      throws InvalidProtocolBufferException {
    // Cannot use generated registry here, because it may cause NPE to clients.
    // For more detail, see b/140135059.
    return DataFileGroupInternal.parseFrom(
        group.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
  }

  /**
   * Converts external proto {@link DownloadConditions} into internal proto {@link
   * MetadataProto.DownloadConditions}.
   */
  // TODO(b/176103639): Use automated proto converter instead
  public static MetadataProto.DownloadConditions convert(DownloadConditions downloadConditions)
      throws InvalidProtocolBufferException {
    // Cannot use generated registry here, because it may cause NPE to clients.
    // For more detail, see b/140135059.
    return MetadataProto.DownloadConditions.parseFrom(
        downloadConditions.toByteArray(), ExtensionRegistryLite.getEmptyRegistry());
  }

  /**
   * Converts external configuration proto {@link DataFile} to internal storage proto {@link
   * MetadataProto.DataFile}.
   */
  // TODO(b/176103639): Use automated proto converter instead
  // LINT.IfChange(data_file_convert)
  public static MetadataProto.DataFile convertDataFile(DataFile dataFile) {
    MetadataProto.DataFile.Builder dataFileBuilder =
        MetadataProto.DataFile.newBuilder()
            .setFileId(dataFile.getFileId())
            .setUrlToDownload(dataFile.getUrlToDownload())
            .setByteSize(dataFile.getByteSize())
            .setChecksumType(
                MetadataProto.DataFile.ChecksumType.forNumber(
                    dataFile.getChecksumType().getNumber()))
            .setChecksum(dataFile.getChecksum())
            .setDownloadedFileChecksum(dataFile.getDownloadedFileChecksum())
            .setDownloadedFileByteSize(dataFile.getDownloadedFileByteSize())
            .setAndroidSharingType(
                MetadataProto.DataFile.AndroidSharingType.forNumber(
                    dataFile.getAndroidSharingType().getNumber()))
            .setAndroidSharingChecksumType(
                MetadataProto.DataFile.AndroidSharingChecksumType.forNumber(
                    dataFile.getAndroidSharingChecksumType().getNumber()))
            .setAndroidSharingChecksum(dataFile.getAndroidSharingChecksum())
            .setRelativeFilePath(dataFile.getRelativeFilePath());

    if (dataFile.hasCustomMetadata()) {
      dataFileBuilder.setCustomMetadata(dataFile.getCustomMetadata());
    }
    if (dataFile.hasDownloadTransforms()) {
      dataFileBuilder.setDownloadTransforms(dataFile.getDownloadTransforms());
    }

    if (dataFile.hasReadTransforms()) {
      dataFileBuilder.setReadTransforms(dataFile.getReadTransforms());
    }

    for (DeltaFile deltaFile : dataFile.getDeltaFileList()) {
      dataFileBuilder.addDeltaFile(convertDeltaFile(deltaFile));
    }

    return dataFileBuilder.build();
  }
  // LINT.ThenChange(
  //
  // <internal>,
  //
  // <internal>)

  /**
   * Converts external configuration proto {@link DeltaFile} to internal storage proto {@link
   * DeltaFile}.
   */
  // TODO(b/176103639): Use automated proto converter instead
  // LINT.IfChange(delta_file_convert)
  public static MetadataProto.DeltaFile convertDeltaFile(DeltaFile deltaFile) {
    return MetadataProto.DeltaFile.newBuilder()
        .setUrlToDownload(deltaFile.getUrlToDownload())
        .setByteSize(deltaFile.getByteSize())
        .setChecksum(deltaFile.getChecksum())
        .setDiffDecoder(
            MetadataProto.DeltaFile.DiffDecoder.forNumber(deltaFile.getDiffDecoder().getNumber()))
        .setBaseFile(
            MetadataProto.BaseFile.newBuilder()
                .setChecksum(deltaFile.getBaseFile().getChecksum())
                .build())
        .build();
  }
  // LINT.ThenChange(
  //
  // <internal>,
  //
  // <internal>)
}
