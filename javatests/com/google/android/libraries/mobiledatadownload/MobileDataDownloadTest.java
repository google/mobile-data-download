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

import static com.google.common.labs.truth.FutureSubject.assertThat;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.TaskScheduler.ConstraintOverrides;
import com.google.android.libraries.mobiledatadownload.TaskScheduler.NetworkState;
import com.google.android.libraries.mobiledatadownload.account.AccountUtil;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.internal.MobileDataDownloadManager;
import com.google.android.libraries.mobiledatadownload.internal.logging.EventLogger;
import com.google.android.libraries.mobiledatadownload.internal.util.ProtoConversionUtil;
import com.google.android.libraries.mobiledatadownload.lite.Downloader;
import com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.labs.concurrent.LabsFutures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mobiledatadownload.ClientConfigProto.ClientFile;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup.Status;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions.DeviceNetworkPolicy;
import com.google.mobiledatadownload.internal.MetadataProto;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link com.google.android.libraries.mobiledatadownload.MobileDataDownload}. */
@RunWith(RobolectricTestRunner.class)
public class MobileDataDownloadTest {
  // Note: Control Executor must not be a single thread executor.
  private static final Executor EXECUTOR = Executors.newCachedThreadPool();
  private static final long LATCH_WAIT_TIME_MS = 1000L;

  private static final String FILE_GROUP_NAME_1 = "test-group-1";
  private static final String FILE_GROUP_NAME_2 = "test-group-2";
  private static final String FILE_ID_1 = "test-file-1";
  private static final String FILE_ID_2 = "test-file-2";
  private static final String FILE_CHECKSUM_1 = "c1ef7864c76a99ae738ddad33882ed65972c99cc";
  private static final String FILE_URL_1 = "https://www.gstatic.com/suggest-dev/odws1_test_4.jar";
  private static final int FILE_SIZE_1 = 85769;

  private static final String FILE_CHECKSUM_2 = "a1cba9d87b1440f41ce9e7da38c43e1f6bd7d5df";
  private static final String FILE_URL_2 = "https://www.gstatic.com/suggest-dev/odws1_empty.jar";
  private static final int FILE_SIZE_2 = 554;

  private final Uri onDeviceUri1 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload/files/datadownload/shared/public/file_1");
  private final Uri onDeviceDirUri =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload/files/datadownload/shared/public/dir");
  private final Uri onDeviceDirFileUri1 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload/files/datadownload/shared/public/dir/file_1");
  private final String onDeviceDirFile1Content = "Test file 1.";
  private final Uri onDeviceDirFileUri2 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload/files/datadownload/shared/public/dir/file_2");
  private final String onDeviceDirFile2Content = "Test file 2.";
  private final Uri onDeviceDirFileUri3 =
      Uri.parse(
          "android://com.google.android.libraries.mobiledatadownload/files/datadownload/shared/public/dir/sub/file");
  private final String onDeviceDirFile3Content = "Test file 3 in sub-dir.";

  private final Flags flags = new Flags() {};
  private Context context;
  private SynchronousFileStorage fileStorage;

  @Mock EventLogger mockEventLogger;
  @Mock MobileDataDownloadManager mockMobileDataDownloadManager;
  @Mock TaskScheduler mockTaskScheduler;
  @Mock FileGroupPopulator mockFileGroupPopulator;
  @Mock DownloadProgressMonitor mockDownloadMonitor;
  @Mock Downloader singleFileDownloader;

  @Captor ArgumentCaptor<GroupKey> groupKeyCaptor;
  @Captor ArgumentCaptor<List<GroupKey>> groupKeysCaptor;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws IOException {
    context = ApplicationProvider.getApplicationContext();
    fileStorage =
        new SynchronousFileStorage(
            ImmutableList.of(AndroidFileBackend.builder(context).build()) /*backends*/);
    createFile(onDeviceUri1, "test");
    fileStorage.createDirectory(onDeviceDirUri);
    createFile(onDeviceDirFileUri1, onDeviceDirFile1Content);
    createFile(onDeviceDirFileUri2, onDeviceDirFile2Content);
    createFile(onDeviceDirFileUri3, onDeviceDirFile3Content);
  }

  private void createFile(Uri uri, String content) throws IOException {
    try (OutputStream out = fileStorage.open(uri, WriteStreamOpener.create())) {
      out.write(content.getBytes(UTF_8));
    }
  }

