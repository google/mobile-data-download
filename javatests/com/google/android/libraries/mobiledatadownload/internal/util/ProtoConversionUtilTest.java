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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadByteArrayOpener;
import com.google.android.libraries.mobiledatadownload.internal.MddTestUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import com.google.mobiledatadownload.DownloadConfigProto.BaseFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup.AllowedReaders;
import com.google.mobiledatadownload.DownloadConfigProto.DeltaFile;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceStoragePolicy;
import com.google.mobiledatadownload.DownloadConfigProto.ExtraHttpHeader;
import com.google.mobiledatadownload.TransformProto.CompressTransform;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.mobiledatadownload.TransformProto.ZipTransform;
import com.google.mobiledatadownload.internal.MetadataProto;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupBookkeeping;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.contrib.android.ProtoParsers;
import com.google.testing.util.TestUtil;
import java.io.File;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/** Unit tests for {@link ProtoConversionUtil}. */
@RunWith(RobolectricTestRunner.class)
public final class ProtoConversionUtilTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  private SynchronousFileStorage fileStorage;
  private Context context;

  // The raw test data folder in google3.
  private static final String TEST_DATA_DIR =
      TestUtil.getRunfilesDir()
          + "/google3/third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/internal/util/testdata/";
  private static final File RAW_GROUP_WITH_EXTENSION =
      new File(TEST_DATA_DIR, "raw_group_with_extension");

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    fileStorage =
        new SynchronousFileStorage(
            ImmutableList.of(AndroidFileBackend.builder(context).build(), new JavaFileBackend()));
  }

  @Test
  public void convert_fromDataFileGroup_toDataFileGroupInternal_noThrow() throws Exception {
    DataFileGroup group =
        DataFileGroup.newBuilder()
            .setGroupName("test-group")
            .setOwnerPackage("com.google.android.libraries.mobiledatadownload")
            .setFileGroupVersionNumber(12)
            .setAllowedReadersEnum(AllowedReaders.ALL_GOOGLE_APPS)
            .setDownloadConditions(
                DownloadConditions.newBuilder()
                    .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK)
                    .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_LOWER_THRESHOLD)
                    .build())
            .setExpirationDate(1234567890)
            .setStaleLifetimeSecs(123456)
            .setTrafficTag(3)
            .setPreserveFilenamesAndIsolateFiles(true)
            .addAllFile(
                ImmutableList.of(
                    DataFile.newBuilder()
                        .setFileId("one")
                        .setByteSize(200)
                        .setUrlToDownload("https://www.google.com/")
                        .setChecksum("checksum1")
                        .build(),
                    DataFile.newBuilder()
                        .setFileId("two")
                        .setByteSize(500)
                        .setUrlToDownload("https://www.instagram.com/")
                        .setChecksum("checksum2")
                        .build()))
            .addAllGroupExtraHttpHeaders(
                ImmutableList.of(
                    ExtraHttpHeader.newBuilder().setKey("k1").setValue("v1").build(),
                    ExtraHttpHeader.newBuilder().setKey("k2").setValue("v2").build()))
            .build();
    DataFileGroupInternal unused = ProtoConversionUtil.convert(group);
  }

  @Test
  public void convert_fromDataFileGroup_toDataFileGroupInternal_parseFromTextProto()
      throws Exception {
    DataFileGroup dataFileGroup = getDataFileGroupFromTextProto(R.raw.group_data_pb);
    DataFileGroupInternal dataFileGroupInternal =
        getDataFileGroupInternalFromTextProto(R.raw.group_internal_data_pb);
    assertThat(ProtoConversionUtil.convert(dataFileGroup)).isEqualTo(dataFileGroupInternal);
  }

  @Test
  public void convert_fromDataFileGroup_toDataFileGroupInternal_parseFromTextProto_optionalUnset()
      throws Exception {
    DataFileGroup dataFileGroup = getDataFileGroupFromTextProto(R.raw.group_optional_unset_data_pb);
    DataFileGroupInternal dataFileGroupInternal =
        getDataFileGroupInternalFromTextProto(R.raw.group_internal_optional_unset_data_pb);
    assertThat(ProtoConversionUtil.convert(dataFileGroup)).isEqualTo(dataFileGroupInternal);
  }

  /**
   * The main purpose of this test is to make sure that after migration, we are still able to
   * interpret and parse the group metadata correctly. The raw metadata was generated by the proto
   * {@link DataFileGroup} with extensions before the migration. Since we reuse the same tag number,
   * the extension should now be parsed into {@link DataFileGroupBookkeeping}.
   */
  @Test
  public void convert_parseRawProtoWithExtensions() throws Exception {
    DataFileGroupInternal expected =
        ProtoConversionUtil.convert(
                MddTestUtil.createDataFileGroup(/*fileGroupName=*/ "test-group", 2))
            .toBuilder()
            .setBookkeeping(
                DataFileGroupBookkeeping.newBuilder()
                    .setGroupNewFilesReceivedTimestamp(1000)
                    .build())
            .build();

    // Read the raw group with extension from file.
    Uri uri = Uri.fromFile(RAW_GROUP_WITH_EXTENSION);
    byte[] bytes = fileStorage.open(uri, ReadByteArrayOpener.create());

    // Make sure that the proto with extension is correcly parsed.
    DataFileGroupInternal parsedFromRawProto =
        DataFileGroupInternal.parseFrom(bytes, ExtensionRegistryLite.getEmptyRegistry());
    assertThat(parsedFromRawProto).isEqualTo(expected);
  }

  @Test
  public void convert_onDownloadConditions() throws Exception {
    DownloadConditions downloadConditions =
        DownloadConditions.newBuilder()
            .setDeviceNetworkPolicy(DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK)
            .setDeviceStoragePolicy(DeviceStoragePolicy.BLOCK_DOWNLOAD_LOWER_THRESHOLD)
            .build();
    MetadataProto.DownloadConditions convertedDownloadConditions =
        ProtoConversionUtil.convert(downloadConditions);
    assertThat(convertedDownloadConditions.hasDeviceStoragePolicy()).isTrue();
    assertThat(convertedDownloadConditions.hasDeviceNetworkPolicy()).isTrue();
    assertThat(convertedDownloadConditions.hasDownloadFirstOnWifiPeriodSecs()).isFalse();
    assertThat(convertedDownloadConditions.getDeviceNetworkPolicy())
        .isEqualTo(MetadataProto.DownloadConditions.DeviceNetworkPolicy.DOWNLOAD_ON_ANY_NETWORK);
    assertThat(convertedDownloadConditions.getDeviceStoragePolicy())
        .isEqualTo(
            MetadataProto.DownloadConditions.DeviceStoragePolicy.BLOCK_DOWNLOAD_LOWER_THRESHOLD);
  }

  @Test
  public void convertDataFile_convertsFields() {
    Transforms downloadTransforms =
        Transforms.newBuilder()
            .addTransform(
                Transform.newBuilder().setCompress(CompressTransform.getDefaultInstance()).build())
            .build();
    Transforms readTransforms =
        Transforms.newBuilder()
            .addTransform(Transform.newBuilder().setZip(ZipTransform.getDefaultInstance()).build())
            .build();

    DataFile dataFileExternal =
        DataFile.newBuilder()
            .setFileId("test-file")
            .setUrlToDownload("https://url.to.download")
            .setByteSize(10)
            .setChecksumType(DataFile.ChecksumType.NONE)
            .setChecksum("testchecksum")
            .setDownloadTransforms(downloadTransforms)
            .setDownloadedFileChecksum("testdownloadedchecksum")
            .setDownloadedFileByteSize(100)
            .setReadTransforms(readTransforms)
            .setAndroidSharingType(DataFile.AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE)
            .setAndroidSharingChecksumType(DataFile.AndroidSharingChecksumType.SHA2_256)
            .setAndroidSharingChecksum("testandroidsharingchecksum")
            .setRelativeFilePath("relative/file/path")
            .addDeltaFile(DeltaFile.newBuilder().setUrlToDownload("url1").build())
            .addDeltaFile(DeltaFile.newBuilder().setUrlToDownload("url2").build())
            .build();

    MetadataProto.DataFile dataFileInternal = ProtoConversionUtil.convertDataFile(dataFileExternal);

    assertThat(dataFileInternal.getFileId()).isEqualTo("test-file");
    assertThat(dataFileInternal.getUrlToDownload()).isEqualTo("https://url.to.download");
    assertThat(dataFileInternal.getByteSize()).isEqualTo(10);
    assertThat(dataFileInternal.getChecksumType())
        .isEqualTo(MetadataProto.DataFile.ChecksumType.NONE);
    assertThat(dataFileInternal.getChecksum()).isEqualTo("testchecksum");
    assertThat(dataFileInternal.getDownloadTransforms()).isEqualTo(downloadTransforms);
    assertThat(dataFileInternal.getDownloadedFileChecksum()).isEqualTo("testdownloadedchecksum");
    assertThat(dataFileInternal.getDownloadedFileByteSize()).isEqualTo(100);
    assertThat(dataFileInternal.getReadTransforms()).isEqualTo(readTransforms);
    assertThat(dataFileInternal.getAndroidSharingType())
        .isEqualTo(MetadataProto.DataFile.AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE);
    assertThat(dataFileInternal.getAndroidSharingChecksumType())
        .isEqualTo(MetadataProto.DataFile.AndroidSharingChecksumType.SHA2_256);
    assertThat(dataFileInternal.getAndroidSharingChecksum())
        .isEqualTo("testandroidsharingchecksum");
    assertThat(dataFileInternal.getRelativeFilePath()).isEqualTo("relative/file/path");

    assertThat(dataFileInternal.getDeltaFileCount()).isEqualTo(2);
    assertThat(dataFileInternal.getDeltaFileList())
        .comparingElementsUsing(
            Correspondence.transforming(MetadataProto.DeltaFile::getUrlToDownload, "using url"))
        .containsExactly("url1", "url2");
  }

  @Test
  public void convertDeltaFile_convertsFields() {
    DeltaFile deltaFileExternal =
        DeltaFile.newBuilder()
            .setUrlToDownload("https://url.to.download")
            .setByteSize(10)
            .setChecksum("testchecksum")
            .setDiffDecoder(DeltaFile.DiffDecoder.VC_DIFF)
            .setBaseFile(BaseFile.newBuilder().setChecksum("testbasechecksum").build())
            .build();

    MetadataProto.DeltaFile deltaFileInternal =
        ProtoConversionUtil.convertDeltaFile(deltaFileExternal);

    assertThat(deltaFileInternal.getUrlToDownload()).isEqualTo("https://url.to.download");
    assertThat(deltaFileInternal.getByteSize()).isEqualTo(10);
    assertThat(deltaFileInternal.getChecksum()).isEqualTo("testchecksum");
    assertThat(deltaFileInternal.getDiffDecoder())
        .isEqualTo(MetadataProto.DeltaFile.DiffDecoder.VC_DIFF);
    assertThat(deltaFileInternal.getBaseFile().getChecksum()).isEqualTo("testbasechecksum");
  }

  private static DataFileGroup getDataFileGroupFromTextProto(int rawResId) {
    return ProtoParsers.parseFromRawRes(
        ApplicationProvider.getApplicationContext(), DataFileGroup.parser(), rawResId);
  }

  private static DataFileGroupInternal getDataFileGroupInternalFromTextProto(int rawResId) {
    return ProtoParsers.parseFromRawRes(
        ApplicationProvider.getApplicationContext(), DataFileGroupInternal.parser(), rawResId);
  }
}
