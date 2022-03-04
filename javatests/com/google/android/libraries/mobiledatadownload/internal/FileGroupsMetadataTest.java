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
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupsMetadataUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.ProtoConversionUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.android.libraries.mobiledatadownload.testing.TestFlags;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKeyProperties;
import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
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
public class FileGroupsMetadataTest {

  // TODO(b/26110951): use Parameterized runner once android_test supports it
  private enum MetadataStoreImpl {
    SP_IMPL,
  }

  // Whether to use PDS metadata store or SharedPreferences metadata store.
  @Parameter(value = 0)
  public MetadataStoreImpl metadataStoreImpl;

  @Parameter(value = 1)
  public Optional<String> instanceId;

  @Parameters(name = "metadataStoreImpl = {0} instanceId = {1}")
  public static Collection<Object[]> parameters() {
    return Arrays.asList(
        new Object[][] {
          {MetadataStoreImpl.SP_IMPL, Optional.absent()},
          {MetadataStoreImpl.SP_IMPL, Optional.of("id")},
        });
  }

  private static final String TEST_GROUP = "test-group";
  private static final String TEST_GROUP_2 = "test-group-2";
  private static final String TEST_GROUP_3 = "test-group-3";
  private static final Executor CONTROL_EXECUTOR =
      MoreExecutors.newSequentialExecutor(Executors.newCachedThreadPool());

  private static GroupKey testKey;
  private static GroupKey testKey2;
  private static GroupKey testKey3;

  private SynchronousFileStorage fileStorage;
  private Context context;
  private FakeTimeSource testClock;
  private FileGroupsMetadata fileGroupsMetadata;
  private final TestFlags flags = new TestFlags();
  @Mock EventLogger mockLogger;
  @Mock SilentFeedback mockSilentFeedback;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws Exception {

    context = ApplicationProvider.getApplicationContext();

    testKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();

    testKey2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();

    testKey3 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_3)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();

    fileStorage =
        new SynchronousFileStorage(Arrays.asList(AndroidFileBackend.builder(context).build()));

