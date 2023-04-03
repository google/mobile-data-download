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
package com.google.android.libraries.mobiledatadownload.file.backends;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFileInBytes;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.FileStorageUnavailableException;
import com.google.android.libraries.mobiledatadownload.file.common.LockScope;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.testing.BackendTestBase;
import com.google.android.libraries.mobiledatadownload.file.openers.NativeReadOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Tests for {@link AndroidFileBackend} */
@RunWith(RobolectricTestRunner.class)
@Config(
    shadows = {},
    sdk = Build.VERSION_CODES.N)
public class AndroidFileBackendTest extends BackendTestBase {

  private final Context context = ApplicationProvider.getApplicationContext();
  private final Backend backend = AndroidFileBackend.builder(context).build();
  private static final byte[] TEST_CONTENT = makeArrayOfBytesContent();

  @Override
  protected Backend backend() {
    return backend;
  }

  @Override
  protected Uri legalUriBase() {
    return Uri.parse("android://" + context.getPackageName() + "/files/common/shared/");
  }

  @Override
  protected List<Uri> illegalUrisToRead() {
    return ImmutableList.of(
        Uri.parse(legalUriBase() + "uriWithQuery?q=a"),
        Uri.parse("android:///null/uriWithInvalidLogicalLocation"),
        Uri.parse("android://" + context.getPackageName()),
        Uri.parse("android://" + context.getPackageName()));
  }

  @Override
  protected List<Uri> illegalUrisToWrite() {
    return ImmutableList.of(
        Uri.parse("android://com.thirdparty.app/files/common/shared/uriAcrossAuthority"));
  }

  @Override
  protected List<Uri> illegalUrisToAppend() {
    return illegalUrisToWrite();
  }

  /** Minimal tests verifying default builder behavior */
  @Test
  public void builder_withNullContext_shouldThrowException() {
    assertThrows(IllegalArgumentException.class, () -> AndroidFileBackend.builder(null));
  }

  @Test
  public void builder_remoteBackend_isNullByDefault() {
    Uri uri = Uri.parse("android://com.thirdparty.app/files/common/shared/file");
    AndroidFileBackend backend = AndroidFileBackend.builder(context).build();
    assertThrows(FileStorageUnavailableException.class, () -> backend.openForRead(uri));
  }

  @Test
  public void builder_accountManager_isNullByDefault() {
    Account account = new Account("<internal>@gmail.com", "google.com");
    Uri uri = AndroidUri.builder(context).setManagedLocation().setAccount(account).build();
    AndroidFileBackend backend = AndroidFileBackend.builder(context).build();
    assertThrows(MalformedUriException.class, () -> backend.openForRead(uri));
  }

  /** Tests verifying backend behavior */
  @Test
  public void openForWrite_shouldUseContextAuthorityIfWithoutAuthority() throws Exception {
    final Uri uri = Uri.parse("android:///files/writing/shared/missingAuthority");

    assertThat(storage().exists(uri)).isFalse();

    createFile(storage(), uri, TEST_CONTENT);

    assertThat(storage().exists(uri)).isTrue();
    assertThat(readFileInBytes(storage(), uri)).isEqualTo(TEST_CONTENT);
  }

