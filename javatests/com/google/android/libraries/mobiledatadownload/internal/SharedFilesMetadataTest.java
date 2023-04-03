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
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.FileStatus;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.mobiledatadownload.internal.MetadataProto.SharedFile;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidUri;
import com.google.android.libraries.mobiledatadownload.internal.Migrations.FileKeyVersion;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedFilesMetadataUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedFilesMetadataUtil.FileKeyDeserializationException;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.TransformProto.CompressTransform;
import com.google.mobiledatadownload.TransformProto.Transform;
import com.google.mobiledatadownload.TransformProto.Transforms;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

@RunWith(ParameterizedRobolectricTestRunner.class)
public class SharedFilesMetadataTest {

  private enum MetadataStoreImpl {
    SP_IMPL,
  }

  @Parameters(name = "metadataStoreImpl = {0} instanceId = {1}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {MetadataStoreImpl.SP_IMPL, Optional.absent()},
          {MetadataStoreImpl.SP_IMPL, Optional.of("id")},
        });
  }

  @Parameter(value = 0)
  public MetadataStoreImpl metadataStoreImpl;

  @Parameter(value = 1)
  public Optional<String> instanceId;

  private SynchronousFileStorage storage;
  private Context context;
  private SharedFilesMetadata sharedFilesMetadata;
  private Uri diagnosticUri;
  private Uri destinationUri;

  private final TestFlags flags = new TestFlags();

  @Mock SilentFeedback mockSilentFeedback;
  @Mock EventLogger mockLogger;

  private static final Transforms COMPRESS_TRANSFORM =
      Transforms.newBuilder()
          .addTransform(Transform.newBuilder().setCompress(CompressTransform.getDefaultInstance()))
          .build();
  private static final Executor CONTROL_EXECUTOR =
      MoreExecutors.newSequentialExecutor(Executors.newCachedThreadPool());

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws InterruptedException, ExecutionException {

    context = ApplicationProvider.getApplicationContext();

    storage =
        new SynchronousFileStorage(Arrays.asList(AndroidFileBackend.builder(context).build()));

    destinationUri =
        AndroidUri.builder(context)
            .setPackage(context.getPackageName())
            .setRelativePath("dest.pb")
            .build();
    diagnosticUri =
        AndroidUri.builder(context)
            .setPackage(context.getPackageName())
            .setRelativePath("diag.pb")
            .build();

    SharedPreferencesSharedFilesMetadata sharedPreferencesMetadata =
        new SharedPreferencesSharedFilesMetadata(context, mockSilentFeedback, instanceId, flags);

    switch (metadataStoreImpl) {
      case SP_IMPL:
        sharedFilesMetadata = sharedPreferencesMetadata;
        break;
    }

    Migrations.clear(context);
    Migrations.setMigratedToNewFileKey(context, true);
  }

  @After
  public void tearDown() throws InterruptedException, ExecutionException, IOException {
    if (storage.exists(diagnosticUri)) {
      storage.deleteFile(diagnosticUri);
    }
    if (storage.exists(destinationUri)) {
      storage.deleteFile(destinationUri);
    }
    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      sharedFilesMetadata.clear().get();
      assertThat(
              SharedPreferencesUtil.getSharedPreferences(
                      context, SharedFilesMetadataUtil.MDD_SHARED_FILES, instanceId)
                  .edit()
                  .clear()
                  .commit())
          .isTrue();
    }
  }

  @Test
  public void init_alwaysMigrateToNewKey() throws InterruptedException, ExecutionException {
    Migrations.setMigratedToNewFileKey(context, false);
    flags.fileKeyVersion = Optional.of(FileKeyVersion.USE_CHECKSUM_ONLY.value);

    assertThat(sharedFilesMetadata.init().get()).isFalse();

    assertThat(Migrations.isMigratedToNewFileKey(context)).isTrue();

    // Verify that we also set the current file version to the latest version in this case.
    assertThat(Migrations.getCurrentVersion(context, mockSilentFeedback))
        .isEqualTo(FileKeyVersion.USE_CHECKSUM_ONLY);
  }

  @Test
  public void testMigrateToNewVersion_noVersionChange()
      throws InterruptedException, ExecutionException {
    flags.fileKeyVersion = Optional.of(FileKeyVersion.NEW_FILE_KEY.value);

    // Create two files, one downloaded and the other currently being downloaded.
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);
    DataFile downloadedFile = MddTestUtil.createDataFile("downloaded-file", /* fileIndex */ 0);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(
            registeredFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    SharedFile registeredSharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.SUBSCRIBED)
            .build();
    SharedFile downloadedSharedFile =
        SharedFile.newBuilder()
            .setFileName("downloaded-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(registeredKey, registeredSharedFile)).isTrue();
      assertThat(writeSharedFile(downloadedKey, downloadedSharedFile)).isTrue();
    }

    assertThat(sharedFilesMetadata.init().get()).isTrue();

    // Check that we are able to read the file after the migration.
    assertThat(sharedFilesMetadata.read(registeredKey).get()).isEqualTo(registeredSharedFile);
    assertThat(sharedFilesMetadata.read(downloadedKey).get()).isEqualTo(downloadedSharedFile);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void testMigrateToNewVersion_toAddDownloadTransform()
      throws InterruptedException, ExecutionException {
    flags.fileKeyVersion = Optional.of(FileKeyVersion.ADD_DOWNLOAD_TRANSFORM.value);

    // Create two files, one downloaded and the other currently being downloaded.
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);
    DataFile downloadedFile = MddTestUtil.createDataFile("downloaded-file", /* fileIndex */ 0);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(
            registeredFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    SharedFile registeredSharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.SUBSCRIBED)
            .build();
    SharedFile downloadedSharedFile =
        SharedFile.newBuilder()
            .setFileName("downloaded-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(registeredKey, registeredSharedFile)).isTrue();
      assertThat(writeSharedFile(downloadedKey, downloadedSharedFile)).isTrue();
    }

    assertThat(sharedFilesMetadata.init().get()).isTrue();

    // Check that we are able to read the file after the migration.
    assertThat(sharedFilesMetadata.read(registeredKey).get()).isEqualTo(registeredSharedFile);
    assertThat(sharedFilesMetadata.read(downloadedKey).get()).isEqualTo(downloadedSharedFile);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void testMigrateToNewVersion_useChecksumOnly()
      throws InterruptedException, ExecutionException {
    flags.fileKeyVersion = Optional.of(FileKeyVersion.USE_CHECKSUM_ONLY.value);

    Migrations.setCurrentVersion(context, FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);

    // Create two files, one downloaded and the other currently being downloaded.
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);
    DataFile downloadedFile = MddTestUtil.createDataFile("downloaded-file", /* fileIndex */ 0);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(
            registeredFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    SharedFile registeredSharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.SUBSCRIBED)
            .build();
    SharedFile downloadedSharedFile =
        SharedFile.newBuilder()
            .setFileName("downloaded-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(registeredKey, registeredSharedFile)).isTrue();
      assertThat(writeSharedFile(downloadedKey, downloadedSharedFile)).isTrue();
    }

    assertThat(sharedFilesMetadata.init().get()).isTrue();

    // Check that we are able to read the file after the migration.
    assertThat(sharedFilesMetadata.read(registeredKey).get()).isEqualTo(registeredSharedFile);
    assertThat(sharedFilesMetadata.read(downloadedKey).get()).isEqualTo(downloadedSharedFile);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void testMigrateFromNewFileKeyToUseChecksumOnly_corruptedMetadata()
      throws InterruptedException, ExecutionException {
    flags.fileKeyVersion = Optional.of(FileKeyVersion.USE_CHECKSUM_ONLY.value);

    // Create two files, one downloaded and the other currently being downloaded.
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);
    DataFile downloadedFile = MddTestUtil.createDataFile("downloaded-file", /* fileIndex */ 0);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(
            registeredFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    SharedFile registeredSharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.SUBSCRIBED)
            .build();
    SharedFile downloadedSharedFile =
        SharedFile.newBuilder()
            .setFileName("downloaded-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(registeredKey, registeredSharedFile)).isTrue();
      assertThat(writeSharedFile(downloadedKey, downloadedSharedFile)).isTrue();
    }

    // Set invalid version
    SharedPreferences migrationPrefs =
        context.getSharedPreferences("gms_icing_mdd_migrations", Context.MODE_PRIVATE);
    migrationPrefs.edit().putInt("mdd_file_key_version", 200).commit();

    assertThat(sharedFilesMetadata.init().get()).isTrue();
    // Check that we are able to read the file after the migration.
    assertThat(sharedFilesMetadata.read(registeredKey).get()).isEqualTo(registeredSharedFile);
    assertThat(sharedFilesMetadata.read(downloadedKey).get()).isEqualTo(downloadedSharedFile);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void testNoMigrate_corruptedMetadata() throws InterruptedException, ExecutionException {
    flags.fileKeyVersion = Optional.of(FileKeyVersion.USE_CHECKSUM_ONLY.value);

    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);

    // Create two files, one downloaded and the other currently being downloaded.
    DataFile registeredFile = MddTestUtil.createDataFile("registered-file", 0);
    DataFile downloadedFile = MddTestUtil.createDataFile("downloaded-file", /* fileIndex */ 0);

    NewFileKey downloadedKey =
        SharedFilesMetadata.createKeyFromDataFile(downloadedFile, AllowedReaders.ALL_GOOGLE_APPS);
    NewFileKey registeredKey =
        SharedFilesMetadata.createKeyFromDataFile(
            registeredFile, AllowedReaders.ONLY_GOOGLE_PLAY_SERVICES);

    SharedFile registeredSharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.SUBSCRIBED)
            .build();
    SharedFile downloadedSharedFile =
        SharedFile.newBuilder()
            .setFileName("downloaded-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(registeredKey, registeredSharedFile)).isTrue();
      assertThat(writeSharedFile(downloadedKey, downloadedSharedFile)).isTrue();
    }

    // Set invalid version
    SharedPreferences migrationPrefs =
        context.getSharedPreferences("gms_icing_mdd_migrations", Context.MODE_PRIVATE);
    migrationPrefs.edit().putInt("mdd_file_key_version", 200).commit();

    assertThat(sharedFilesMetadata.init().get()).isTrue();
    // Unable to read the file because the file key version doesn't match during migration
    assertThat(sharedFilesMetadata.read(registeredKey).get()).isNull();
    assertThat(sharedFilesMetadata.read(downloadedKey).get()).isNull();

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void testMigrateToNewVersion_downgrade_clearOff()
      throws InterruptedException, ExecutionException {
    Migrations.setCurrentVersion(context, FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);
    flags.fileKeyVersion = Optional.of(FileKeyVersion.NEW_FILE_KEY.value);

    assertThat(sharedFilesMetadata.init().get()).isFalse();
  }

  @Test
  public void testDeserializeNewFileKey() throws FileKeyDeserializationException {
    Migrations.setCurrentVersion(context, FileKeyVersion.NEW_FILE_KEY);
    String url = "https://www.gstatic.com/icing/idd/apitest/compressedFile.txt.deflate";
    int size = 15;
    String checksum = "ec876850e7ddc9ecde1a3844006e3663d70569e3";
    NewFileKey fileKey =
        NewFileKey.newBuilder()
            .setUrlToDownload(url)
            .setByteSize(size)
            .setChecksum(checksum)
            .setAllowedReaders(AllowedReaders.ALL_GOOGLE_APPS)
            .build();
    String serializedStr =
        SharedFilesMetadataUtil.getSerializedFileKey(fileKey, context, mockSilentFeedback);
    NewFileKey newFileKey =
        SharedFilesMetadataUtil.deserializeNewFileKey(serializedStr, context, mockSilentFeedback);
    assertThat(newFileKey.getUrlToDownload()).isEqualTo(url);
    assertThat(newFileKey.getByteSize()).isEqualTo(size);
    assertThat(newFileKey.getChecksum()).isEqualTo(checksum);
    assertThat(newFileKey.getAllowedReaders()).isEqualTo(AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(newFileKey.hasDownloadTransforms()).isFalse();

    // test with transforms, it should be skipped
    String serializedStrWithDownloadTransform =
        SharedFilesMetadataUtil.getSerializedFileKey(
            newFileKey.toBuilder().setDownloadTransforms(COMPRESS_TRANSFORM).build(),
            context,
            mockSilentFeedback);
    newFileKey =
        SharedFilesMetadataUtil.deserializeNewFileKey(
            serializedStrWithDownloadTransform, context, mockSilentFeedback);
    assertThat(newFileKey.getUrlToDownload()).isEqualTo(url);
    assertThat(newFileKey.getByteSize()).isEqualTo(size);
    assertThat(newFileKey.getChecksum()).isEqualTo(checksum);
    assertThat(newFileKey.getAllowedReaders()).isEqualTo(AllowedReaders.ALL_GOOGLE_APPS);

    assertThat(newFileKey.hasDownloadTransforms()).isFalse();
  }

  @Test
  public void testReadAndWriteAfterDownloadTransformMigration()
      throws InterruptedException, ExecutionException {
    Migrations.setCurrentVersion(context, FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);
    String url = "https://www.gstatic.com/icing/idd/apitest/compressedFile.txt.deflate";
    int size = 15;
    String checksum = "ec876850e7ddc9ecde1a3844006e3663d70569e3";
    NewFileKey fileKey =
        NewFileKey.newBuilder()
            .setUrlToDownload(url)
            .setByteSize(size)
            .setChecksum(checksum)
            .setAllowedReaders(AllowedReaders.ALL_GOOGLE_APPS)
            .build();
    SharedFile sharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    // Change the same key and just add download transform
    NewFileKey fileKeyWithTransforms =
        fileKey.toBuilder().setDownloadTransforms(COMPRESS_TRANSFORM).build();
    SharedFile sharedFileWithTransform =
        SharedFile.newBuilder()
            .setFileName("file-with-transform")
            .setFileStatus(FileStatus.SUBSCRIBED)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(fileKey, sharedFile)).isTrue();
      assertThat(writeSharedFile(fileKeyWithTransforms, sharedFileWithTransform)).isTrue();

      assertThat(sharedFilesMetadata.read(fileKey).get()).isEqualTo(sharedFile);
      assertThat(sharedFilesMetadata.read(fileKeyWithTransforms).get())
          .isEqualTo(sharedFileWithTransform);

      assertThat(sharedFilesMetadata.remove(fileKey).get()).isTrue();
      assertThat(sharedFilesMetadata.remove(fileKeyWithTransforms).get()).isTrue();
    }

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void testReadAndWrite_afterChecksumOnlyMigration()
      throws InterruptedException, ExecutionException {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);

    String checksum = "ec876850e7ddc9ecde1a3844006e3663d70569e3";
    NewFileKey fileKey = NewFileKey.newBuilder().setChecksum(checksum).build();
    SharedFile sharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(fileKey, sharedFile)).isTrue();
      assertThat(sharedFilesMetadata.read(fileKey).get()).isEqualTo(sharedFile);
      assertThat(sharedFilesMetadata.remove(fileKey).get()).isTrue();
    }

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void testGetAllFileKeys_afterDownloadTransformMigration()
      throws InterruptedException, ExecutionException {
    Migrations.setCurrentVersion(context, FileKeyVersion.ADD_DOWNLOAD_TRANSFORM);
    String url = "https://www.gstatic.com/icing/idd/apitest/compressedFile.txt.deflate";
    int size = 15;
    String checksum = "ec876850e7ddc9ecde1a3844006e3663d70569e3";
    NewFileKey fileKey =
        NewFileKey.newBuilder()
            .setUrlToDownload(url)
            .setByteSize(size)
            .setChecksum(checksum)
            .setAllowedReaders(AllowedReaders.ALL_GOOGLE_APPS)
            .build();
    SharedFile sharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    // Change the same key and just add download transform
    NewFileKey fileKeyWithTransforms =
        fileKey.toBuilder().setDownloadTransforms(COMPRESS_TRANSFORM).build();
    SharedFile sharedFileWithTransform =
        SharedFile.newBuilder()
            .setFileName("file-with-transform")
            .setFileStatus(FileStatus.SUBSCRIBED)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(fileKey, sharedFile)).isTrue();
      assertThat(writeSharedFile(fileKeyWithTransforms, sharedFileWithTransform)).isTrue();

      List<NewFileKey> allFileKeys = sharedFilesMetadata.getAllFileKeys().get();
      assertThat(allFileKeys).hasSize(2);
    }
  }

  @Test
  public void testGetAllFileKeys_afterChecksumOnlyMigration()
      throws InterruptedException, ExecutionException {
    Migrations.setCurrentVersion(context, FileKeyVersion.USE_CHECKSUM_ONLY);

    String checksum = "ec876850e7ddc9ecde1a3844006e3663d70569e3";
    NewFileKey fileKey =
        NewFileKey.newBuilder()
            .setChecksum(checksum)
            .setAllowedReaders(AllowedReaders.ALL_GOOGLE_APPS)
            .build();
    SharedFile sharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();

    synchronized (SharedPreferencesSharedFilesMetadata.class) {
      assertThat(writeSharedFile(fileKey, sharedFile)).isTrue();

      List<NewFileKey> allFileKeys = sharedFilesMetadata.getAllFileKeys().get();
      assertThat(allFileKeys).hasSize(1);
      assertThat(allFileKeys.get(0)).isEqualTo(fileKey);
    }
  }

  @Test
  public void testGetAllFileKeysWithCorruptedData()
      throws InterruptedException, ExecutionException {
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(
            context, SharedFilesMetadataUtil.MDD_SHARED_FILES, instanceId);
    String validKeyOne =
        "https://www.gstatic.com/icing/idd/apitest/compressedFile.txt.deflate|15|checksum|1";
    String corruptedKeyOne =
        "https://www.gstatic.com/icing/idd/apitest/compressedFile.txt.deflate|15|abc";
    SharedFile sharedFile =
        SharedFile.newBuilder()
            .setFileName("registered-file")
            .setFileStatus(FileStatus.DOWNLOAD_COMPLETE)
            .build();
    assertThat(SharedPreferencesUtil.writeProto(prefs, validKeyOne, sharedFile)).isTrue();
    assertThat(SharedPreferencesUtil.writeProto(prefs, corruptedKeyOne, sharedFile)).isTrue();
    String corruptedKeyTwo = "|15|abc";
    assertThat(SharedPreferencesUtil.writeProto(prefs, corruptedKeyTwo, sharedFile)).isTrue();
    assertThat(sharedFilesMetadata.getAllFileKeys().get()).hasSize(1);
    assertThat(
            SharedPreferencesUtil.getSharedPreferences(
                    context, SharedFilesMetadataUtil.MDD_SHARED_FILES, instanceId)
                .getAll())
        .hasSize(1);
  }

  @Test
  public void test_createKeyFromDataFile_withZipDownloadTransform() {
    DataFile zipFile = MddTestUtil.createZipFolderDataFile("testzip", 0);
    NewFileKey fileKey =
        SharedFilesMetadata.createKeyFromDataFile(zipFile, AllowedReaders.ALL_GOOGLE_APPS);
    assertThat(fileKey.getChecksum()).isEqualTo(zipFile.getDownloadedFileChecksum());
  }

  private boolean writeSharedFile(NewFileKey newFileKey, SharedFile sharedFile)
      throws InterruptedException, ExecutionException {
    return sharedFilesMetadata.write(newFileKey, sharedFile).get();
  }

  private void verifyNoErrorInPdsMigration() {}
}