    testClock = new FakeTimeSource();
    SharedPreferencesFileGroupsMetadata sharedPreferencesImpl =
        new SharedPreferencesFileGroupsMetadata(
            context, testClock, mockSilentFeedback, instanceId, CONTROL_EXECUTOR);
    switch (metadataStoreImpl) {
      case SP_IMPL:
        fileGroupsMetadata = sharedPreferencesImpl;
        break;
    }
  }

  @After
  public void tearDown() throws Exception {
    fileGroupsMetadata.clear().get();
  }

  @Test
  public void serializeAndDeserializeFileGroupKey() throws Exception {
    String serializedGroupKey = FileGroupsMetadataUtil.getSerializedGroupKey(testKey, context);
    GroupKey deserializedGroupKey = FileGroupsMetadataUtil.deserializeGroupKey(serializedGroupKey);

    assertThat(deserializedGroupKey.getGroupName()).isEqualTo(TEST_GROUP);
    assertThat(deserializedGroupKey.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(deserializedGroupKey.getDownloaded()).isFalse();
  }

  @Test
  public void readAndWriteFileGroup() throws Exception {
    DataFileGroupInternal writeFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    DataFileGroupInternal writeFileGroup2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);
    DataFileGroupInternal writeFileGroup3 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 5);

    assertThat(fileGroupsMetadata.read(testKey).get()).isNull();
    assertThat(fileGroupsMetadata.write(testKey, writeFileGroup).get()).isTrue();

    assertThat(fileGroupsMetadata.read(testKey2).get()).isNull();
    assertThat(fileGroupsMetadata.write(testKey2, writeFileGroup2).get()).isTrue();

    assertThat(fileGroupsMetadata.read(testKey3).get()).isNull();
    assertThat(fileGroupsMetadata.write(testKey3, writeFileGroup3).get()).isTrue();

    DataFileGroupInternal readFileGroup = fileGroupsMetadata.read(testKey).get();
    MddTestUtil.assertMessageEquals(readFileGroup, writeFileGroup);

    DataFileGroupInternal readFileGroup2 = fileGroupsMetadata.read(testKey2).get();
    MddTestUtil.assertMessageEquals(readFileGroup2, writeFileGroup2);

    DataFileGroupInternal readFileGroup3 = fileGroupsMetadata.read(testKey3).get();
    MddTestUtil.assertMessageEquals(readFileGroup3, writeFileGroup3);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void readAndWriteFileGroup_withExtension() throws Exception {
    DataFileGroupInternal writeFileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);

    assertThat(fileGroupsMetadata.read(testKey).get()).isNull();
    assertThat(fileGroupsMetadata.write(testKey, writeFileGroup).get()).isTrue();

    DataFileGroupInternal readFileGroup = fileGroupsMetadata.read(testKey).get();
    MddTestUtil.assertMessageEquals(readFileGroup, writeFileGroup);

    writeFileGroup = FileGroupUtil.setStaleExpirationDate(writeFileGroup, 1000);
    assertThat(fileGroupsMetadata.write(testKey, writeFileGroup).get()).isTrue();

    readFileGroup = fileGroupsMetadata.read(testKey).get();
    MddTestUtil.assertMessageEquals(readFileGroup, writeFileGroup);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void removeFileGroup() throws Exception {
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);
    DataFileGroupInternal fileGroup3 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 5);

    assertThat(fileGroupsMetadata.write(testKey, fileGroup).get()).isTrue();
    assertThat(fileGroupsMetadata.remove(testKey).get()).isTrue();
    assertThat(fileGroupsMetadata.read(testKey).get()).isNull();

    assertThat(fileGroupsMetadata.write(testKey2, fileGroup2).get()).isTrue();
    assertThat(fileGroupsMetadata.remove(testKey2).get()).isTrue();
    assertThat(fileGroupsMetadata.read(testKey2).get()).isNull();

    assertThat(fileGroupsMetadata.write(testKey3, fileGroup3).get()).isTrue();
    assertThat(fileGroupsMetadata.remove(testKey3).get()).isTrue();
    assertThat(fileGroupsMetadata.read(testKey3).get()).isNull();

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void readAndWriteFileGroupKeyProperties() throws Exception {
    GroupKeyProperties writeGroupKeyProperties =
        GroupKeyProperties.newBuilder().setActivatedOnDevice(true).build();
    GroupKeyProperties writeGroupKeyProperties2 =
        GroupKeyProperties.newBuilder().setActivatedOnDevice(false).build();

    assertThat(fileGroupsMetadata.readGroupKeyProperties(testKey).get()).isNull();
    assertThat(fileGroupsMetadata.writeGroupKeyProperties(testKey, writeGroupKeyProperties).get())
        .isTrue();

    assertThat(fileGroupsMetadata.readGroupKeyProperties(testKey2).get()).isNull();
    assertThat(fileGroupsMetadata.writeGroupKeyProperties(testKey2, writeGroupKeyProperties2).get())
        .isTrue();

    GroupKeyProperties readGroupKeyProperties =
        fileGroupsMetadata.readGroupKeyProperties(testKey).get();
    MddTestUtil.assertMessageEquals(writeGroupKeyProperties, readGroupKeyProperties);

    GroupKeyProperties readGroupKeyProperties2 =
        fileGroupsMetadata.readGroupKeyProperties(testKey2).get();
    MddTestUtil.assertMessageEquals(writeGroupKeyProperties2, readGroupKeyProperties2);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void clear_removesAllMetadata() throws Exception {
    DataFileGroupInternal fileGroup = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 1);
    DataFileGroupInternal fileGroup3 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 5);

    File parentDir =
        new File(context.getFilesDir(), DirectoryUtil.MDD_STORAGE_MODULE + "/" + "shared");
    assertThat(parentDir.mkdirs()).isTrue();
    File garbageFile = FileGroupsMetadataUtil.getGarbageCollectorFile(context, instanceId);

    DataFileGroupInternal staleFileGroup =
        MddTestUtil.createDataFileGroupInternal("stale-group", 2).toBuilder()
            .setStaleLifetimeSecs(Duration.ofDays(1).getSeconds())
            .build();

    assertThat(fileGroupsMetadata.write(testKey, fileGroup).get()).isTrue();
    assertThat(fileGroupsMetadata.write(testKey2, fileGroup2).get()).isTrue();
    assertThat(fileGroupsMetadata.write(testKey3, fileGroup3).get()).isTrue();
    assertThat(fileGroupsMetadata.addStaleGroup(staleFileGroup).get()).isTrue();

    fileGroupsMetadata.clear().get();

    assertThat(fileGroupsMetadata.read(testKey).get()).isNull();
    assertThat(fileGroupsMetadata.read(testKey2).get()).isNull();
    assertThat(fileGroupsMetadata.read(testKey3).get()).isNull();
    assertThat(garbageFile.exists()).isFalse();

    for (File file : parentDir.listFiles()) {
      boolean unused = file.delete();
    }

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void retrieveAllGroups() throws Exception {
    GroupKey notSetDownloadedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .build();

    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    assertThat(fileGroupsMetadata.write(notSetDownloadedGroupKey, fileGroup1).get()).isTrue();

    GroupKey setTrueDownloadedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_2)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(true)
            .build();
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_2, 2);
    assertThat(fileGroupsMetadata.write(setTrueDownloadedGroupKey, fileGroup2).get()).isTrue();

    GroupKey setFalseDownloadedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP_3)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();
    DataFileGroupInternal fileGroup3 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP_3, 2);
    assertThat(fileGroupsMetadata.write(setFalseDownloadedGroupKey, fileGroup3).get()).isTrue();

    if (metadataStoreImpl == MetadataStoreImpl.SP_IMPL) {
      // Garbage entry that will create null GroupKey
      SharedPreferences prefs =
          SharedPreferencesUtil.getSharedPreferences(
              context, FileGroupsMetadataUtil.MDD_FILE_GROUPS, instanceId);
      prefs.edit().putString("garbage-key", "garbage-value").commit();
    }

    List<Pair<GroupKey, DataFileGroupInternal>> allGroups =
        fileGroupsMetadata.getAllFreshGroups().get();
    assertThat(allGroups).hasSize(3);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void removeGroups_noGroups() throws Exception {
    // Newer pending version of this group.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    GroupKey key1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();
    writePendingFileGroupToSharedPrefs(key1, fileGroup1);

    // Older downloaded version of the same group
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    GroupKey key2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(true)
            .build();
    writeDownloadedFileGroupToSharedPrefs(key2, fileGroup2);

    assertThat(fileGroupsMetadata.removeAllGroupsWithKeys(ImmutableList.of()).get()).isTrue();

    assertThat(readPendingFileGroupFromSharedPrefs(key1, true /*shouldExist*/))
        .isEqualTo(fileGroup1);
    assertThat(readDownloadedFileGroupFromSharedPrefs(key2, true /*shouldExist*/))
        .isEqualTo(fileGroup2);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void removeGroups_removePendingGroup() throws Exception {
    // Newer pending version of this group.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    GroupKey key1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();
    writePendingFileGroupToSharedPrefs(key1, fileGroup1);

    // Older downloaded version of the same group
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    GroupKey key2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(true)
            .build();
    writeDownloadedFileGroupToSharedPrefs(key2, fileGroup2);

    assertThat(fileGroupsMetadata.removeAllGroupsWithKeys(Arrays.asList(key1)).get()).isTrue();

    readPendingFileGroupFromSharedPrefs(key1, false /*shouldExist*/);
    assertThat(readDownloadedFileGroupFromSharedPrefs(key2, true /*shouldExist*/))
        .isEqualTo(fileGroup2);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void removeGroups_removeDownloadedGroup() throws Exception {
    // Newer pending version of this group.
    DataFileGroupInternal fileGroup1 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 2);
    GroupKey key1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();
    writePendingFileGroupToSharedPrefs(key1, fileGroup1);

    // Older downloaded version of the same group
    DataFileGroupInternal fileGroup2 = MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1);
    GroupKey key2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(true)
            .build();
    writeDownloadedFileGroupToSharedPrefs(key2, fileGroup2);

    assertThat(fileGroupsMetadata.removeAllGroupsWithKeys(Arrays.asList(key2)).get()).isTrue();

    assertThat(readPendingFileGroupFromSharedPrefs(key1, true /*shouldExist*/))
        .isEqualTo(fileGroup1);
    readDownloadedFileGroupFromSharedPrefs(key2, false /*shouldExist*/);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void addStaleGroup_multipleGroups() throws Exception {
    long staleExpirationLifetimeSecs = 1000;

    DataFileGroupInternal fileGroup1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setStaleLifetimeSecs(staleExpirationLifetimeSecs)
            .build();
    DataFileGroupInternal fileGroup2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3).toBuilder()
            .setStaleLifetimeSecs(staleExpirationLifetimeSecs)
            .build();

    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();

    testClock.set(15000 /* 15 seconds */);
    assertThat(fileGroupsMetadata.addStaleGroup(fileGroup1).get()).isTrue();
    assertThat(fileGroupsMetadata.addStaleGroup(fileGroup2).get()).isTrue();

    List<DataFileGroupInternal> staleGroups = fileGroupsMetadata.getAllStaleGroups().get();
    assertThat(staleGroups).hasSize(2);

    fileGroup1 = FileGroupUtil.setStaleExpirationDate(fileGroup1, staleExpirationLifetimeSecs + 15);
    fileGroup2 = FileGroupUtil.setStaleExpirationDate(fileGroup2, staleExpirationLifetimeSecs + 15);

    assertThat(staleGroups.get(0)).isEqualTo(fileGroup1);
    assertThat(staleGroups.get(1)).isEqualTo(fileGroup2);

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void removeAllStaleGroups_multipleGroups() throws Exception {
    long staleExpirationLifetimeSecs = 1000;

    List<DataFileGroupInternal> fileGroups = new ArrayList<>();
    DataFileGroupInternal fileGroup1 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 1).toBuilder()
            .setStaleLifetimeSecs(staleExpirationLifetimeSecs)
            .build();
    fileGroups.add(fileGroup1);

    DataFileGroupInternal fileGroup2 =
        MddTestUtil.createDataFileGroupInternal(TEST_GROUP, 3).toBuilder()
            .setStaleLifetimeSecs(staleExpirationLifetimeSecs)
            .build();
    fileGroups.add(fileGroup2);

    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();

    assertThat(fileGroupsMetadata.writeStaleGroups(fileGroups).get()).isTrue();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).hasSize(2);

    fileGroupsMetadata.removeAllStaleGroups().get();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void writeStaleGroups_noGroup() throws Exception {
    List<DataFileGroupInternal> fileGroups = new ArrayList<>();
    assertThat(fileGroupsMetadata.writeStaleGroups(fileGroups).get()).isTrue();
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).isEmpty();
    verifyNoErrorInPdsMigration();
  }

  /**
   * This test mainly exists to ensure that the garbage collector handles IO operations correctly
   * for large inputs.
   */
  @Test
  public void writeAndReadStaleGroups_onLotsOfFileGroups() throws Exception {
    long staleExpirationDate = 1000;

    // Create files on device so that the garbage collector can delete them
    List<DataFileGroupInternal> fileGroups = new ArrayList<>();
    for (int i = 0; i < 5; ++i) {
      DataFileGroupInternal dataFileGroup = MddTestUtil.createDataFileGroupInternal("group" + i, 1);
      dataFileGroup = FileGroupUtil.setStaleExpirationDate(dataFileGroup, staleExpirationDate);
      fileGroups.add(dataFileGroup);
    }

    assertThat(fileGroupsMetadata.writeStaleGroups(fileGroups).get()).isTrue();
    assertThat(
            fileGroupsMetadata
                .getAllStaleGroups()
                .get()
                .get(0)
                .getBookkeeping()
                .getStaleExpirationDate())
        .isEqualTo(1000);
    assertThat(fileGroupsMetadata.getAllStaleGroups().get()).containsExactlyElementsIn(fileGroups);

    verifyNoErrorInPdsMigration();
  }

  /**
   * This test mainly exists to ensure that after migrating the group metadata storage proto from
   * {@link DataFileGroup} to {@link DataFileGroupInternal}, MDD is still able to parse the group
   * metadata which was previously written to disk before the migration.
   */
  @Test
  public void writeAndReadGroups_migration_fromDataFileGroup_toDataFileGroupInternal()
      throws Exception {
    DataFileGroup fileGroup1 = MddTestUtil.createDataFileGroup(TEST_GROUP, 2);
    GroupKey key1 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(false)
            .build();
    assertThat(writeDataFileGroup(key1, fileGroup1, instanceId)).isTrue();

    // Older downloaded version of the same group
    DataFileGroup fileGroup2 = MddTestUtil.createDataFileGroup(TEST_GROUP, 1);
    GroupKey key2 =
        GroupKey.newBuilder()
            .setGroupName(TEST_GROUP)
            .setOwnerPackage(context.getPackageName())
            .setDownloaded(true)
            .build();
    assertThat(writeDataFileGroup(key2, fileGroup2, instanceId)).isTrue();

    // Make sure that parsing DataFileGroup to DataFileGroupInternal produces identical result as
    // calling proto convert.
    assertThat(fileGroupsMetadata.read(key1).get())
        .isEqualTo(ProtoConversionUtil.convert(fileGroup1));
    assertThat(fileGroupsMetadata.read(key2).get())
        .isEqualTo(ProtoConversionUtil.convert(fileGroup2));

    verifyNoErrorInPdsMigration();
  }

  @Test
  public void garbageCollectorFileSeparation() throws Exception {
    SharedPreferencesFileGroupsMetadata fileGroupsMetadataAbsent =
        new SharedPreferencesFileGroupsMetadata(
            context, testClock, mockSilentFeedback, Optional.absent(), CONTROL_EXECUTOR);

    SharedPreferencesFileGroupsMetadata fileGroupsMetadata2 =
        new SharedPreferencesFileGroupsMetadata(
            context, testClock, mockSilentFeedback, Optional.of("instance2"), CONTROL_EXECUTOR);

    SharedPreferencesFileGroupsMetadata fileGroupsMetadata3 =
        new SharedPreferencesFileGroupsMetadata(
            context, testClock, mockSilentFeedback, Optional.of("instance3"), CONTROL_EXECUTOR);

    assertThat(fileGroupsMetadataAbsent.getGarbageCollectorFile().getAbsolutePath())
        .isNotEqualTo(fileGroupsMetadata2.getGarbageCollectorFile().getAbsolutePath());

    assertThat(fileGroupsMetadata2.getGarbageCollectorFile().getAbsolutePath())
        .isNotEqualTo(fileGroupsMetadata3.getGarbageCollectorFile().getAbsolutePath());
  }

  /**
   * Writes {@link DataFileGroup} into disk. The main purpose of this method is for the convenience
   * of migration tests. Previously, the file group metadata is stored in DataFileGroup with
   * extensions. We wanted to make sure that after migrating to {@link DataFileGroupInternal}, the
   * previous metadata can still be parsed.
   */
  boolean writeDataFileGroup(
      GroupKey groupKey, DataFileGroup fileGroup, Optional<String> instanceId) {
    String serializedGroupKey = FileGroupsMetadataUtil.getSerializedGroupKey(groupKey, context);
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(
            context, FileGroupsMetadataUtil.MDD_FILE_GROUPS, instanceId);
    return SharedPreferencesUtil.writeProto(prefs, serializedGroupKey, fileGroup);
  }

  private DataFileGroupInternal readPendingFileGroupFromSharedPrefs(
      GroupKey key, boolean shouldExist) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(false).build();
    return readFileGroupFromSharedPrefs(duplicateGroupKey, shouldExist);
  }

  private void writePendingFileGroupToSharedPrefs(GroupKey key, DataFileGroupInternal group)
      throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(false).build();
    assertThat(fileGroupsMetadata.write(duplicateGroupKey, group).get()).isTrue();
  }

  private DataFileGroupInternal readDownloadedFileGroupFromSharedPrefs(
      GroupKey key, boolean shouldExist) throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(true).build();
    return readFileGroupFromSharedPrefs(duplicateGroupKey, shouldExist);
  }

  private void writeDownloadedFileGroupToSharedPrefs(GroupKey key, DataFileGroupInternal group)
      throws Exception {
    GroupKey duplicateGroupKey = key.toBuilder().setDownloaded(true).build();
    assertThat(fileGroupsMetadata.write(duplicateGroupKey, group).get()).isTrue();
  }

  private DataFileGroupInternal readFileGroupFromSharedPrefs(GroupKey key, boolean shouldExist)
      throws Exception {
    DataFileGroupInternal group = fileGroupsMetadata.read(key).get();
    if (shouldExist) {
      assertWithMessage(String.format("Expected that key %s should exist.", key))
          .that(group)
          .isNotNull();
    } else {
      assertWithMessage(String.format("Expected that key %s should not exist.", key))
          .that(group)
          .isNull();
    }
    return group;
  }

  private void verifyNoErrorInPdsMigration() {}
}
