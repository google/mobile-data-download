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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.mobiledatadownload.TransformProto.CompressTransform;
import com.google.mobiledatadownload.TransformProto.EncryptTransform;
import com.google.mobiledatadownload.TransformProto.IntegrityTransform;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.mobiledatadownload.TransformProto.ZipTransform;
import com.google.mobiledatadownload.internal.MetadataProto.BaseFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.AndroidSharingChecksumType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.AndroidSharingType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile.ChecksumType;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile.DiffDecoder;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions;
import com.google.mobiledatadownload.internal.MetadataProto.DownloadConditions.DeviceNetworkPolicy;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link
 * com.google.android.libraries.mobiledatadownload.internal.DataFileGroupValidator}.
 */
@RunWith(RobolectricTestRunner.class)
public class DataFileGroupValidatorTest {

  private static final String TEST_GROUP = "test-group";
  private Context context;
  private final TestFlags flags = new TestFlags();

  @Before
  public void setUp() {

    context = ApplicationProvider.getApplicationContext();
  }

  @Test
  public void testAddGroupForDownload_compressedFile() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);

    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadedFileChecksum("downloadchecksum")
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setCompress(CompressTransform.getDefaultInstance()))))
            .build();

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();
  }

  @Test
  public void testAddGroupForDownload_compressedFile_noDownloadChecksum() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);

    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    // Set valid download transforms so it won't fail transforms validation
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setCompress(CompressTransform.getDefaultInstance()))))
            .build();

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();
  }

  @Test
  public void testAddGroupForDownload_encryptFileTransform() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setEncrypt(EncryptTransform.getDefaultInstance())))
                    .setDownloadedFileChecksum("downloadchecksum"))
            .build();

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();
  }

  @Test
  public void testAddGroupForDownload_integrityFileTransform() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setIntegrity(IntegrityTransform.getDefaultInstance())))
                    .setDownloadedFileChecksum("downloadchecksum"))
            .build();

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();
  }

  @Test
  public void testAddGroupForDownload_zip() {
    flags.enableZipFolder = Optional.of(true);

    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setZip(ZipTransform.newBuilder().setTarget("*").build())))
                    .setDownloadedFileChecksum("DOWNLOADEDFILECHECKSUM"))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();
  }

  @Test
  public void testAddGroupForDownload_zip_featureOff() {
    flags.enableZipFolder = Optional.of(false);

    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setZip(ZipTransform.newBuilder().setTarget("*").build())))
                    .setDownloadedFileChecksum("DOWNLOADEDFILECHECKSUM"))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();
  }

  @Test
  public void testAddGroupForDownload_zip_noDownloadFileChecksum() {
    flags.enableZipFolder = Optional.of(true);

    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setZip(ZipTransform.newBuilder().setTarget("*").build()))))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();
  }

  @Test
  public void testAddGroupForDownload_zip_targetOneFile() {
    flags.enableZipFolder = Optional.of(true);

    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setZip(
                                        ZipTransform.newBuilder().setTarget("abc.txt").build())))
                    .setDownloadedFileChecksum("DOWNLOADEDFILECHECKSUM"))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();
  }

  @Test
  public void testAddGroupForDownload_zip_moreThanOneTransforms() {
    flags.enableZipFolder = Optional.of(true);

    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    // Set valid download transforms so it won't fail transforms validation
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setZip(ZipTransform.newBuilder().setTarget("*").build()))
                            .addTransform(
                                Transform.newBuilder()
                                    .setCompress(CompressTransform.getDefaultInstance()))))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();
  }

  @Test
  public void testAddGroupForDownload_readTransform() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setReadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setIntegrity(IntegrityTransform.getDefaultInstance()))))
            .build();

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();
  }

  @Test
  public void testAddGroupForDownload_readTransform_invalid() throws Exception {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    Migrations.setMigratedToNewFileKey(context, true);
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(
                0,
                fileGroupBuilder.getFile(0).toBuilder()
                    .setReadTransforms(
                        Transforms.newBuilder().addTransform(Transform.getDefaultInstance())))
            .build();

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();
  }

  @Test
  public void testAddGroupForDownload_isValidGroup() throws Exception {
    // Group with empty name.
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder().setGroupName("").build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with SPLIT_CHAR in the name.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setGroupName("group|name")
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with empty file id.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().clearFileId())
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with SPLIT_CHAR in the file id.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().setFileId("file|id"))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with empty url.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().clearUrlToDownload())
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with file size 0.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(1, dataFileGroup.getFile(1).toBuilder().clearByteSize())
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();

    // Group with empty checksum and ChecksumType = DEFAULT is invalid.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().setChecksum(""))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with empty checksum and ChecksumType = NONE is valid.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(
                0,
                dataFileGroup.getFile(0).toBuilder()
                    .setChecksum("")
                    .setChecksumType(ChecksumType.NONE))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();

    // Group with download transforms but without downloaded file checksum, and ChecksumType =
    // DEFAULT is invalid.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(
                0,
                dataFileGroup.getFile(0).toBuilder()
                    .setChecksumType(ChecksumType.DEFAULT)
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setCompress(CompressTransform.getDefaultInstance()))))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with download transforms but without downloaded file checksum, and ChecksumType = NONE
    // is valid.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(
                0,
                dataFileGroup.getFile(0).toBuilder()
                    .setChecksum("")
                    .setChecksumType(ChecksumType.NONE)
                    .setDownloadTransforms(
                        Transforms.newBuilder()
                            .addTransform(
                                Transform.newBuilder()
                                    .setCompress(CompressTransform.getDefaultInstance()))))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();

    // Group with SPLIT_CHAR in the checksum.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(
                0,
                dataFileGroup.getFile(0).toBuilder()
                    .setChecksumType(ChecksumType.DEFAULT)
                    .setChecksum("check|sum"))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with duplicate file ids.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(
                0,
                dataFileGroup.getFile(0).toBuilder()
                    .setFileId(dataFileGroup.getFile(1).getFileId()))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // For DeviceNetworkPolicy.DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK
    // download_first_on_wifi_period_secs must be > 0.
    DownloadConditions downloadConditions =
        DownloadConditions.newBuilder()
            .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_FIRST_ON_WIFI_THEN_ON_ANY_NETWORK)
            .setDownloadFirstOnWifiPeriodSecs(0)
            .build();
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setDownloadConditions(downloadConditions)
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isFalse();

    // Group with shared and not-shared files.
    dataFileGroup =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2).toBuilder()
            .setFile(
                0,
                dataFileGroup.getFile(0).toBuilder()
                    .setAndroidSharingType(AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE)
                    .setAndroidSharingChecksumType(AndroidSharingChecksumType.SHA2_256)
                    .setAndroidSharingChecksum("sha256_file0"))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();
  }

  @Test
  public void testInvalidAndroidSharedFile() {
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createSharedDataFileGroupInternal(TEST_GROUP, 1);
    DataFileGroupInternal invalidGroup =
        dataFileGroup.toBuilder()
            .setFile(0, dataFileGroup.getFile(0).toBuilder().setAndroidSharingChecksum(""))
            .build();
    assertThat(DataFileGroupValidator.isValidGroup(invalidGroup, context, flags)).isFalse();
  }

  @Test
  public void testValidDeltaFile() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    DataFileGroupInternal dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP);
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup, context, flags)).isTrue();
  }

  @Test
  public void testInvalidDeltaFile_noDownloadUrl() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    // create with delta file with NO download url
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile = MddTestUtil.createDeltaFile().toBuilder().clearUrlToDownload().build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testInvalidDeltaFile_noDiffDecoder() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    // create with delta file with NO diff decoder
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile = MddTestUtil.createDeltaFile().toBuilder().clearDiffDecoder().build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testInvalidDeltaFile_unspecifiedDiffDecoder() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    // create with delta file with UNSPECIFIED diff decoder
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile =
        MddTestUtil.createDeltaFile().toBuilder().setDiffDecoder(DiffDecoder.UNSPECIFIED).build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testInvalidDeltaFile_noChecksum() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    // create with delta file with NO checksum
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile = MddTestUtil.createDeltaFile().toBuilder().clearChecksum().build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testInvalidDeltaFile_noByteSize() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    // create with delta file with NO byte size
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile = MddTestUtil.createDeltaFile().toBuilder().clearByteSize().build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);

    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testInvalidDeltaFile_noBaseFileChecksum() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile =
        MddTestUtil.createDeltaFile().toBuilder()
            .setBaseFile(BaseFile.getDefaultInstance())
            .build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testInvalidDeltaFile_baseFile_invalidChecksum() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile =
        MddTestUtil.createDeltaFile().toBuilder()
            .setBaseFile(BaseFile.newBuilder().setChecksum("abc" + "|" + "def"))
            .build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testInvalidDeltaFile_noBaseFile() {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);
    DataFileGroupInternal.Builder dataFileGroup =
        MddTestUtil.createFileGroupInternalWithDeltaFile(TEST_GROUP).toBuilder();
    DeltaFile deltaFile = MddTestUtil.createDeltaFile().toBuilder().clearBaseFile().build();
    DataFile dataFile =
        dataFileGroup.getFile(0).toBuilder().clearDeltaFile().addDeltaFile(deltaFile).build();
    dataFileGroup = dataFileGroup.setFile(0, dataFile);
    assertThat(DataFileGroupValidator.isValidGroup(dataFileGroup.build(), context, flags))
        .isFalse();
  }

  @Test
  public void testSideloadedFile_validWhenSideloadingEnabled() {
    // Create sideloaded group
    DataFileGroupInternal sideloadedGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(TEST_GROUP)
            .addFile(
                DataFile.newBuilder()
                    .setFileId("sideloaded_file")
                    .setUrlToDownload("file:/test")
                    .setChecksumType(DataFile.ChecksumType.NONE)
                    .build())
            .build();

    {
      // Force sideloading off
      flags.enableSideloading = Optional.of(false);

      assertThat(DataFileGroupValidator.isValidGroup(sideloadedGroup, context, flags)).isFalse();
    }

    {
      // Force sideloading on
      flags.enableSideloading = Optional.of(true);

      assertThat(DataFileGroupValidator.isValidGroup(sideloadedGroup, context, flags)).isTrue();
    }
  }
}
