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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import static org.junit.Assert.assertThrows;

import android.net.Uri;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.GcParam;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.collect.ImmutableList;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class FakeFileBackendTest extends BackendTestBase {
  private final FakeFileBackend backend = new FakeFileBackend();

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Override
  protected Backend backend() {
    return backend;
  }

  @Override
  protected Uri legalUriBase() throws IOException {
    return tmpUri.newDirectoryUri();
  }

  @Override
  protected List<Uri> illegalUrisToRead() {
    return ImmutableList.of();
  }

  @Override
  protected List<Uri> illegalUrisToWrite() {
    return ImmutableList.of();
  }

  @Override
  protected List<Uri> illegalUrisToAppend() {
    return ImmutableList.of();
  }

  @Test
  public void throwsExceptions_forRead() throws Exception {
    Uri uri = tmpUri.newUri();
    backend.setFailure(FakeFileBackend.OperationType.READ, new FakeIOException("test"));
    assertThrows(FakeIOException.class, () -> backend.openForRead(uri));
    assertThrows(FakeIOException.class, () -> backend.openForNativeRead(uri));

    backend.clearFailure(FakeFileBackend.OperationType.READ);
    try (Closeable resource = backend.openForRead(uri)) {}
    Pair<Uri, Closeable> nativeOpen = backend.openForNativeRead(uri);
    try (Closeable resource = nativeOpen.second) {}
  }

  @Test
  public void throwsExceptions_forWrite() throws Exception {
    Uri uri = tmpUri.newUri();
    backend.setFailure(FakeFileBackend.OperationType.WRITE, new FakeIOException("test"));
    assertThrows(FakeIOException.class, () -> backend.openForWrite(uri));
    assertThrows(FakeIOException.class, () -> backend.openForAppend(uri));

    backend.clearFailure(FakeFileBackend.OperationType.WRITE);
    try (Closeable resource = backend.openForWrite(uri)) {}
    try (Closeable resource = backend.openForAppend(uri)) {}
  }

  @Test
  public void throwsExceptions_forQuery() throws Exception {
    Uri uri = tmpUri.newUri();
    Uri dir = tmpUri.newDirectoryUri();

    backend.setFailure(FakeFileBackend.OperationType.QUERY, new FakeIOException("test"));
    assertThrows(FakeIOException.class, () -> backend.exists(uri));
    assertThrows(FakeIOException.class, () -> backend.isDirectory(uri));
    assertThrows(FakeIOException.class, () -> backend.fileSize(uri));
    assertThrows(FakeIOException.class, () -> backend.children(dir));
    assertThrows(FakeIOException.class, () -> backend.getGcParam(uri));
    assertThrows(FakeIOException.class, () -> backend.toFile(uri));

    backend.clearFailure(FakeFileBackend.OperationType.QUERY);
    backend.exists(uri);
    backend.isDirectory(uri);
    backend.fileSize(uri);
    backend.children(dir);
    assertThrows(UnsupportedFileStorageOperation.class, () -> backend.getGcParam(uri));
    backend.toFile(uri);
  }

  @Test
  public void throwsExceptions_forManage() throws Exception {
    Uri uri = tmpUri.newUri();
    Uri uri2 = tmpUri.newUri();
    Uri dir = tmpUri.newDirectoryUri();

    backend.setFailure(FakeFileBackend.OperationType.MANAGE, new FakeIOException("test"));
    assertThrows(FakeIOException.class, () -> backend.deleteFile(uri));
    assertThrows(FakeIOException.class, () -> backend.deleteDirectory(dir));
    assertThrows(FakeIOException.class, () -> backend.rename(uri, uri2));
    assertThrows(FakeIOException.class, () -> backend.createDirectory(dir));
    assertThrows(FakeIOException.class, () -> backend.setGcParam(uri, GcParam.none()));

    backend.clearFailure(FakeFileBackend.OperationType.MANAGE);
    assertThrows(
        UnsupportedFileStorageOperation.class, () -> backend.setGcParam(uri, GcParam.none()));
    backend.rename(uri, uri2);
    backend.deleteFile(uri2);
    backend.deleteDirectory(dir);
    backend.createDirectory(dir);
  }

  @Test
  public void throwsExceptions_forAll() throws Exception {
    Uri uri = tmpUri.newUri();

    backend.setFailure(FakeFileBackend.OperationType.ALL, new FakeIOException("test"));
    assertThrows(FakeIOException.class, () -> backend.openForRead(uri)); // READ
    assertThrows(FakeIOException.class, () -> backend.openForWrite(uri)); // WRITE
    assertThrows(FakeIOException.class, () -> backend.exists(uri)); // QUERY
    assertThrows(FakeIOException.class, () -> backend.deleteFile(uri)); // MANAGE

    backend.clearFailure(FakeFileBackend.OperationType.ALL);
    try (Closeable resource = backend.openForRead(uri)) {}
    try (Closeable resource = backend.openForWrite(uri)) {}
    backend.exists(uri);
    backend.deleteFile(uri);
  }

  private static class FakeIOException extends IOException {
    FakeIOException(String message) {
      super(message);
    }
  }
}
