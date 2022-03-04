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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import android.os.Build.VERSION;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;
import com.google.android.apps.common.testing.util.BackdoorTestUtil;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.mobiledatadownload.TransformProto.ZipTransform;
import com.google.mobiledatadownload.internal.MetadataProto.BaseFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile.DiffDecoder;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import com.google.protobuf.MessageLite;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class MddTestUtil {

  public static final String FILE_URI = "android://file";
  private static final String TAG = "MddTestUtil";

  /**
   * Creates a data file group with the given number of files. It only sets the field that are set
   * in a data file group that we get from the server.
   */
  public static DataFileGroup createDataFileGroup(String fileGroupName, int fileCount) {
    DataFileGroup.Builder dataFileGroup = DataFileGroup.newBuilder().setGroupName(fileGroupName);
    for (int i = 0; i < fileCount; ++i) {
      com.google.mobiledatadownload.DownloadConfigProto.DataFile.Builder file =
          com.google.mobiledatadownload.DownloadConfigProto.DataFile.newBuilder();
      file.setFileId(String.format("%s_%s", fileGroupName, i));
      file.setUrlToDownload(String.format("https://%s_%s", fileGroupName, i));
      file.setByteSize(10 + i);
      file.setChecksum("123" + i);
      dataFileGroup.addFile(file.build());
    }
    return dataFileGroup.build();
  }

  /**
   * Creates an internal data file group with the given number of files. It only sets the field that
   * are set in a data file group that we get from the server.
   */
  public static DataFileGroupInternal createDataFileGroupInternal(
      String fileGroupName, int fileCount) {
    DataFileGroupInternal.Builder dataFileGroupInternal =
        DataFileGroupInternal.newBuilder().setGroupName(fileGroupName);
    for (int i = 0; i < fileCount; ++i) {
      dataFileGroupInternal.addFile(createDataFile(fileGroupName, i));
    }
    return dataFileGroupInternal.build();
  }

  /**
   * Creates an internal data file group with the given number of files. It only sets the field that
   * are set in a data file group that we get from the server.
   */
  public static DataFileGroupInternal createDataFileGroupInternalWithDownloadId(
      String fileGroupName, int fileCount) {
    DataFileGroupInternal.Builder dataFileGroupInternal =
        DataFileGroupInternal.newBuilder().setGroupName(fileGroupName);
    for (int i = 0; i < fileCount; ++i) {
      dataFileGroupInternal.addFile(createDataFile(fileGroupName, i));
    }
    return dataFileGroupInternal.build();
  }

  /**
   * Creates an internal data file group with the given number of files, all configured to be
   * shared. It only sets the field that are set in a data file group that we get from the server.
   */
  public static DataFileGroupInternal createSharedDataFileGroupInternal(
      String fileGroupName, int fileCount) {
    DataFileGroupInternal.Builder dataFileGroupInternal =
        DataFileGroupInternal.newBuilder().setGroupName(fileGroupName);
    for (int i = 0; i < fileCount; ++i) {
      dataFileGroupInternal.addFile(createSharedDataFile(fileGroupName, /* fileIndex = */ i));
    }
    return dataFileGroupInternal.build();
  }

  /**
   * Creates a data file group with the given number of files. It creates a downloaded file, so the
   * file uri is also set.
   */
  public static DataFileGroupInternal createDownloadedDataFileGroupInternal(
      String fileGroupName, int fileCount) {
    DataFileGroupInternal.Builder dataFileGroup =
        DataFileGroupInternal.newBuilder().setGroupName(fileGroupName);
    for (int i = 0; i < fileCount; ++i) {
      dataFileGroup.addFile(createDownloadedDataFile(fileGroupName, i));
    }
    return dataFileGroup.build();
  }

  private static DataFile createDownloadedDataFile(String fileId, int fileIndex) {
    DataFile file = createDataFile(fileId, fileIndex);
    return file;
  }

  public static DataFile createDataFile(String fileId, int fileIndex) {
    DataFile.Builder file = DataFile.newBuilder();
    file.setFileId(String.format("%s_%s", fileId, fileIndex));
    file.setUrlToDownload(String.format("https://%s_%s", fileId, fileIndex));
    file.setByteSize(10 + fileIndex);
    file.setChecksum("123" + fileIndex);
    return file.build();
  }

  /**
   * Creates a dataFile configured for sharing, i.e. with ChecksumType set to SHA256 and
   * AndroidSharingType set to ANDROID_BLOB_WHEN_AVAILABLE.
   */
  public static DataFile createSharedDataFile(String fileId, int fileIndex) {
    DataFile.Builder file = DataFile.newBuilder();
    file.setFileId(String.format("%s_%s", fileId, fileIndex));
    file.setUrlToDownload(String.format("https://%s_%s", fileId, fileIndex));
    file.setByteSize(10 + fileIndex);
    file.setChecksum("123" + fileIndex);
    file.setChecksumType(DataFile.ChecksumType.DEFAULT);
    file.setAndroidSharingType(DataFile.AndroidSharingType.ANDROID_BLOB_WHEN_AVAILABLE);
    file.setAndroidSharingChecksumType(DataFile.AndroidSharingChecksumType.SHA2_256);
    file.setAndroidSharingChecksum("sha256_123" + fileIndex);
    return file.build();
  }

  /** Creates a dataFile with relative path. */
  public static DataFile createRelativePathDataFile(
      String fileId, int fileIndex, String relativeFilePath) {
    return DataFile.newBuilder()
        .setFileId(String.format("%s_%s", fileId, fileIndex))
        .setUrlToDownload(String.format("https://%s_%s", fileId, fileIndex))
        .setByteSize(10 + fileIndex)
        .setChecksum("123" + fileIndex)
        .setChecksumType(DataFile.ChecksumType.DEFAULT)
        .setRelativeFilePath(relativeFilePath)
        .build();
  }

  public static DataFile createZipFolderDataFile(String fileId, int fileIndex) {
    DataFile.Builder file = DataFile.newBuilder();
    file.setFileId(String.format("%s_%s", fileId, fileIndex));
    file.setUrlToDownload(String.format("https://%s_%s", fileId, fileIndex));
    file.setByteSize(10 + fileIndex);
    file.setDownloadedFileChecksum("123" + fileIndex);
    file.setDownloadTransforms(
        Transforms.newBuilder()
            .addTransform(
                Transform.newBuilder().setZip(ZipTransform.newBuilder().setTarget("*").build())));
    return file.build();
  }

  public static DataFileGroupInternal createFileGroupInternalWithDeltaFile(String fileGroupName) {
    DataFileGroupInternal.Builder fileGroupBuilder =
        MddTestUtil.createDataFileGroupInternal(fileGroupName, 1).toBuilder();
    DataFileGroupInternal dataFileGroup =
        fileGroupBuilder
            .setFile(0, fileGroupBuilder.getFile(0).toBuilder().addDeltaFile(0, createDeltaFile()))
            .build();
    return dataFileGroup;
  }

  public static DeltaFile createDeltaFile() {
    return DeltaFile.newBuilder()
        .setUrlToDownload("http://abc")
        .setByteSize(10)
        .setChecksum("ABC")
        .setDiffDecoder(DiffDecoder.VC_DIFF)
        .setBaseFile(createDeltaBaseFile("mychecksum"))
        .build();
  }

  public static DeltaFile createDeltaFile(String fileId, int fileIndex) {
    return DeltaFile.newBuilder()
        .setUrlToDownload(String.format("https://%s_%s", fileId, fileIndex))
        .setByteSize(10 + fileIndex)
        .setChecksum("123" + fileIndex)
        .setDiffDecoder(DiffDecoder.VC_DIFF)
        .setBaseFile(createDeltaBaseFile("mychecksum" + fileIndex))
        .build();
  }

  public static BaseFile createDeltaBaseFile(String checksum) {
    return BaseFile.newBuilder().setChecksum(checksum).build();
  }

  public static NewFileKey[] createFileKeysForDataFileGroupInternal(DataFileGroupInternal group) {
    NewFileKey[] newFileKeys = new NewFileKey[group.getFileCount()];
    for (int i = 0; i < group.getFileCount(); ++i) {
      newFileKeys[i] =
          SharedFilesMetadata.createKeyFromDataFile(
              group.getFile(i), group.getAllowedReadersEnum());
    }
    return newFileKeys;
  }

  public static void assertMessageEquals(MessageLite expected, MessageLite actual) {
    assertWithMessage(String.format("EXPECTED: %s\n ACTUAL: %s\n", expected, actual))
        .that(expected.equals(actual))
        .isTrue();
  }

  public static DataFile createDataFileWithDeltaFile(
      String fileId, int fileIndex, int deltaFileCount) {
    DataFile.Builder file =
        DataFile.newBuilder()
            .setFileId(String.format("%s_%s", fileId, fileIndex))
            .setUrlToDownload(String.format("https://%s_%s", fileId, fileIndex))
            .setByteSize(10 + fileIndex)
            .setChecksum("123" + fileIndex);
    for (int i = 0; i < deltaFileCount; i++) {
      file.addDeltaFile(createDeltaFile(fileId + "_delta_" + i, i));
    }
    return file.build();
  }

  /** Executes the shell command {@code cmd}. */
  public static String runShellCmd(String cmd) throws IOException {
    final UiDevice uiDevice = UiDevice.getInstance(getInstrumentation());
    final String result = uiDevice.executeShellCommand(cmd).trim();
    Log.i(TAG, "Output of '" + cmd + "': '" + result + "'");
    return result;
  }

  /** For API-level 19+, it moves the time forward by {@code timeInMillis} milliseconds. */
  public static void timeTravel(Context context, long timeInMillis) {
    if (VERSION.SDK_INT == 18) {
      throw new UnsupportedOperationException(
          "Time travel does not work on API-level 18 - b/31132161. "
              + "You need to disable this test on API-level 18. Example: cl/131498720");
    }

    final long timestampBeforeTravel = System.currentTimeMillis();
    if (!BackdoorTestUtil.advanceTime(context, timeInMillis)) {
      // On some API levels (>23) the call returns false even if the time changed. Have a manual
      // validation that the time changed instead.
      if (VERSION.SDK_INT >= 23) {
        assertThat(System.currentTimeMillis()).isAtLeast(timestampBeforeTravel + timeInMillis);
      } else {
        throw new IllegalStateException("Time Travel was not successful");
      }
    }
  }

  /**
   * @return the time (in seconds) that is n days from the current time
   */
  public static long daysFromNow(int days) {
    long thenMillis = System.currentTimeMillis() + DAYS.toMillis(days);
    return MILLISECONDS.toSeconds(thenMillis);
  }

  /**
   * Writes the SharedFile metadata for all the files stored in the {@code fileGroup}, setting for
   * each of them the status and whether they are currently android-shared based on the array
   * position.
   */
  public static void writeSharedFiles(
      SharedFilesMetadata sharedFilesMetadata,
      DataFileGroupInternal fileGroup,
      List<FileStatus> statuses,
      List<Boolean> androidShared)
      throws Exception {
    int size = fileGroup.getFileCount();
    NewFileKey[] keys = createFileKeysForDataFileGroupInternal(fileGroup);
    assertWithMessage("Created file keys must match the given DataFileGroup's file count")
        .that(keys.length)
        .isEqualTo(size);
    assertWithMessage("Given FileStatus list must match the given DataFileGroup's file count")
        .that(statuses.size())
        .isEqualTo(size);
    assertWithMessage("Given androidShared list must match the given DataFileGroup's file count")
        .that(androidShared.size())
        .isEqualTo(size);
    for (int i = 0; i < fileGroup.getFileCount(); i++) {
      DataFile file = fileGroup.getFile(i);
      SharedFile.Builder sharedFileBuilder =
          SharedFile.newBuilder().setFileName(file.getFileId()).setFileStatus(statuses.get(i));
      if (androidShared.get(i)) {
        sharedFileBuilder.setAndroidShared(true).setAndroidSharingChecksum("sha256_123" + i);
      }
      sharedFilesMetadata.write(keys[i], sharedFileBuilder.build()).get();
    }
  }

  /**
   * Convenience method for {@link MddTestUtil#writeSharedFiles(SharedFilesMetadata,
   * DataFileGroupInternal, List<FileStatus>, List<Boolean>)} when android shared status is
   * unnecessary.
   */
  public static void writeSharedFiles(
      SharedFilesMetadata sharedFilesMetadata,
      DataFileGroupInternal fileGroup,
      List<FileStatus> statuses)
      throws Exception {
    int size = fileGroup.getFileCount();
    List<Boolean> androidShared = Collections.nCopies(size, false);
    writeSharedFiles(sharedFilesMetadata, fileGroup, statuses, androidShared);
  }
}