  @Test
  public void rename_shouldNotRenameAcrossAuthority() throws Exception {
    final Uri from =
        Uri.parse("android://" + context.getPackageName() + "/files/localfrom/shared/file");
    final Uri to = Uri.parse("android://com.thirdparty.app/files/remoteto/shared/file");

    createFile(storage(), from, TEST_CONTENT);

    assertThat(storage().exists(from)).isTrue();
    assertThrows(MalformedUriException.class, () -> storage().rename(from, to));
    assertThat(storage().exists(from)).isTrue();
    assertThat(readFileInBytes(storage(), from)).isEqualTo(TEST_CONTENT);
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.N)
  public void openForRead_directBootFilesOnNShouldUseDeviceProtectedStorageContext()
      throws Exception {
    Uri uri =
        AndroidUri.builder(context)
            .setDirectBootFilesLocation()
            .setModule("testboot")
            .setRelativePath("inDirectBoot.txt")
            .build();
    File directBootFile =
        new File(
            context.createDeviceProtectedStorageContext().getFilesDir(),
            "testboot/shared/inDirectBoot.txt");
    File filesFile = new File(context.getFilesDir(), "testboot/shared/inDirectBoot.txt");

    createFile(storage(), uri, TEST_CONTENT);

    assertThat(filesFile.exists()).isFalse();
    assertThat(directBootFile.exists()).isTrue();
    assertThat(readFileInBytes(storage(), uri)).isEqualTo(TEST_CONTENT);
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.N)
  public void openForRead_directBootCacheOnNShouldUseDeviceProtectedStorageContext()
      throws Exception {
    Uri uri =
        AndroidUri.builder(context)
            .setDirectBootCacheLocation()
            .setModule("testboot")
            .setRelativePath("inDirectBoot.txt")
            .build();
    File directBootFile =
        new File(
            context.createDeviceProtectedStorageContext().getCacheDir(),
            "testboot/shared/inDirectBoot.txt");
    File cacheFile = new File(context.getCacheDir(), "testboot/shared/inDirectBoot.txt");

    createFile(storage(), uri, TEST_CONTENT);

    assertThat(cacheFile.exists()).isFalse();
    assertThat(directBootFile.exists()).isTrue();
    assertThat(readFileInBytes(storage(), uri)).isEqualTo(TEST_CONTENT);
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.M)
  public void openForRead_directBootFilesBeforeNShouldThrowException() throws Exception {
    Uri uri =
        AndroidUri.builder(context)
            .setDirectBootFilesLocation()
            .setModule("testboot")
            .setRelativePath("inDirectBoot.txt")
            .build();

    assertThrows(MalformedUriException.class, () -> storage().open(uri, ReadStreamOpener.create()));
  }

  @Test
  @Config(sdk = Build.VERSION_CODES.M)
  public void openForRead_directBootCacheBeforeNShouldThrowException() throws Exception {
    Uri uri =
        AndroidUri.builder(context)
            .setDirectBootCacheLocation()
            .setModule("testboot")
            .setRelativePath("inDirectBoot.txt")
            .build();

    assertThrows(MalformedUriException.class, () -> storage().open(uri, ReadStreamOpener.create()));
  }

  @Test
  public void openForRead_remoteAuthorityShouldUseRemoteBackend()
      throws IOException, ExecutionException, InterruptedException {
    Backend remoteBackend = mock(Backend.class);
    when(remoteBackend.openForRead(any(Uri.class)))
        .thenReturn(new ByteArrayInputStream(new byte[0]));
    SynchronousFileStorage remoteStorage =
        new SynchronousFileStorage(
            ImmutableList.of(
                AndroidFileBackend.builder(context).setRemoteBackend(remoteBackend).build()));
    Uri uri = Uri.parse("android://com.thirdparty.app/files/reading/file");

    Closeable unused = remoteStorage.open(uri, ReadStreamOpener.create());

    verify(remoteBackend).openForRead(uri);
  }

  @Test
  public void openForNativeRead_remoteAuthorityShouldUseRemoteBackend()
      throws IOException, ExecutionException, InterruptedException {
    Backend remoteBackend = mock(Backend.class);
    when(remoteBackend.openForNativeRead(any(Uri.class)))
        .thenReturn(Pair.create(Uri.parse("fd:123"), (Closeable) null));
    SynchronousFileStorage remoteStorage =
        new SynchronousFileStorage(
            ImmutableList.of(
                AndroidFileBackend.builder(context).setRemoteBackend(remoteBackend).build()));
    Uri uri = Uri.parse("android://com.thirdparty.app/files/reading/file");

    Closeable unused = remoteStorage.open(uri, NativeReadOpener.create());

    verify(remoteBackend).openForNativeRead(uri);
  }