  @Test
  public void buildGetFileGroupsByFilterRequest() throws Exception {
    Account account = AccountUtil.create("account-name", "account-type");
    GetFileGroupsByFilterRequest request1 =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .setAccountOptional(Optional.of(account))
            .build();
    assertThat(request1.groupNameOptional()).hasValue(FILE_GROUP_NAME_1);
    assertThat(request1.accountOptional()).hasValue(account);

    GetFileGroupsByFilterRequest request2 =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .build();
    assertThat(request2.groupNameOptional()).hasValue(FILE_GROUP_NAME_1);
    assertThat(request2.accountOptional()).isAbsent();

    GetFileGroupsByFilterRequest.Builder builder = GetFileGroupsByFilterRequest.newBuilder();

    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void buildGetFileGroupsByFilterRequest_groupWithNoAccountOnly() {
    Account account = AccountUtil.create("account-name", "account-type");
    GetFileGroupsByFilterRequest.Builder builder =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupWithNoAccountOnly(true)
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .setAccountOptional(Optional.of(account));

    // Make sure that when request account independent groups, accountOptional should be absent.
    assertThrows(IllegalArgumentException.class, builder::build);
  }

  @Test
  public void addFileGroup() throws Exception {
    when(mockMobileDataDownloadManager.addGroupForDownloadInternal(
            any(GroupKey.class), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(true));
    DataFileGroup dataFileGroup =
        createDataFileGroup(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            1 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(dataFileGroup).build())
                .get())
        .isTrue();
  }

  @Test
  public void addFileGroup_onFailure() throws Exception {
    when(mockMobileDataDownloadManager.addGroupForDownloadInternal(
            any(GroupKey.class), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(false));
    DataFileGroup dataFileGroup =
        createDataFileGroup(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            1 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(dataFileGroup).build())
                .get())
        .isFalse();
  }

  @Test
  public void addFileGroup_invalidOwnerPackageName() throws Exception {
    when(mockMobileDataDownloadManager.addGroupForDownloadInternal(
            any(GroupKey.class), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(true));

    // Owner Package should be same as the app package.
    DataFileGroup dataFileGroup =
        createDataFileGroup(
            FILE_GROUP_NAME_1,
            "PACKAGE_NAME",
            1 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            null /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    assertThat(
            mobileDataDownload
                .addFileGroup(
                    AddFileGroupRequest.newBuilder().setDataFileGroup(dataFileGroup).build())
                .get())
        .isFalse();
  }

  @Test
  public void addFileGroupWithFileGroupKey() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.addGroupForDownloadInternal(
            groupKeyCaptor.capture(), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(true));

    DataFileGroup dataFileGroup =
        createDataFileGroup(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            1 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    Account account = AccountUtil.create("account-name", "account-type");
    AddFileGroupRequest addFileGroupRequest =
        AddFileGroupRequest.newBuilder()
            .setDataFileGroup(dataFileGroup)
            .setAccountOptional(Optional.of(account))
            .build();

    assertThat(mobileDataDownload.addFileGroup(addFileGroupRequest).get()).isTrue();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account))
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
    verify(mockMobileDataDownloadManager)
        .addGroupForDownloadInternal(
            eq(groupKey), eq(ProtoConversionUtil.convert(dataFileGroup)), any());
  }

  @Test
  public void addFileGroupWithFileGroupKey_onFailure() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.addGroupForDownloadInternal(
            groupKeyCaptor.capture(), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(false));

    DataFileGroup dataFileGroup =
        createDataFileGroup(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            1 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    Account account = AccountUtil.create("account-name", "account-type");
    AddFileGroupRequest addFileGroupRequest =
        AddFileGroupRequest.newBuilder()
            .setDataFileGroup(dataFileGroup)
            .setAccountOptional(Optional.of(account))
            .build();

    assertThat(mobileDataDownload.addFileGroup(addFileGroupRequest).get()).isFalse();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account))
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
    verify(mockMobileDataDownloadManager)
        .addGroupForDownloadInternal(
            eq(groupKey), eq(ProtoConversionUtil.convert(dataFileGroup)), any());
  }

  @Test
  public void addFileGroupWithFileGroupKey_nullAccount() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.addGroupForDownloadInternal(
            groupKeyCaptor.capture(), any(DataFileGroupInternal.class), any()))
        .thenReturn(Futures.immediateFuture(true));

    DataFileGroup dataFileGroup =
        createDataFileGroup(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            1 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    AddFileGroupRequest addFileGroupRequest =
        AddFileGroupRequest.newBuilder().setDataFileGroup(dataFileGroup).build();

    assertThat(mobileDataDownload.addFileGroup(addFileGroupRequest).get()).isTrue();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
    verify(mockMobileDataDownloadManager)
        .addGroupForDownloadInternal(
            eq(groupKey), eq(ProtoConversionUtil.convert(dataFileGroup)), any());
  }

  @Test
  public void addFileGroupWithFileGroupKey_withVariant() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.addGroupForDownloadInternal(
            groupKeyCaptor.capture(), any(), any()))
        .thenReturn(Futures.immediateFuture(true));

    DataFileGroup dataFileGroupWithVariant =
        createDataFileGroup(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                1 /* versionNumber */,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .setVariantId("en")
            .build();

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    AddFileGroupRequest addFileGroupRequest =
        AddFileGroupRequest.newBuilder()
            .setDataFileGroup(dataFileGroupWithVariant)
            .setVariantIdOptional(Optional.of("en"))
            .build();

    assertThat(mobileDataDownload.addFileGroup(addFileGroupRequest).get()).isTrue();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setVariantId("en")
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
    verify(mockMobileDataDownloadManager)
        .addGroupForDownloadInternal(
            eq(groupKey), eq(ProtoConversionUtil.convert(dataFileGroupWithVariant)), any());
  }

  @Test
  public void removeFileGroup_onSuccess_returnsTrue() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.removeFileGroup(groupKeyCaptor.capture(), eq(false)))
        .thenReturn(Futures.immediateFuture(null /* Void */));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    RemoveFileGroupRequest removeFileGroupRequest =
        RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build();

    assertThat(mobileDataDownload.removeFileGroup(removeFileGroupRequest).get()).isTrue();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
  }

  @Test
  public void removeFileGroup_onFailure_returnsFalse() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    doThrow(new IOException())
        .when(mockMobileDataDownloadManager)
        .removeFileGroup(groupKeyCaptor.capture(), /* pendingOnly= */ eq(false));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    RemoveFileGroupRequest removeFileGroupRequest =
        RemoveFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build();

    ExecutionException exception =
        assertThrows(
            ExecutionException.class,
            () -> mobileDataDownload.removeFileGroup(removeFileGroupRequest).get());
    assertThat(exception).hasCauseThat().isInstanceOf(IOException.class);

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
  }

  @Test
  public void removeFileGroup_withAccount_returnsTrue() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.removeFileGroup(
            groupKeyCaptor.capture(), /* pendingOnly= */ eq(false)))
        .thenReturn(Futures.immediateFuture(null /* Void */));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    Account account = AccountUtil.create("account-name", "account-type");
    RemoveFileGroupRequest removeFileGroupRequest =
        RemoveFileGroupRequest.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setAccountOptional(Optional.of(account))
            .build();

    assertThat(mobileDataDownload.removeFileGroup(removeFileGroupRequest).get()).isTrue();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account))
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
  }

  @Test
  public void removeFileGroup_withVariantId_returnsTrue() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.removeFileGroup(
            groupKeyCaptor.capture(), /* pendingOnly= */ eq(false)))
        .thenReturn(Futures.immediateFuture(null /* Void */));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    RemoveFileGroupRequest removeFileGroupRequest =
        RemoveFileGroupRequest.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setVariantIdOptional(Optional.of("en"))
            .build();

    assertThat(mobileDataDownload.removeFileGroup(removeFileGroupRequest).get()).isTrue();

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setVariantId("en")
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
  }

  @Test
  public void getFileGroup() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                5 /* versionNumber */,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .setBuildId(10)
            .setVariantId("test-variant")
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(clientFileGroup.hasAccount()).isFalse();

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());
  }

  @Test
  public void getFileGroup_withDirectory() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceDirUri));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(3);
    assertThat(clientFileGroup.hasAccount()).isFalse();

    List<ClientFile> clientFileList = clientFileGroup.getFileList();
    assertThat(clientFileList)
        .contains(
            ClientFile.newBuilder()
                .setFileId("/file_1")
                .setFileUri(onDeviceDirFileUri1.toString())
                .setFullSizeInBytes(onDeviceDirFile1Content.getBytes(UTF_8).length)
                .build());
    assertThat(clientFileList)
        .contains(
            ClientFile.newBuilder()
                .setFileId("/file_2")
                .setFileUri(onDeviceDirFileUri2.toString())
                .setFullSizeInBytes(onDeviceDirFile2Content.getBytes(UTF_8).length)
                .build());
    assertThat(clientFileList)
        .contains(
            ClientFile.newBuilder()
                .setFileId("/sub/file")
                .setFileUri(onDeviceDirFileUri3.toString())
                .setFullSizeInBytes(onDeviceDirFile3Content.getBytes(UTF_8).length)
                .build());
  }

  @Test
  public void getFileGroup_withDirectory_withTraverseDisabled() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceDirUri));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder()
                    .setPreserveZipDirectories(true)
                    .setGroupName(FILE_GROUP_NAME_1)
                    .build())
            .get();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(clientFileGroup.hasAccount()).isFalse();

    List<ClientFile> clientFileList = clientFileGroup.getFileList();
    assertThat(clientFileList)
        .contains(
            ClientFile.newBuilder()
                .setFileId("test-file-1")
                .setFileUri(onDeviceDirUri.toString())
                .setFullSizeInBytes(FILE_SIZE_1)
                .build());
    assertThat(fileStorage.isDirectory(Uri.parse(clientFileList.get(0).getFileUri()))).isTrue();
  }

  @Test
  public void removeFileGroupsByFilter_withAccountSpecified_removesMatchingAccountGroups()
      throws Exception {
    List<Pair<GroupKey, DataFileGroupInternal>> keyToGroupList = new ArrayList<>();
    Account account1 = AccountUtil.create("account-name", "account-type");
    Account account2 = AccountUtil.create("account-name2", "account-type");

    DataFileGroupInternal downloadedFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                /* versionNumber = */ 5,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .build();
    DataFileGroupInternal pendingFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                /* versionNumber = */ 6,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .build();

    GroupKey account1GroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account1))
            .build();
    GroupKey downloadedAccount1GroupKey = account1GroupKey.toBuilder().setDownloaded(true).build();
    GroupKey pendingAccount1GroupKey = account1GroupKey.toBuilder().setDownloaded(false).build();

    GroupKey account2GroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account2))
            .build();
    GroupKey downloadedAccount2GroupKey = account2GroupKey.toBuilder().setDownloaded(true).build();
    GroupKey pendingAccount2GroupKey = account2GroupKey.toBuilder().setDownloaded(false).build();

    GroupKey noAccountGroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    GroupKey downloadedGroupKey = noAccountGroupKey.toBuilder().setDownloaded(true).build();
    GroupKey pendingGroupKey = noAccountGroupKey.toBuilder().setDownloaded(false).build();

    keyToGroupList.add(Pair.create(downloadedGroupKey, downloadedFileGroup));
    keyToGroupList.add(Pair.create(downloadedAccount1GroupKey, downloadedFileGroup));
    keyToGroupList.add(Pair.create(downloadedAccount2GroupKey, downloadedFileGroup));
    keyToGroupList.add(Pair.create(pendingGroupKey, pendingFileGroup));
    keyToGroupList.add(Pair.create(pendingAccount1GroupKey, pendingFileGroup));
    keyToGroupList.add(Pair.create(pendingAccount2GroupKey, pendingFileGroup));

    when(mockMobileDataDownloadManager.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(keyToGroupList));
    when(mockMobileDataDownloadManager.removeFileGroups(groupKeysCaptor.capture()))
        .thenReturn(Futures.immediateVoidFuture());

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            /* foregroundDownloadServiceClassOptional = */ Optional.absent(),
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // Setup request that matches all fresh groups, but also include account to make sure only
    // account associated file groups are removed
    RemoveFileGroupsByFilterRequest removeFileGroupsByFilterRequest =
        RemoveFileGroupsByFilterRequest.newBuilder()
            .setAccountOptional(Optional.of(account1))
            .build();

    RemoveFileGroupsByFilterResponse response =
        mobileDataDownload.removeFileGroupsByFilter(removeFileGroupsByFilterRequest).get();

    assertThat(response.removedFileGroupsCount()).isEqualTo(1);
    verify(mockMobileDataDownloadManager, times(1)).removeFileGroups(anyList());
    List<GroupKey> removedGroupKeys = groupKeysCaptor.getValue();
    assertThat(removedGroupKeys).containsExactly(account1GroupKey);
  }

  @Test
  public void getFileGroup_nullFileUri() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(
            Futures.immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                    .setMessage("Fail to download file group")
                    .build()));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    assertNull(
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get());
  }

  @Test
  public void getFileGroup_null() throws Exception {
    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(null));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    assertNull(
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get());

    verifyNoInteractions(mockEventLogger);
  }

  @Test
  public void getFileGroup_withAccount() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    Account account = AccountUtil.create("account-name", "account-type");
    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setAccountOptional(Optional.of(account))
                    .build())
            .get();
    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getAccount()).isEqualTo(AccountUtil.serialize(account));
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account))
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
  }

  @Test
  public void getFileGroup_withVariantId() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                5 /* versionNumber */,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .setVariantId("en")
            .build();

    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(
                GetFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setVariantIdOptional(Optional.of("en"))
                    .build())
            .get();

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getAccount()).isEmpty();
    assertThat(clientFileGroup.getVariantId()).isEqualTo("en");
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setVariantId("en")
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(groupKey);
  }

  @Test
  public void getFileGroup_includesIdentifyingProperties() throws Exception {
    Any customProperty =
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/google.protobuf.stringvalue")
            .setValue(StringValue.of("TEST_PROPERTY").toByteString())
            .build();
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                5 /* versionNumber */,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .setBuildId(1L)
            .setVariantId("testvariant")
            .setCustomProperty(customProperty)
            .build();

    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get();

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getBuildId()).isEqualTo(1L);
    assertThat(clientFileGroup.getVariantId()).isEqualTo("testvariant");
    assertThat(clientFileGroup.getCustomProperty()).isEqualTo(customProperty);
  }

  @Test
  public void getFileGroup_includesLocale() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                5 /* versionNumber */,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .addLocale("en-US")
            .addLocale("en-CA")
            .build();

    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get();

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getLocaleList()).containsExactly("en-US", "en-CA");
  }

  @Test
  public void getFileGroup_includesGroupLevelMetadataWhenProvided() throws Exception {
    Any customMetadata =
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/google.protobuf.stringvalue")
            .setValue(StringValue.of("TEST_METADATA").toByteString())
            .build();
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                5 /* versionNumber */,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .setCustomMetadata(customMetadata)
            .build();

    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get();

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getCustomMetadata()).isEqualTo(customMetadata);
  }

  @Test
  public void getFileGroup_includesFileLevelMetadataWhenProvided() throws Exception {
    Any customMetadata =
        Any.newBuilder()
            .setTypeUrl("type.googleapis.com/google.protobuf.stringvalue")
            .setValue(StringValue.of("TEST_METADATA").toByteString())
            .build();
    DataFileGroupInternal dataFileGroup =
        DataFileGroupInternal.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .addFile(
                MetadataProto.DataFile.newBuilder()
                    .setFileId(FILE_ID_1)
                    .setUrlToDownload(FILE_URL_1)
                    .setCustomMetadata(customMetadata)
                    .build())
            .addFile(
                MetadataProto.DataFile.newBuilder()
                    .setFileId(FILE_ID_2)
                    .setUrlToDownload(FILE_URL_2)
                    .build())
            .build();

    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(1), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get();

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.hasCustomMetadata()).isFalse();

    ClientFile clientFile1 =
        clientFileGroup.getFile(0).getFileId().equals(FILE_ID_1)
            ? clientFileGroup.getFile(0)
            : clientFileGroup.getFile(1);
    ClientFile clientFile2 =
        clientFileGroup.getFile(0).getFileId().equals(FILE_ID_1)
            ? clientFileGroup.getFile(1)
            : clientFileGroup.getFile(0);

    assertThat(clientFile1.hasCustomMetadata()).isTrue();
    assertThat(clientFile1.getCustomMetadata()).isEqualTo(customMetadata);
    assertThat(clientFile2.hasCustomMetadata()).isFalse();
  }

  @Test
  public void getFileGroupsByFilter_singleGroup() throws Exception {
    List<Pair<GroupKey, DataFileGroupInternal>> keyDataFileGroupList = new ArrayList<>();

    DataFileGroupInternal downloadedFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);

    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(eq(groupKey), eq(true)))
        .thenReturn(Futures.immediateFuture(downloadedFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(
            downloadedFileGroup.getFile(0), downloadedFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    keyDataFileGroupList.add(
        Pair.create(groupKey.toBuilder().setDownloaded(true).build(), downloadedFileGroup));

    DataFileGroupInternal pendingFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            7 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_2},
            new String[] {FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    pendingFileGroup =
        pendingFileGroup.toBuilder()
            .setFile(0, pendingFileGroup.getFile(0).toBuilder().setDownloadedFileByteSize(222222))
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey, false))
        .thenReturn(Futures.immediateFuture(pendingFileGroup));
    keyDataFileGroupList.add(
        Pair.create(groupKey.toBuilder().setDownloaded(false).build(), pendingFileGroup));

    DataFileGroupInternal pendingFileGroup2 =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_2,
            context.getPackageName(),
            4 /* versionNumber */,
            new String[] {FILE_ID_1, FILE_ID_2},
            new int[] {FILE_SIZE_1, FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_1, FILE_CHECKSUM_2},
            new String[] {FILE_URL_1, FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_2)
            .setOwnerPackage(context.getPackageName())
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey2, false))
        .thenReturn(Futures.immediateFuture(pendingFileGroup2));
    keyDataFileGroupList.add(
        Pair.create(groupKey2.toBuilder().setDownloaded(false).build(), pendingFileGroup2));

    when(mockMobileDataDownloadManager.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(keyDataFileGroupList));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // We should get back 2 groups for FILE_GROUP_NAME_1.
    GetFileGroupsByFilterRequest getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .build();
    ImmutableList<ClientFileGroup> clientFileGroups =
        mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    assertThat(clientFileGroups).hasSize(2);

    ClientFileGroup downloadedClientFileGroup = clientFileGroups.get(0);
    assertThat(downloadedClientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(downloadedClientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(downloadedClientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(downloadedClientFileGroup.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(downloadedClientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(downloadedClientFileGroup.hasAccount()).isFalse();

    ClientFile clientFile = downloadedClientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());
    assertThat(clientFile.getFullSizeInBytes()).isEqualTo(FILE_SIZE_1);

    ClientFileGroup pendingClientFileGroup = clientFileGroups.get(1);
    assertThat(pendingClientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(pendingClientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(pendingClientFileGroup.getVersionNumber()).isEqualTo(7);
    assertThat(pendingClientFileGroup.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingClientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(pendingClientFileGroup.hasAccount()).isFalse();

    clientFile = pendingClientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getDownloadSizeInBytes()).isEqualTo(222222);
    assertThat(clientFile.getFileUri()).isEmpty();

    // We should get back 1 group for FILE_GROUP_NAME_2.
    getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_2))
            .build();
    clientFileGroups = mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    ClientFileGroup pendingClientFileGroup2 = clientFileGroups.get(0);
    assertThat(pendingClientFileGroup2.getGroupName()).isEqualTo(FILE_GROUP_NAME_2);
    assertThat(pendingClientFileGroup2.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(pendingClientFileGroup2.getVersionNumber()).isEqualTo(4);
    assertThat(pendingClientFileGroup2.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingClientFileGroup2.getFileCount()).isEqualTo(2);
    assertThat(pendingClientFileGroup2.hasAccount()).isFalse();
  }

  @Test
  public void getFileGroupsByFilter_includeAllGroups() throws Exception {
    List<Pair<GroupKey, DataFileGroupInternal>> keyDataFileGroupList = new ArrayList<>();

    Account account = AccountUtil.create("account-name", "account-type");

    DataFileGroupInternal downloadedFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account))
            .build();
    when(mockMobileDataDownloadManager.getDataFileUri(
            downloadedFileGroup.getFile(0), downloadedFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    keyDataFileGroupList.add(
        Pair.create(groupKey.toBuilder().setDownloaded(true).build(), downloadedFileGroup));

    DataFileGroupInternal pendingFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            7,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_2},
            new String[] {FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    keyDataFileGroupList.add(
        Pair.create(groupKey.toBuilder().setDownloaded(false).build(), pendingFileGroup));

    DataFileGroupInternal pendingFileGroup2 =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_2,
            context.getPackageName(),
            4 /* versionNumber */,
            new String[] {FILE_ID_1, FILE_ID_2},
            new int[] {FILE_SIZE_1, FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_1, FILE_CHECKSUM_2},
            new String[] {FILE_URL_1, FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_2)
            .setOwnerPackage(context.getPackageName())
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey2, false))
        .thenReturn(Futures.immediateFuture(pendingFileGroup2));
    keyDataFileGroupList.add(
        Pair.create(groupKey2.toBuilder().setDownloaded(false).build(), pendingFileGroup2));

    when(mockMobileDataDownloadManager.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(keyDataFileGroupList));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // We should get back all 3 groups for this key.
    GetFileGroupsByFilterRequest getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build();
    List<ClientFileGroup> clientFileGroups =
        mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    assertThat(clientFileGroups).hasSize(3);

    ClientFileGroup downloadedClientFileGroup = clientFileGroups.get(0);
    assertThat(downloadedClientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(downloadedClientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(downloadedClientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(downloadedClientFileGroup.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(downloadedClientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(downloadedClientFileGroup.getAccount()).isEqualTo(AccountUtil.serialize(account));

    ClientFile clientFile = downloadedClientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());
    assertThat(clientFile.getFullSizeInBytes()).isEqualTo(FILE_SIZE_1);

    ClientFileGroup pendingClientFileGroup = clientFileGroups.get(1);
    assertThat(pendingClientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(pendingClientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(pendingClientFileGroup.getVersionNumber()).isEqualTo(7);
    assertThat(pendingClientFileGroup.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingClientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(pendingClientFileGroup.getAccount()).isEqualTo(AccountUtil.serialize(account));

    clientFile = pendingClientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEmpty();

    ClientFileGroup pendingClientFileGroup2 = clientFileGroups.get(2);
    assertThat(pendingClientFileGroup2.getGroupName()).isEqualTo(FILE_GROUP_NAME_2);
    assertThat(pendingClientFileGroup2.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(pendingClientFileGroup2.getVersionNumber()).isEqualTo(4);
    assertThat(pendingClientFileGroup2.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingClientFileGroup2.getFileCount()).isEqualTo(2);
    assertThat(pendingClientFileGroup2.hasAccount()).isFalse();
  }

  @Test
  public void getFileGroupsByFilter_noData() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);
    when(mockMobileDataDownloadManager.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(ImmutableList.of()));

    GetFileGroupsByFilterRequest getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder().setIncludeAllGroups(true).build();
    List<ClientFileGroup> clientFileGroups =
        mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    assertThat(clientFileGroups).isEmpty();

    getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .build();
    clientFileGroups = mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    assertThat(clientFileGroups).isEmpty();

    verifyNoInteractions(mockEventLogger);
  }

  @Test
  public void getFileGroupsByFilter_withAccount() throws Exception {
    List<Pair<GroupKey, DataFileGroupInternal>> keyDataFileGroupList = new ArrayList<>();

    Account account1 = AccountUtil.create("account-name-1", "account-type");
    Account account2 = AccountUtil.create("account-name-2", "account-type");

    DataFileGroupInternal downloadedFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account1))
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey, true))
        .thenReturn(Futures.immediateFuture(downloadedFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(
            downloadedFileGroup.getFile(0), downloadedFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    keyDataFileGroupList.add(
        Pair.create(groupKey.toBuilder().setDownloaded(true).build(), downloadedFileGroup));

    DataFileGroupInternal pendingFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            7 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_2},
            new String[] {FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    when(mockMobileDataDownloadManager.getFileGroup(groupKey, false))
        .thenReturn(Futures.immediateFuture(pendingFileGroup));
    keyDataFileGroupList.add(
        Pair.create(groupKey.toBuilder().setDownloaded(false).build(), pendingFileGroup));

    DataFileGroupInternal pendingFileGroup2 =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            4 /* versionNumber */,
            new String[] {FILE_ID_1, FILE_ID_2},
            new int[] {FILE_SIZE_1, FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_1, FILE_CHECKSUM_2},
            new String[] {FILE_URL_1, FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account2))
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey2, false))
        .thenReturn(Futures.immediateFuture(pendingFileGroup2));
    keyDataFileGroupList.add(
        Pair.create(groupKey2.toBuilder().setDownloaded(false).build(), pendingFileGroup2));

    when(mockMobileDataDownloadManager.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(keyDataFileGroupList));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.absent() /* downloadMonitorOptional */,
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // We should get back 2 groups for FILE_GROUP_NAME_1 with account1.
    GetFileGroupsByFilterRequest getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .setAccountOptional(Optional.of(account1))
            .build();
    ImmutableList<ClientFileGroup> clientFileGroups =
        mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    assertThat(clientFileGroups).hasSize(2);

    ClientFileGroup downloadedClientFileGroup = clientFileGroups.get(0);
    assertThat(downloadedClientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(downloadedClientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(downloadedClientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(downloadedClientFileGroup.getAccount()).isEqualTo(AccountUtil.serialize(account1));
    assertThat(downloadedClientFileGroup.getStatus()).isEqualTo(Status.DOWNLOADED);
    assertThat(downloadedClientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = downloadedClientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());
    assertThat(clientFile.getFullSizeInBytes()).isEqualTo(FILE_SIZE_1);

    ClientFileGroup pendingClientFileGroup = clientFileGroups.get(1);
    assertThat(pendingClientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(pendingClientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(pendingClientFileGroup.getVersionNumber()).isEqualTo(7);
    assertThat(pendingClientFileGroup.getAccount()).isEqualTo(AccountUtil.serialize(account1));
    assertThat(pendingClientFileGroup.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingClientFileGroup.getFileCount()).isEqualTo(1);

    clientFile = pendingClientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEmpty();

    // We should get back 1 group for FILE_GROUP_NAME_1 with account2.
    getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .setAccountOptional(Optional.of(account2))
            .build();
    clientFileGroups = mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    ClientFileGroup pendingClientFileGroup2 = clientFileGroups.get(0);
    assertThat(pendingClientFileGroup2.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(pendingClientFileGroup2.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(pendingClientFileGroup2.getVersionNumber()).isEqualTo(4);
    assertThat(pendingClientFileGroup2.getAccount()).isEqualTo(AccountUtil.serialize(account2));
    assertThat(pendingClientFileGroup2.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingClientFileGroup2.getFileCount()).isEqualTo(2);
  }

  @Test
  public void getFileGroupsByFilter_groupWithNoAccountOnly() throws Exception {
    List<Pair<GroupKey, DataFileGroupInternal>> keyDataFileGroupList = new ArrayList<>();

    Account account1 = AccountUtil.create("account-name-1", "account-type");
    Account account2 = AccountUtil.create("account-name-2", "account-type");

    // downloadedFileGroup is associated with account1.
    DataFileGroupInternal downloadedFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            /*versionNumber=*/ 5,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account1))
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey, true))
        .thenReturn(Futures.immediateFuture(downloadedFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(
            downloadedFileGroup.getFile(0), downloadedFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    keyDataFileGroupList.add(
        Pair.create(groupKey.toBuilder().setDownloaded(true).build(), downloadedFileGroup));

    // pendingFileGroup is associated with account2.
    DataFileGroupInternal pendingFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            /*versionNumber=*/ 7,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_2},
            new String[] {FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setAccount(AccountUtil.serialize(account2))
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey2, false))
        .thenReturn(Futures.immediateFuture(pendingFileGroup));
    keyDataFileGroupList.add(
        Pair.create(groupKey2.toBuilder().setDownloaded(false).build(), pendingFileGroup));

    // pendingFileGroup2 is an account independent group.
    DataFileGroupInternal pendingFileGroup2 =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            /*versionNumber=*/ 4,
            new String[] {FILE_ID_1, FILE_ID_2},
            new int[] {FILE_SIZE_1, FILE_SIZE_2},
            new String[] {FILE_CHECKSUM_1, FILE_CHECKSUM_2},
            new String[] {FILE_URL_1, FILE_URL_2},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    GroupKey groupKey3 =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    when(mockMobileDataDownloadManager.getFileGroup(groupKey3, false))
        .thenReturn(Futures.immediateFuture(pendingFileGroup2));
    keyDataFileGroupList.add(
        Pair.create(groupKey3.toBuilder().setDownloaded(false).build(), pendingFileGroup2));

    when(mockMobileDataDownloadManager.getAllFreshGroups())
        .thenReturn(Futures.immediateFuture(keyDataFileGroupList));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /*fileGroupPopulatorList=*/ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /*downloadMonitorOptional=*/ Optional.absent(),
            /* foregroundDownloadServiceClassOptional = */ Optional.absent(),
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // We should get back only 1 group for FILE_GROUP_NAME_1 with groupWithNoAccountOnly being set
    // to true.
    GetFileGroupsByFilterRequest getFileGroupsByFilterRequest =
        GetFileGroupsByFilterRequest.newBuilder()
            .setGroupWithNoAccountOnly(true)
            .setGroupNameOptional(Optional.of(FILE_GROUP_NAME_1))
            .build();
    ImmutableList<ClientFileGroup> clientFileGroups =
        mobileDataDownload.getFileGroupsByFilter(getFileGroupsByFilterRequest).get();
    assertThat(clientFileGroups).hasSize(1);

    ClientFileGroup pendingClientFileGroup = clientFileGroups.get(0);
    assertThat(pendingClientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(pendingClientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(pendingClientFileGroup.getVersionNumber()).isEqualTo(4);
    assertThat(pendingClientFileGroup.getStatus()).isEqualTo(Status.PENDING);
    assertThat(pendingClientFileGroup.getFileCount()).isEqualTo(2);
    assertThat(pendingClientFileGroup.hasAccount()).isFalse();
  }

  @Test
  public void importFiles_whenSuccessful_returns() throws Exception {
    String inlineFileUrl = String.format("inlinefile:sha1:%s", FILE_CHECKSUM_1);
    DataFile inlineFile =
        DataFile.newBuilder()
            .setFileId(FILE_ID_1)
            .setByteSize(FILE_SIZE_1)
            .setChecksum(FILE_CHECKSUM_1)
            .setUrlToDownload(inlineFileUrl)
            .build();
    ImmutableList<DataFile> updatedDataFileList = ImmutableList.of(inlineFile);
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            FILE_ID_1, FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    // TODO: rely on actual implementation once feature is fully implemented.
    when(mockMobileDataDownloadManager.importFiles(
            any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Futures.immediateVoidFuture());

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()),
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // Since we use mocks, just call the method directly, no need to call addFileGroup first
    mobileDataDownload
        .importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME_1)
                .setBuildId(1)
                .setVariantId("testvariant")
                .setUpdatedDataFileList(updatedDataFileList)
                .setInlineFileMap(inlineFileMap)
                .build())
        .get();

    // Verify mocks were called
    GroupKey expectedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    ImmutableList<MetadataProto.DataFile> expectedDataFileList =
        ImmutableList.of(ProtoConversionUtil.convertDataFile(inlineFile));
    verify(mockMobileDataDownloadManager)
        .importFiles(
            eq(expectedGroupKey),
            eq(1L),
            eq("testvariant"),
            eq(expectedDataFileList),
            eq(inlineFileMap),
            eq(Optional.absent()),
            any());
  }

  @Test
  public void importFiles_whenAccountIsSpecified_usesAccount() throws Exception {
    Account account = AccountUtil.create("account-name", "account-type");
    String inlineFileUrl = String.format("inlinefile:sha1:%s", FILE_CHECKSUM_1);
    DataFile inlineFile =
        DataFile.newBuilder()
            .setFileId(FILE_ID_1)
            .setByteSize(FILE_SIZE_1)
            .setChecksum(FILE_CHECKSUM_1)
            .setUrlToDownload(inlineFileUrl)
            .build();
    ImmutableList<DataFile> updatedDataFileList = ImmutableList.of(inlineFile);
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            FILE_ID_1, FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    // TODO: rely on actual implementation once feature is fully implemented.
    when(mockMobileDataDownloadManager.importFiles(
            any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Futures.immediateVoidFuture());

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()),
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // Since we use mocks, just call the method directly, no need to call addFileGroup first
    mobileDataDownload
        .importFiles(
            ImportFilesRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME_1)
                .setAccountOptional(Optional.of(account))
                .setBuildId(1)
                .setVariantId("testvariant")
                .setUpdatedDataFileList(updatedDataFileList)
                .setInlineFileMap(inlineFileMap)
                .build())
        .get();

    // Verify mocks were called
    GroupKey expectedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setAccount(AccountUtil.serialize(account))
            .setOwnerPackage(context.getPackageName())
            .build();
    ImmutableList<MetadataProto.DataFile> expectedDataFileList =
        ImmutableList.of(ProtoConversionUtil.convertDataFile(inlineFile));
    verify(mockMobileDataDownloadManager)
        .importFiles(
            eq(expectedGroupKey),
            eq(1L),
            eq("testvariant"),
            eq(expectedDataFileList),
            eq(inlineFileMap),
            eq(Optional.absent()),
            any());
  }

  @Test
  public void importFiles_whenFails_returnsFailure() throws Exception {
    String inlineFileUrl = String.format("inlinefile:%s", FILE_CHECKSUM_1);
    DataFile inlineFile =
        DataFile.newBuilder()
            .setFileId(FILE_ID_1)
            .setByteSize(FILE_SIZE_1)
            .setChecksum(FILE_CHECKSUM_1)
            .setUrlToDownload(inlineFileUrl)
            .build();
    ImmutableList<DataFile> updatedDataFileList = ImmutableList.of(inlineFile);
    ImmutableMap<String, FileSource> inlineFileMap =
        ImmutableMap.of(
            FILE_ID_1, FileSource.ofByteString(ByteString.copyFromUtf8("TEST_CONTENT")));

    // TODO: rely on actual implementation once feature is fully implemented.
    when(mockMobileDataDownloadManager.importFiles(
            any(), anyLong(), any(), any(), any(), any(), any()))
        .thenReturn(Futures.immediateFailedFuture(new Exception("Test failure")));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()),
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    // Since we use mocks, just call the method directly, no need to call addFileGroup first
    ExecutionException ex =
        assertThrows(
            ExecutionException.class,
            () ->
                mobileDataDownload
                    .importFiles(
                        ImportFilesRequest.newBuilder()
                            .setGroupName(FILE_GROUP_NAME_1)
                            .setBuildId(1)
                            .setVariantId("testvariant")
                            .setUpdatedDataFileList(updatedDataFileList)
                            .setInlineFileMap(inlineFileMap)
                            .build())
                    .get());
    assertThat(ex).hasMessageThat().contains("Test failure");

    // Verify mocks were called
    GroupKey expectedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .build();
    ImmutableList<MetadataProto.DataFile> expectedDataFileList =
        ImmutableList.of(ProtoConversionUtil.convertDataFile(inlineFile));
    verify(mockMobileDataDownloadManager)
        .importFiles(
            eq(expectedGroupKey),
            eq(1L),
            eq("testvariant"),
            eq(expectedDataFileList),
            eq(inlineFileMap),
            eq(Optional.absent()),
            any());
  }

  @Test
  public void downloadFileGroup() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    CountDownLatch onCompleteLatch = new CountDownLatch(1);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .downloadFileGroup(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setListenerOptional(
                        Optional.of(
                            new DownloadListener() {
                              @Override
                              public void onProgress(long currentSize) {}

                              @Override
                              public void onComplete(ClientFileGroup clientFileGroup) {
                                assertThat(clientFileGroup.getGroupName())
                                    .isEqualTo(FILE_GROUP_NAME_1);
                                assertThat(clientFileGroup.getOwnerPackage())
                                    .isEqualTo(context.getPackageName());
                                assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
                                assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

                                // This is to verify that onComplete is called.
                                onCompleteLatch.countDown();
                              }
                            }))
                    .build())
            .get();

    // Verify that onComplete is called.
    if (!onCompleteLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException("onComplete is not called");
    }

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(clientFileGroup.hasAccount()).isFalse();

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor)
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));
    verify(mockDownloadMonitor).removeDownloadListener(eq(FILE_GROUP_NAME_1));

    assertThat(groupKeyCaptor.getValue().getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(groupKeyCaptor.getValue().hasAccount()).isFalse();
  }

  @Test
  public void downloadFileGroup_failed() throws Exception {
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(
            Futures.immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                    .setMessage("Fail to download file group")
                    .build()));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroup(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME_1)
                .setListenerOptional(
                    Optional.of(
                        new DownloadListener() {
                          @Override
                          public void onProgress(long currentSize) {}

                          @Override
                          public void onComplete(ClientFileGroup clientFileGroup) {}
                        }))
                .build());

    CountDownLatch onFailureLatch = new CountDownLatch(1);

    Futures.addCallback(
        downloadFuture,
        new FutureCallback<ClientFileGroup>() {
          @Override
          public void onSuccess(ClientFileGroup result) {}

          @Override
          public void onFailure(Throwable t) {
            // This is to ensure that onFailure is called.
            onFailureLatch.countDown();
          }
        },
        MoreExecutors.directExecutor());

    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e).hasMessageThat().contains("Fail");

    if (!onFailureLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException("latch timeout: onFailure is not called");
    }

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor)
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));
    verify(mockDownloadMonitor).removeDownloadListener(eq(FILE_GROUP_NAME_1));

    assertThat(groupKeyCaptor.getValue().getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(groupKeyCaptor.getValue().hasAccount()).isFalse();
  }

  @Test
  public void downloadFileGroup_withAccount() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    CountDownLatch onCompleteLatch = new CountDownLatch(1);

    Account account = AccountUtil.create("account-name", "account-type");
    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .downloadFileGroup(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setAccountOptional(Optional.of(account))
                    .setListenerOptional(
                        Optional.of(
                            new DownloadListener() {
                              @Override
                              public void onProgress(long currentSize) {}

                              @Override
                              public void onComplete(ClientFileGroup clientFileGroup) {
                                assertThat(clientFileGroup.getGroupName())
                                    .isEqualTo(FILE_GROUP_NAME_1);
                                assertThat(clientFileGroup.getOwnerPackage())
                                    .isEqualTo(context.getPackageName());
                                assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
                                assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

                                // This is to verify that onComplete is called.
                                onCompleteLatch.countDown();
                              }
                            }))
                    .build())
            .get();

    // Verify that onComplete is called.
    if (!onCompleteLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException("onComplete is not called");
    }

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getAccount()).isEqualTo(AccountUtil.serialize(account));
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor)
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));
    verify(mockDownloadMonitor).removeDownloadListener(eq(FILE_GROUP_NAME_1));

    assertThat(groupKeyCaptor.getValue().getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(groupKeyCaptor.getValue().getAccount()).isEqualTo(AccountUtil.serialize(account));
  }

  @Test
  public void downloadFileGroup_withVariantId() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                /* versionNumber = */ 5,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .setVariantId("en")
            .build();

    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of() /* fileGroupPopulatorList */,
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .downloadFileGroup(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setVariantIdOptional(Optional.of("en"))
                    .build())
            .get();

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getVariantId()).isEqualTo("en");

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());

    GroupKey expectedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setVariantId("en")
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(expectedGroupKey);
  }

  @Test
  public void downloadFileGroupWithForegroundService() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            /* versionNumber = */ 5,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), anyBoolean()))
        .thenReturn(Futures.immediateFuture(dataFileGroup));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()),
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    CountDownLatch onCompleteLatch = new CountDownLatch(1);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .downloadFileGroupWithForegroundService(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setListenerOptional(
                        Optional.of(
                            new DownloadListener() {
                              @Override
                              public void onProgress(long currentSize) {}

                              @Override
                              public void onComplete(ClientFileGroup clientFileGroup) {
                                assertThat(clientFileGroup.getGroupName())
                                    .isEqualTo(FILE_GROUP_NAME_1);
                                assertThat(clientFileGroup.getOwnerPackage())
                                    .isEqualTo(context.getPackageName());
                                assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
                                assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

                                // This is to verify that onComplete is called.
                                onCompleteLatch.countDown();
                              }
                            }))
                    .build())
            .get();

    // Verify that onComplete is called.
    if (!onCompleteLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException("onComplete is not called");
    }

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(clientFileGroup.hasAccount()).isFalse();

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor)
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));
    verify(mockDownloadMonitor).removeDownloadListener(eq(FILE_GROUP_NAME_1));

    assertThat(groupKeyCaptor.getValue().getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(groupKeyCaptor.getValue().hasAccount()).isFalse();
  }

  @Test
  public void downloadFileGroupWithForegroundService_failed() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            /* versionNumber = */ 5,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(
            Futures.immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                    .setMessage("Fail to download file group")
                    .build()));
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(false)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(true)))
        .thenReturn(Futures.immediateFuture(null));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroupWithForegroundService(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME_1)
                .setListenerOptional(
                    Optional.of(
                        new DownloadListener() {
                          @Override
                          public void onProgress(long currentSize) {}

                          @Override
                          public void onComplete(ClientFileGroup clientFileGroup) {}
                        }))
                .build());

    CountDownLatch onFailureLatch = new CountDownLatch(1);

    Futures.addCallback(
        downloadFuture,
        new FutureCallback<ClientFileGroup>() {
          @Override
          public void onSuccess(ClientFileGroup result) {}

          @Override
          public void onFailure(Throwable t) {
            // This is to ensure that onFailure is called.
            onFailureLatch.countDown();
          }
        },
        MoreExecutors.directExecutor());

    assertThrows(ExecutionException.class, downloadFuture::get);
    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e).hasMessageThat().contains("Fail");

    if (!onFailureLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException("latch timeout: onFailure is not called");
    }

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor)
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));

    // Sleep for 1 sec to wait for the listener.onFailure to finish.
    Thread.sleep(/*millis=*/ 1000);
    verify(mockDownloadMonitor).removeDownloadListener(eq(FILE_GROUP_NAME_1));

    assertThat(groupKeyCaptor.getValue().getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(groupKeyCaptor.getValue().hasAccount()).isFalse();
  }

  @Test
  public void downloadFileGroupWithForegroundService_withAccount() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            5 /* versionNumber */,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(false)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(true)))
        .thenReturn(Futures.immediateFuture(null));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    CountDownLatch onCompleteLatch = new CountDownLatch(1);

    Account account = AccountUtil.create("account-name", "account-type");
    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .downloadFileGroupWithForegroundService(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setAccountOptional(Optional.of(account))
                    .setListenerOptional(
                        Optional.of(
                            new DownloadListener() {
                              @Override
                              public void onProgress(long currentSize) {}

                              @Override
                              public void onComplete(ClientFileGroup clientFileGroup) {
                                assertThat(clientFileGroup.getGroupName())
                                    .isEqualTo(FILE_GROUP_NAME_1);
                                assertThat(clientFileGroup.getOwnerPackage())
                                    .isEqualTo(context.getPackageName());
                                assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
                                assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

                                // This is to verify that onComplete is called.
                                onCompleteLatch.countDown();
                              }
                            }))
                    .build())
            .get();

    // Verify that onComplete is called.
    if (!onCompleteLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException("onComplete is not called");
    }

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getAccount()).isEqualTo(AccountUtil.serialize(account));
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor)
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));
    verify(mockDownloadMonitor).removeDownloadListener(eq(FILE_GROUP_NAME_1));

    assertThat(groupKeyCaptor.getValue().getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(groupKeyCaptor.getValue().getAccount()).isEqualTo(AccountUtil.serialize(account));
  }

  @Test
  public void downloadFileGroupWithForegroundService_withVariantId() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
                FILE_GROUP_NAME_1,
                context.getPackageName(),
                /* versionNumber = */ 5,
                new String[] {FILE_ID_1},
                new int[] {FILE_SIZE_1},
                new String[] {FILE_CHECKSUM_1},
                new String[] {FILE_URL_1},
                DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI)
            .toBuilder()
            .setVariantId("en")
            .build();

    ArgumentCaptor<GroupKey> groupKeyCaptor = ArgumentCaptor.forClass(GroupKey.class);
    when(mockMobileDataDownloadManager.downloadFileGroup(groupKeyCaptor.capture(), any(), any()))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(false)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), eq(true)))
        .thenReturn(Futures.immediateFuture(null));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .downloadFileGroupWithForegroundService(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setVariantIdOptional(Optional.of("en"))
                    .build())
            .get();

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getVariantId()).isEqualTo("en");

    verify(mockMobileDataDownloadManager).downloadFileGroup(any(GroupKey.class), any(), any());

    GroupKey expectedGroupKey =
        GroupKey.newBuilder()
            .setGroupName(FILE_GROUP_NAME_1)
            .setOwnerPackage(context.getPackageName())
            .setVariantId("en")
            .build();
    assertThat(groupKeyCaptor.getValue()).isEqualTo(expectedGroupKey);
  }

  @Test
  public void downloadFileGroupWithForegroundService_whenAlreadyDownloaded() throws Exception {
    DataFileGroupInternal dataFileGroup =
        createDataFileGroupInternal(
            FILE_GROUP_NAME_1,
            context.getPackageName(),
            /* versionNumber = */ 5,
            new String[] {FILE_ID_1},
            new int[] {FILE_SIZE_1},
            new String[] {FILE_CHECKSUM_1},
            new String[] {FILE_URL_1},
            DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    // Mock situation: no pending group but there is a downloaded group
    when(mockMobileDataDownloadManager.getFileGroup(any(), eq(false)))
        .thenReturn(Futures.immediateFuture(null));
    when(mockMobileDataDownloadManager.getFileGroup(any(), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    CountDownLatch onCompleteLatch = new CountDownLatch(1);

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .downloadFileGroupWithForegroundService(
                DownloadFileGroupRequest.newBuilder()
                    .setGroupName(FILE_GROUP_NAME_1)
                    .setListenerOptional(
                        Optional.of(
                            new DownloadListener() {
                              @Override
                              public void onProgress(long currentSize) {}

                              @Override
                              public void onComplete(ClientFileGroup clientFileGroup) {
                                assertThat(clientFileGroup.getGroupName())
                                    .isEqualTo(FILE_GROUP_NAME_1);
                                assertThat(clientFileGroup.getOwnerPackage())
                                    .isEqualTo(context.getPackageName());
                                assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
                                assertThat(clientFileGroup.getFileCount()).isEqualTo(1);

                                // This is to verify that onComplete is called.
                                onCompleteLatch.countDown();
                              }
                            }))
                    .build())
            .get();

    // Verify that onComplete is called.
    if (!onCompleteLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      throw new RuntimeException("onComplete is not called");
    }

    assertThat(clientFileGroup.getGroupName()).isEqualTo(FILE_GROUP_NAME_1);
    assertThat(clientFileGroup.getOwnerPackage()).isEqualTo(context.getPackageName());
    assertThat(clientFileGroup.getVersionNumber()).isEqualTo(5);
    assertThat(clientFileGroup.getFileCount()).isEqualTo(1);
    assertThat(clientFileGroup.hasAccount()).isFalse();

    ClientFile clientFile = clientFileGroup.getFileList().get(0);
    assertThat(clientFile.getFileId()).isEqualTo(FILE_ID_1);
    assertThat(clientFile.getFileUri()).isEqualTo(onDeviceUri1.toString());

    verify(mockMobileDataDownloadManager, times(1)).getFileGroup(any(GroupKey.class), eq(true));
    verify(mockMobileDataDownloadManager, times(1)).getFileGroup(any(GroupKey.class), eq(false));
    verify(mockMobileDataDownloadManager, times(0))
        .downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor)
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));
    verify(mockDownloadMonitor).removeDownloadListener(eq(FILE_GROUP_NAME_1));
  }

  @Test
  public void downloadFileGroupWithForegroundService_whenNoVersionFound_fails() throws Exception {
    when(mockMobileDataDownloadManager.getFileGroup(groupKeyCaptor.capture(), anyBoolean()))
        .thenReturn(Futures.immediateFuture(null));

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            Optional.of(mockDownloadMonitor),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    CountDownLatch onFailureLatch = new CountDownLatch(1);

    ListenableFuture<ClientFileGroup> downloadFuture =
        mobileDataDownload.downloadFileGroupWithForegroundService(
            DownloadFileGroupRequest.newBuilder()
                .setGroupName(FILE_GROUP_NAME_1)
                .setListenerOptional(
                    Optional.of(
                        new DownloadListener() {
                          @Override
                          public void onProgress(long currentSize) {}

                          @Override
                          public void onComplete(ClientFileGroup clientFileGroup) {
                            fail("onComplete should not be called");
                          }

                          @Override
                          public void onFailure(Throwable t) {
                            assertThat(t).isInstanceOf(DownloadException.class);
                            assertThat(((DownloadException) t).getDownloadResultCode())
                                .isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);

                            // This is to verify onFailure is called.
                            onFailureLatch.countDown();
                          }
                        }))
                .build());

    assertThrows(ExecutionException.class, downloadFuture::get);

    // Verify onFailure is called
    if (!onFailureLatch.await(LATCH_WAIT_TIME_MS, TimeUnit.MILLISECONDS)) {
      fail("onFailure should be called");
    }

    DownloadException e = LabsFutures.getFailureCauseAs(downloadFuture, DownloadException.class);
    assertThat(e.getDownloadResultCode()).isEqualTo(DownloadResultCode.GROUP_NOT_FOUND_ERROR);

    // Verify did not attempt a download
    verify(mockMobileDataDownloadManager, times(0))
        .downloadFileGroup(any(GroupKey.class), any(), any());
    verify(mockDownloadMonitor, times(0))
        .addDownloadListener(eq(FILE_GROUP_NAME_1), any(DownloadListener.class));
  }

  @Test
  public void maintenance_success() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /*fileGroupPopulatorList=*/ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /*downloadMonitorOptional=*/ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    when(mockMobileDataDownloadManager.maintenance()).thenReturn(Futures.immediateFuture(null));

    mobileDataDownload.maintenance().get();

    verify(mockMobileDataDownloadManager).maintenance();
    verifyNoMoreInteractions(mockMobileDataDownloadManager);
  }

  @Test
  public void maintenance_failure() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /*fileGroupPopulatorList=*/ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /*downloadMonitorOptional=*/ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    when(mockMobileDataDownloadManager.maintenance())
        .thenReturn(Futures.immediateFailedFuture(new IOException("test-failure")));

    ExecutionException e =
        assertThrows(ExecutionException.class, () -> mobileDataDownload.maintenance().get());
    assertThat(e).hasCauseThat().isInstanceOf(IOException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("test-failure");

    verify(mockMobileDataDownloadManager).maintenance();
    verifyNoMoreInteractions(mockMobileDataDownloadManager);
  }

  @Test
  public void schedulePeriodicTasks() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    mobileDataDownload.schedulePeriodicTasks();

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.CHARGING_PERIODIC_TASK,
            (new Flags() {}).chargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_ANY,
            /* constraintOverrides = */ Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.MAINTENANCE_PERIODIC_TASK,
            (new Flags() {}).maintenanceGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_ANY,
            /* constraintOverrides = */ Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK,
            (new Flags() {}).cellularChargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_CONNECTED,
            /* constraintOverrides = */ Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.WIFI_CHARGING_PERIODIC_TASK,
            (new Flags() {}).wifiChargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_UNMETERED,
            /* constraintOverrides = */ Optional.absent());

    verifyNoMoreInteractions(mockTaskScheduler);
  }

  @Test
  public void schedulePeriodicTasks_nullTaskScheduler() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            /* taskSchedulerOptional = */ Optional.absent(),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    mobileDataDownload.schedulePeriodicTasks();

    verifyNoInteractions(mockTaskScheduler);
  }

  @Test
  public void schedulePeriodicBackgroundTasks() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    mobileDataDownload.schedulePeriodicBackgroundTasks().get();

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.CHARGING_PERIODIC_TASK,
            (new Flags() {}).chargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_ANY,
            /* constraintOverrides = */ Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.MAINTENANCE_PERIODIC_TASK,
            (new Flags() {}).maintenanceGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_ANY,
            /* constraintOverrides = */ Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK,
            (new Flags() {}).cellularChargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_CONNECTED,
            /* constraintOverrides = */ Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.WIFI_CHARGING_PERIODIC_TASK,
            (new Flags() {}).wifiChargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_UNMETERED,
            /* constraintOverrides = */ Optional.absent());

    verifyNoMoreInteractions(mockTaskScheduler);
  }

  @Test
  public void schedulePeriodicBackgroundTasks_nullTaskScheduler() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            /* taskSchedulerOptional = */ Optional.absent(),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    mobileDataDownload.schedulePeriodicBackgroundTasks().get();

    verifyNoInteractions(mockTaskScheduler);
  }

  @Test
  public void schedulePeriodicBackgroundTasks_withConstraintOverrides() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ConstraintOverrides wifiOverrides =
        ConstraintOverrides.newBuilder()
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(true)
            .build();
    ConstraintOverrides cellularOverrides =
        ConstraintOverrides.newBuilder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(false)
            .build();

    Map<String, ConstraintOverrides> constraintOverridesMap = new HashMap<>();
    constraintOverridesMap.put(TaskScheduler.WIFI_CHARGING_PERIODIC_TASK, wifiOverrides);
    constraintOverridesMap.put(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK, cellularOverrides);

    mobileDataDownload.schedulePeriodicBackgroundTasks(Optional.of(constraintOverridesMap)).get();

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.CHARGING_PERIODIC_TASK,
            (new Flags() {}).chargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_ANY,
            Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.MAINTENANCE_PERIODIC_TASK,
            (new Flags() {}).maintenanceGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_ANY,
            Optional.absent());

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK,
            (new Flags() {}).cellularChargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_CONNECTED,
            Optional.of(cellularOverrides));

    verify(mockTaskScheduler)
        .schedulePeriodicTask(
            TaskScheduler.WIFI_CHARGING_PERIODIC_TASK,
            (new Flags() {}).wifiChargingGcmTaskPeriod(),
            NetworkState.NETWORK_STATE_UNMETERED,
            Optional.of(wifiOverrides));

    verifyNoMoreInteractions(mockTaskScheduler);
  }

  @Test
  public void schedulePeriodicBackgroundTasks_nullTaskScheduler_andOverrides() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            /* taskSchedulerOptional = */ Optional.absent(),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    mobileDataDownload.schedulePeriodicBackgroundTasks(Optional.absent()).get();

    verifyNoInteractions(mockTaskScheduler);
  }

  // A helper function to create a DataFilegroup.
  private static DataFileGroup createDataFileGroup(
      String groupName,
      String ownerPackage,
      int versionNumber,
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
            .setFileGroupVersionNumber(versionNumber)
            .setDownloadConditions(
                DownloadConditions.newBuilder().setDeviceNetworkPolicy(deviceNetworkPolicy));

    for (int i = 0; i < fileId.length; ++i) {
      DataFile file =
          DataFile.newBuilder()
              .setFileId(fileId[i])
              .setByteSize(byteSize[i])
              .setChecksum(checksum[i])
              .setUrlToDownload(url[i])
              .build();
      dataFileGroupBuilder.addFile(file);
    }

    return dataFileGroupBuilder.build();
  }

  private static DataFileGroupInternal createDataFileGroupInternal(
      String groupName,
      String ownerPackage,
      int versionNumber,
      String[] fileId,
      int[] byteSize,
      String[] checksum,
      String[] url,
      DeviceNetworkPolicy deviceNetworkPolicy)
      throws Exception {
    return ProtoConversionUtil.convert(
        createDataFileGroup(
            groupName,
            ownerPackage,
            versionNumber,
            fileId,
            byteSize,
            checksum,
            url,
            deviceNetworkPolicy));
  }

  @Test
  public void handleTask_maintenance() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);
    when(mockMobileDataDownloadManager.maintenance()).thenReturn(Futures.immediateFuture(null));

    mobileDataDownload.handleTask(TaskScheduler.MAINTENANCE_PERIODIC_TASK).get();
    verify(mockMobileDataDownloadManager).maintenance();
    verifyNoMoreInteractions(mockMobileDataDownloadManager);
  }

  @Test
  public void handleTask_charging() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of(mockFileGroupPopulator),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    when(mockMobileDataDownloadManager.verifyAllPendingGroups(any()))
        .thenReturn(Futures.immediateFuture(null));
    when(mockFileGroupPopulator.refreshFileGroups(mobileDataDownload))
        .thenReturn(Futures.immediateFuture(null));

    mobileDataDownload.handleTask(TaskScheduler.CHARGING_PERIODIC_TASK).get();
    verify(mockFileGroupPopulator).refreshFileGroups(mobileDataDownload);
    verify(mockMobileDataDownloadManager).verifyAllPendingGroups(any());
    verifyNoMoreInteractions(mockMobileDataDownloadManager);
  }

  @Test
  public void handleTask_wifi_charging() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of(mockFileGroupPopulator),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    when(mockFileGroupPopulator.refreshFileGroups(mobileDataDownload))
        .thenReturn(Futures.immediateFuture(null));
    when(mockMobileDataDownloadManager.downloadAllPendingGroups(eq(true) /*wifi*/, any()))
        .thenReturn(Futures.immediateFuture(null));

    mobileDataDownload.handleTask(TaskScheduler.WIFI_CHARGING_PERIODIC_TASK).get();
    verify(mockFileGroupPopulator, times(2)).refreshFileGroups(mobileDataDownload);
    verify(mockMobileDataDownloadManager, times(2)).downloadAllPendingGroups(eq(true), any());
    verifyNoMoreInteractions(mockMobileDataDownloadManager);
  }

  @Test
  public void handleTask_cellular_charging() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of(mockFileGroupPopulator),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    when(mockFileGroupPopulator.refreshFileGroups(mobileDataDownload))
        .thenReturn(Futures.immediateFuture(null));
    when(mockMobileDataDownloadManager.downloadAllPendingGroups(eq(false) /*wifi*/, any()))
        .thenReturn(Futures.immediateFuture(null));

    mobileDataDownload.handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK).get();
    verify(mockFileGroupPopulator, times(2)).refreshFileGroups(mobileDataDownload);
    verify(mockMobileDataDownloadManager, times(2)).downloadAllPendingGroups(eq(false), any());
    verifyNoMoreInteractions(mockMobileDataDownloadManager);
  }

  @Test
  public void handleTask_no_filegroup_populator() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            /* fileGroupPopulatorList = */ ImmutableList.of(),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    when(mockMobileDataDownloadManager.verifyAllPendingGroups(any()))
        .thenReturn(Futures.immediateFuture(null));
    when(mockMobileDataDownloadManager.downloadAllPendingGroups(anyBoolean() /*wifi*/, any()))
        .thenReturn(Futures.immediateFuture(null));

    mobileDataDownload.handleTask(TaskScheduler.CHARGING_PERIODIC_TASK).get();
    verify(mockMobileDataDownloadManager).verifyAllPendingGroups(any());

    mobileDataDownload.handleTask(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK).get();
    verify(mockMobileDataDownloadManager, times(2)).downloadAllPendingGroups(eq(false), any());

    mobileDataDownload.handleTask(TaskScheduler.WIFI_CHARGING_PERIODIC_TASK).get();
    verify(mockMobileDataDownloadManager, times(2)).downloadAllPendingGroups(eq(true), any());
  }

  @Test
  public void handleTask_invalid_tag() throws Exception {
    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            ImmutableList.of(mockFileGroupPopulator),
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    ExecutionException e =
        assertThrows(
            ExecutionException.class, () -> mobileDataDownload.handleTask("invalid-tag").get());
    assertThat(e).hasCauseThat().isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  public void handleTask_onePopulatorFails_continuesToRunOthers() throws Exception {
    FileGroupPopulator failingPopulator =
        (MobileDataDownload unused) ->
            Futures.immediateFailedFuture(new IllegalArgumentException("test"));

    AtomicBoolean refreshedOneSucceedingPopulator = new AtomicBoolean(false);
    FileGroupPopulator oneSucceedingPopulator =
        (MobileDataDownload unused) -> {
          refreshedOneSucceedingPopulator.set(true);
          return Futures.immediateVoidFuture();
        };

    AtomicBoolean refreshedAnotherSucceedingPopulator = new AtomicBoolean(false);
    FileGroupPopulator anotherSucceedingPopulator =
        (MobileDataDownload unused) -> {
          refreshedAnotherSucceedingPopulator.set(true);
          return Futures.immediateVoidFuture();
        };

    // The populators will be executed in this order.
    ImmutableList<FileGroupPopulator> populators =
        ImmutableList.of(failingPopulator, oneSucceedingPopulator, anotherSucceedingPopulator);

    MobileDataDownload mobileDataDownload =
        new MobileDataDownloadImpl(
            context,
            mockEventLogger,
            mockMobileDataDownloadManager,
            EXECUTOR,
            populators,
            Optional.of(mockTaskScheduler),
            fileStorage,
            /* downloadMonitorOptional = */ Optional.absent(),
            Optional.of(this.getClass()), // don't need to use the real foreground download service.
            flags,
            singleFileDownloader,
            Optional.absent() /* customFileGroupValidator */);

    when(mockMobileDataDownloadManager.verifyAllPendingGroups(any() /* validator */))
        .thenReturn(Futures.immediateVoidFuture());
    when(mockMobileDataDownloadManager.downloadAllPendingGroups(
            anyBoolean() /*wifi*/, any() /* validator */))
        .thenReturn(Futures.immediateVoidFuture());

    ListenableFuture<Void> handleFuture =
        mobileDataDownload.handleTask(TaskScheduler.WIFI_CHARGING_PERIODIC_TASK);
    assertThat(handleFuture).whenDone().isSuccessful();

    handleFuture.get();
    assertThat(refreshedOneSucceedingPopulator.get()).isTrue();
    assertThat(refreshedAnotherSucceedingPopulator.get()).isTrue();
  }

  @Test
  public void reportUsage_basic() throws Exception {
    DataFileGroupInternal dataFileGroup = createDefaultDataFileGroupInternal();

    when(mockMobileDataDownloadManager.getFileGroup(any(GroupKey.class), eq(true)))
        .thenReturn(Futures.immediateFuture(dataFileGroup));
    when(mockMobileDataDownloadManager.getDataFileUri(dataFileGroup.getFile(0), dataFileGroup))
        .thenReturn(Futures.immediateFuture(onDeviceUri1));

    MobileDataDownload mobileDataDownload = createDefaultMobileDataDownload();

    ClientFileGroup clientFileGroup =
        mobileDataDownload
            .getFileGroup(GetFileGroupRequest.newBuilder().setGroupName(FILE_GROUP_NAME_1).build())
            .get();

    UsageEvent usageEvent =
        UsageEvent.builder()
            .setEventCode(0)
            .setAppVersion(123)
            .setClientFileGroup(clientFileGroup)
            .build();
    mobileDataDownload.reportUsage(usageEvent).get();

    verify(mockEventLogger).logMddUsageEvent(createFileGroupStats(clientFileGroup), null);
  }

  private static Void createFileGroupStats(ClientFileGroup clientFileGroup) {
    return null;
  }

  private MobileDataDownload createDefaultMobileDataDownload() {
    return new MobileDataDownloadImpl(
        context,
        mockEventLogger,
        mockMobileDataDownloadManager,
        EXECUTOR,
        ImmutableList.of() /* fileGroupPopulatorList */,
        Optional.of(mockTaskScheduler),
        fileStorage,
        Optional.absent() /* downloadMonitorOptional */,
        Optional.of(this.getClass()), // don't need to use the real foreground download service.
        flags,
        singleFileDownloader,
        Optional.absent() /* customFileGroupValidator */);
  }

  private DataFileGroupInternal createDefaultDataFileGroupInternal() throws Exception {
    return createDataFileGroupInternal(
        FILE_GROUP_NAME_1,
        context.getPackageName(),
        1 /* versionNumber */,
        new String[] {FILE_ID_1},
        new int[] {FILE_SIZE_1},
        new String[] {FILE_CHECKSUM_1},
        new String[] {FILE_URL_1},
        DeviceNetworkPolicy.DOWNLOAD_ONLY_ON_WIFI);
  }
}
