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
package com.google.android.libraries.mobiledatadownload.file;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.FragmentParamMatchers.eqParam;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.GcParam;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.common.testing.BufferingMonitor;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FileStorageTestBase;
import com.google.android.libraries.mobiledatadownload.file.common.testing.NoOpMonitor;
import com.google.android.libraries.mobiledatadownload.file.openers.AppendStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.ForwardingBackend;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.android.libraries.mobiledatadownload.file.transforms.BufferTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;

/**
 * Test {@link com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage}. These
 * tests use mocks and basically just ensure that things are being called in the expected order.
 */
@RunWith(RobolectricTestRunner.class)
public class SynchronousFileStorageTest extends FileStorageTestBase {

  private SynchronousFileStorage storage;
  private final Context context = ApplicationProvider.getApplicationContext();

  @Override
  protected void initStorage() {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(fileBackend, cnsBackend),
            ImmutableList.of(compressTransform, encryptTransform, identityTransform),
            ImmutableList.of(countingMonitor));
  }

  // Backend registrar

  @Test
  public void registeredBackends_shouldNotThrowException() throws Exception {
    assertThat(storage.exists(file1Uri)).isFalse();
  }

  @Test
  public void unregisteredBackends_shouldThrowException() throws Exception {
    Uri unregisteredUri = Uri.parse("unregistered:///");
    assertThrows(UnsupportedFileStorageOperation.class, () -> storage.exists(unregisteredUri));
  }

  @Test
  public void nullUriScheme_shouldThrowException() throws Exception {
    Uri relativeUri = Uri.parse("/relative/uri");
    assertThrows(UnsupportedFileStorageOperation.class, () -> storage.exists(relativeUri));
  }

  @Test
  public void emptyBackendName_shouldBeSilentlySkipped() throws Exception {
    Backend emptyNameBackend =
        new ForwardingBackend() {
          @Override
          protected Backend delegate() {
            return fileBackend;
          }

          @Override
          public String name() {
            return "";
          }
        };
    new SynchronousFileStorage(ImmutableList.of(emptyNameBackend));
  }

  @Test
  public void doubleRegisteringBackendName_shouldThrowException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SynchronousFileStorage(
                ImmutableList.of(new JavaFileBackend(), new JavaFileBackend())));
  }

  // Backend operations

  @Test
  public void deleteFile_shouldInvokeBackend() throws Exception {
    storage.deleteFile(file1Uri);
    verify(fileBackend).deleteFile(file1Uri);
  }

  @Test
  public void deleteDir_shouldInvokeBackend() throws Exception {
    storage.deleteDirectory(file1Uri);
    verify(fileBackend).deleteDirectory(file1Uri);
  }

  @Test
  public void deleteRecursively_shouldRecurse() throws Exception {
    Uri dir2Uri = dir1Uri.buildUpon().appendPath("dir2").build();
    when(fileBackend.exists(dir1Uri)).thenReturn(true);
    when(fileBackend.isDirectory(dir1Uri)).thenReturn(true);
    when(fileBackend.exists(dir2Uri)).thenReturn(true);
    when(fileBackend.isDirectory(dir2Uri)).thenReturn(true);
    when(fileBackend.exists(file1Uri)).thenReturn(true);
    when(fileBackend.exists(file2Uri)).thenReturn(true);
    when(fileBackend.children(dir1Uri)).thenReturn(ImmutableList.of(file1Uri, file2Uri, dir2Uri));
    when(fileBackend.children(dir2Uri)).thenReturn(Collections.emptyList());

    assertThat(storage.deleteRecursively(dir1Uri)).isTrue();

    verify(fileBackend).deleteFile(file1Uri);
    verify(fileBackend).deleteFile(file2Uri);
    verify(fileBackend).deleteDirectory(dir2Uri);
    verify(fileBackend).deleteDirectory(dir1Uri);
  }

  @Test
  public void deleteRecursively_failsOnAccessError() throws Exception {
    when(fileBackend.exists(dir1Uri)).thenReturn(true);
    when(fileBackend.isDirectory(dir1Uri)).thenReturn(true);
    when(fileBackend.exists(file1Uri)).thenReturn(true);
    when(fileBackend.exists(file2Uri)).thenReturn(true);
    when(fileBackend.children(dir1Uri)).thenReturn(ImmutableList.of(file1Uri, file2Uri));
    doThrow(IOException.class).when(fileBackend).deleteFile(file2Uri);

    assertThrows(IOException.class, () -> storage.deleteRecursively(dir1Uri));

    verify(fileBackend).deleteFile(file1Uri);
    verify(fileBackend).deleteFile(file2Uri);
    verify(fileBackend, never()).deleteDirectory(dir1Uri);
  }

  @Test
  public void deleteRecursively_fileDeletes() throws Exception {
    when(fileBackend.exists(file1Uri)).thenReturn(true);
    when(fileBackend.isDirectory(file1Uri)).thenReturn(false);

    assertThat(storage.deleteRecursively(file1Uri)).isTrue();

    verify(fileBackend).exists(file1Uri);
    verify(fileBackend).deleteFile(file1Uri);
  }

  @Test
  public void deleteRecursively_fileNotExist() throws Exception {
    when(fileBackend.exists(dir1Uri)).thenReturn(false);

    assertThat(storage.deleteRecursively(dir1Uri)).isFalse();

    verify(fileBackend).exists(dir1Uri);
  }

  @Test
  public void rename_shouldInvokeBackend() throws Exception {
    storage.rename(file1Uri, file2Uri);
    verify(fileBackend).rename(file1Uri, file2Uri);
  }

  @Test
  public void rename_crossingBackendsShouldThrowException() throws Exception {
    assertThrows(UnsupportedFileStorageOperation.class, () -> storage.rename(file1Uri, cnsUri));
  }

  @Test
  public void exists_shouldInvokeBackend() throws Exception {
    assertThat(storage.exists(file1Uri)).isFalse();
    verify(fileBackend).exists(file1Uri);
  }

  @Test
  public void isDirectory_shouldInvokeBackend() throws Exception {
    assertThat(storage.isDirectory(file1Uri)).isFalse();
    verify(fileBackend).isDirectory(file1Uri);
  }

  @Test
  public void createDirectoryshouldInvokeBackend() throws Exception {
    storage.createDirectory(file1Uri);
    verify(fileBackend).createDirectory(file1Uri);
  }

  @Test
  public void fileSize_shouldInvokeBackend() throws Exception {
    assertThat(storage.fileSize(file1Uri)).isEqualTo(0L);
    verify(fileBackend).fileSize(file1Uri);
  }

  //
  // Transform stuff
  //

  @Test
  public void registeredTransforms_shouldNotThrowException() throws Exception {
    assertThat(storage.exists(file1CompressUri)).isFalse();
    verify(fileBackend).exists(file1Uri);
  }

  @Test
  public void unregisteredTransforms_shouldThrowException() throws Exception {
    Uri unregisteredUri = Uri.parse(file1Uri + "#transform=unregistered");
    assertThrows(UnsupportedFileStorageOperation.class, () -> storage.exists(unregisteredUri));
  }

  @Test
  public void getDebugInfo_shouldIncludeRegisteredPlugins() throws Exception {
    SynchronousFileStorage debugStorage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend(), AndroidFileBackend.builder(context).build()),
            ImmutableList.of(new CompressTransform(), new BufferTransform()),
            ImmutableList.of(new BufferingMonitor(), new NoOpMonitor()));
    String debugString = debugStorage.getDebugInfo();

    assertThat(debugString)
        .isEqualTo(
            "Registered Mobstore Plugins:\n"
                + "\n"
                + "Backends:\n"
                + "protocol: android, class: AndroidFileBackend,\n"
                + "protocol: file, class: JavaFileBackend\n"
                + "\n"
                + "Transforms:\n"
                + "BufferTransform,\n"
                + "CompressTransform\n"
                + "\n"
                + "Monitors:\n"
                + "BufferingMonitor,\n"
                + "NoOpMonitor");
  }

  @Test
  public void emptyTransformName_shouldBeSilentlySkipped() throws Exception {
    Transform emptyNameTransform =
        new Transform() {
          @Override
          public String name() {
            return "";
          }
        };
    new SynchronousFileStorage(ImmutableList.of(), ImmutableList.of(emptyNameTransform));
  }

  @Test
  public void doubleRegisteringTransformName_shouldThrowException() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SynchronousFileStorage(
                ImmutableList.of(),
                ImmutableList.of(new CompressTransform(), new CompressTransform())));
  }

  @Test
  public void read_shouldInvokeTransforms() throws Exception {
    when(compressTransform.wrapForRead(eqParam(uriWithCompressParam), any(InputStream.class)))
        .thenReturn(compressInputStream);
    try (InputStream in = storage.open(file1CompressUri, ReadStreamOpener.create())) {
      verify(compressTransform).wrapForRead(eqParam(uriWithCompressParam), any(InputStream.class));
      verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file1Filename));
    }
  }

  @Test
  public void read_shouldInvokeTransformsWithEncoded() throws Exception {
    when(compressTransform.wrapForRead(
            eqParam(uriWithCompressParamWithEncoded), any(InputStream.class)))
        .thenReturn(compressInputStream);
    try (InputStream in = storage.open(file1CompressUriWithEncoded, ReadStreamOpener.create())) {
      verify(compressTransform)
          .wrapForRead(eqParam(uriWithCompressParamWithEncoded), any(InputStream.class));
      verify(compressTransform).encode(eqParam(uriWithCompressParamWithEncoded), eq(file1Filename));
    }
  }

  @Test
  public void write_shouldInvokeTransforms() throws Exception {
    when(compressTransform.wrapForWrite(eqParam(uriWithCompressParam), any(OutputStream.class)))
        .thenReturn(compressOutputStream);
    try (OutputStream out = storage.open(file1CompressUri, WriteStreamOpener.create())) {
      verify(compressTransform)
          .wrapForWrite(eqParam(uriWithCompressParam), any(OutputStream.class));
      verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file1Filename));
    }
  }

  @Test
  public void deleteFile_shouldInvokeTransformEncode() throws Exception {
    storage.deleteFile(file1CompressUri);
    verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file1Filename));
  }

  @Test
  public void deleteDirectory_shouldNOTInvokeTransformEncode() throws Exception {
    storage.deleteDirectory(file1CompressUri);
    verify(compressTransform, never()).encode(eqParam(uriWithCompressParam), any());
  }

  @Test
  public void rename_shouldInvokeTransformEncode() throws Exception {
    storage.rename(file1CompressUri, file2CompressEncryptUri);
    verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file1Filename));
    verify(encryptTransform).encode(eqParam(uriWithEncryptParam), eq(file2Filename));
    verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file2Filename));
  }

  @Test
  public void rename_shouldNOTInvokeTransformEncodeOnDirectory() throws Exception {
    storage.rename(dir1Uri, dir2CompressUri);
    verify(compressTransform, never()).encode(eqParam(uriWithCompressParam), any());
  }

  @Test
  public void exists_shouldInvokeTransformEncode() throws Exception {
    assertThat(storage.exists(file1CompressUri)).isFalse();
    verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file1Filename));
  }

  @Test
  public void exists_shouldNOTInvokeTransformEncodeOnDirectory() throws Exception {
    assertThat(storage.exists(dir2CompressUri)).isFalse();
    verify(compressTransform, never()).encode(eqParam(uriWithCompressParam), any());
  }

  @Test
  public void isDirectory_shouldNOTInvokeTransformEncode() throws Exception {
    assertThat(storage.isDirectory(file1CompressUri)).isFalse();
    verify(compressTransform, never()).encode(eqParam(uriWithCompressParam), any());
  }

  @Test
  public void createDirectoryshouldNOTInvokeTransformEncode() throws Exception {
    storage.createDirectory(file1CompressUri);
    verify(compressTransform, never()).encode(eqParam(uriWithCompressParam), any());
  }

  @Test
  public void fileSize_shouldInvokeTransformEncode() throws Exception {
    assertThat(storage.fileSize(file1CompressUri)).isEqualTo(0L);
    verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file1Filename));
  }

  @Test
  public void multipleTransformsShouldBeEncodedForwardAndComposedInReverse() throws Exception {
    // The spec "transform=compress+encrypt" means the data is compressed and then
    // encrypted before stored. Since transforms are implemented by wrapping transforms,
    // they need to be instantiated in the reverse order. So, in this case,
    // 1. encrypt is instantiated
    // 2. encrypt wraps the backend stream
    // 3. compress is instantiated
    // 4. compress wraps the encrypted stream
    // 5. the compress transforms stream is returned to the client
    // In contrast, encode() is called in the order in which transforms appear in the fragment.

    when(encryptTransform.wrapForWrite(eqParam(uriWithEncryptParam), any(OutputStream.class)))
        .thenReturn(encryptOutputStream);
    when(compressTransform.wrapForWrite(eqParam(uriWithCompressParam), eq(encryptOutputStream)))
        .thenReturn(compressOutputStream);
    try (OutputStream out = storage.open(file2CompressEncryptUri, WriteStreamOpener.create())) {

      InOrder forward = inOrder(compressTransform, encryptTransform);
      forward.verify(compressTransform).encode(eqParam(uriWithCompressParam), eq(file2Filename));
      forward.verify(encryptTransform).encode(eqParam(uriWithEncryptParam), eq(file2Filename));

      InOrder reverse = inOrder(encryptTransform, compressTransform);
      reverse
          .verify(encryptTransform)
          .wrapForWrite(eqParam(uriWithEncryptParam), any(OutputStream.class));
      reverse
          .verify(compressTransform)
          .wrapForWrite(eqParam(uriWithCompressParam), eq(encryptOutputStream));
    }
  }

  @Test
  public void children_shouldInvokeTransformDecodeInReverse() throws Exception {
    // The spec "transform=compress+encrypt" means the data is compressed and then encrypted.
    // When listing children, transform decodes() are invoked in reverse.

    when(fileBackend.children(eq(file2Uri))).thenReturn(Arrays.asList(Uri.parse("file:///child1")));
    assertThat(storage.children(file2CompressEncryptUri)).isNotNull();

    InOrder reverse = inOrder(encryptTransform, compressTransform);
    reverse.verify(encryptTransform).decode(eqParam(uriWithEncryptParam), eq("child1"));
    reverse.verify(compressTransform).decode(eqParam(uriWithCompressParam), eq("child1"));
  }

  @Test
  public void children_transformsShouldNotDecodeSubdirectories() throws Exception {
    when(fileBackend.children(eq(file1Uri)))
        .thenReturn(
            Arrays.asList(
                Uri.parse("file:///file1"),
                Uri.parse("file:///file2"),
                Uri.parse("file:///dir1/")));
    assertThat(storage.children(file1CompressUri)).isNotNull();

    verify(compressTransform).decode(eqParam(uriWithCompressParam), eq("file1"));
    verify(compressTransform).decode(eqParam(uriWithCompressParam), eq("file2"));
    verify(compressTransform, never()).decode(eqParam(uriWithCompressParam), eq("dir1"));
    verify(compressTransform, atLeast(1)).name();
    verifyNoMoreInteractions(compressTransform);
  }

  //
  // Monitor stuff
  //

  @Test
  public void read_shouldMonitor() throws Exception {
    try (InputStream in = storage.open(file1Uri, ReadStreamOpener.create())) {
      verify(countingMonitor).monitorRead(file1Uri);
    }
  }

  @Test
  public void write_shouldMonitor() throws Exception {
    try (OutputStream out = storage.open(file1Uri, WriteStreamOpener.create())) {
      verify(countingMonitor).monitorWrite(file1Uri);
    }
  }

  @Test
  public void append_shouldMonitor() throws Exception {
    try (OutputStream out = storage.open(file1Uri, AppendStreamOpener.create())) {
      verify(countingMonitor).monitorAppend(file1Uri);
    }
  }

  @Test
  public void readWithTransform_shouldGetOriginalUri() throws Exception {
    try (InputStream in = storage.open(file1CompressUri, ReadStreamOpener.create())) {
      verify(countingMonitor).monitorRead(file1CompressUri);
    }
  }

  //
  // MobStoreGc stuff
  //

  @Test
  public void gcMethods_shouldInvokeCorrespondingBackendMethods() throws Exception {
    GcParam param = GcParam.expiresAt(new Date(1L));
    storage.setGcParam(file1Uri, param);
    verify(fileBackend).setGcParam(eq(file1Uri), eq(param));
    storage.getGcParam(file1Uri);
    verify(fileBackend).getGcParam(eq(file1Uri));
  }
}