  @Test
  public void exists_remoteAuthorityShouldUseRemoteBackend()
      throws IOException, ExecutionException, InterruptedException {
    Backend remoteBackend = mock(Backend.class);
    when(remoteBackend.exists(any(Uri.class))).thenReturn(true);
    SynchronousFileStorage remoteStorage =
        new SynchronousFileStorage(
            ImmutableList.of(
                AndroidFileBackend.builder(context).setRemoteBackend(remoteBackend).build()));
    Uri uri = Uri.parse("android://com.thirdparty.app/files/reading/file");

    assertThat(remoteStorage.exists(uri)).isTrue();

    verify(remoteBackend).exists(uri);
  }

  @Test
  public void managedUris_isSerializedAsIntegerOnDisk() throws Exception {
    Account account = new Account("<internal>@gmail.com", "google.com");
    AccountManager mockManager = mock(AccountManager.class);
    when(mockManager.getAccountId(account)).thenReturn(Futures.immediateFuture(123));

    AndroidFileBackend backend =
        AndroidFileBackend.builder(context).setAccountManager(mockManager).build();
    SynchronousFileStorage storage = new SynchronousFileStorage(ImmutableList.of(backend));

    Uri uri =
        Uri.parse(
            "android://"
                + context.getPackageName()
                + "/managed/common/google.com%3Ayou%40gmail.com/file");
    createFile(storage, uri, TEST_CONTENT);
    assertThat(storage.exists(uri)).isTrue();

    File file = new File(context.getFilesDir(), "managed/common/123/file");
    assertThat(file.exists()).isTrue();
  }

  @Test
  public void managedLocation_worksWithChildren() throws Exception {
    Account account = new Account("<internal>@gmail.com", "google.com");
    AccountManager mockManager = mock(AccountManager.class);
    when(mockManager.getAccount(123)).thenReturn(Futures.immediateFuture(account));
    when(mockManager.getAccountId(account)).thenReturn(Futures.immediateFuture(123));

    AndroidFileBackend backend =
        AndroidFileBackend.builder(context).setAccountManager(mockManager).build();

    Uri dirUri =
        Uri.parse(
            "android://"
                + context.getPackageName()
                + "/managed/common/google.com%3Ayou%40gmail.com/dir");
    Uri fileUri0 = Uri.withAppendedPath(dirUri, "file-0");
    Uri fileUri1 = Uri.withAppendedPath(dirUri, "file-1");
    backend.createDirectory(dirUri);
    backend.openForWrite(fileUri0).close();
    backend.openForWrite(fileUri1).close();

    assertThat(backend.children(dirUri)).containsExactly(fileUri0, fileUri1);
  }

  @Test
  public void managedUris_worksWithToFile() throws Exception {
    Account account = new Account("<internal>@gmail.com", "google.com");
    AccountManager mockManager = mock(AccountManager.class);
    when(mockManager.getAccountId(account)).thenReturn(Futures.immediateFuture(123));

    AndroidFileBackend backend =
        AndroidFileBackend.builder(context).setAccountManager(mockManager).build();

    Uri uri =
        Uri.parse(
            "android://"
                + context.getPackageName()
                + "/managed/common/google.com%3Ayou%40gmail.com/file");
    File file = backend.toFile(uri);
    assertThat(file.getPath()).endsWith("/files/managed/common/123/file");
  }

  @Test
  public void lockScope_returnsNonNullLockScope() throws IOException {
    assertThat(backend.lockScope()).isNotNull();
  }

  @Test
  public void lockScope_canBeOverridden() throws IOException {
    LockScope lockScope = new LockScope();
    AndroidFileBackend backend =
        AndroidFileBackend.builder(context).setLockScope(lockScope).build();
    assertThat(backend.lockScope()).isSameInstanceAs(lockScope);
  }
}
