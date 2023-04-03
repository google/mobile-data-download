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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.appendFile;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFileInBytes;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.readFileInBytesFromSource;
import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.writeFileToSink;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.FileConvertible;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.openers.AppendStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.primitives.Bytes;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Base class for {@code Backend} tests that exercises common behavior expected of all
 * implementations. Concrete test cases must specify a test runner, extend from this class, and
 * implement the abstract setup methods. Subclasses are free to add additional test methods in order
 * to exercise backend-specific behavior using the provided {@link #storage}.
 *
 * <p>If the backend under test does not support a specific feature, the test subclass should
 * override the appropriate {@code supportsX()} and return false in order to skip the associated
 * unit tests. NOTE: this is adopted from Guava and seems like the least-bad strategy.
 *
 * <p>Abstract setup methods may be called before the {@code @Before} methods of the subclass, and
 * so should not depend on them. {@code @BeforeClass}, lazy initialization, and static
 * initialization are viable alternatives.
 */
public abstract class BackendTestBase {

  private SynchronousFileStorage storage;
  private static final byte[] TEST_CONTENT = makeArrayOfBytesContent();
  private static final byte[] OTHER_CONTENT = makeArrayOfBytesContent(6);

  @Rule public TestName testName = new TestName();

  /** Returns the concrete {@code Backend} instance to be tested. */
  protected abstract Backend backend();

  /** Enables unit tests verifying {@link Backend#openForAppend}. */
  protected boolean supportsAppend() {
    return true;
  }

  /** Enables unit tests verifying {@link Backend#rename}. */
  protected boolean supportsRename() {
    return true;
  }

  /**
   * Enables unit tests verifying {@link Backend#createDirectory}, {@link Backend#isDirectory},
   * {@link Backend#deleteDirectory}, {@link Backend#children}, and writing to a subdirectory uri.
   */
  protected boolean supportsDirectories() {
    return true;
  }

  /** Enables unit tests verifying that {@link FileConvertible} can be returned directly. */
  protected boolean supportsFileConvertible() {
    return true;
  }

  /** Enable unit tests verifying {@link Backend#toFile}. */
  protected boolean supportsToFile() {
    return true;
  }

  /**
   * Returns a URI to a temporary directory for writing test data to. The {@code Backend} should be
   * able to {@code openForWrite} a file to this directory without any additional setup code.
   */
  protected abstract Uri legalUriBase() throws IOException;

  /**
   * Returns a list of URIs for which {@code Backend.openForRead(uri)} is expected to throw {@code
   * MalformedUriException} without any additional setup code.
   */
  protected List<Uri> illegalUrisToRead() {
    return ImmutableList.of();
  }

  /**
   * Returns a list of URIs for which {@code Backend.openForWrite(uri)} is expected to throw {@code
   * MalformedUriException} without any additional setup code.
   */
  protected List<Uri> illegalUrisToWrite() {
    return ImmutableList.of();
  }

  /**
   * Returns a list of URIs for which {@code Backend.openForAppend(uri)} is expected to throw {@code
   * MalformedUriException} without any additional setup code.
   */
  protected List<Uri> illegalUrisToAppend() {
    return ImmutableList.of();
  }

  @Before
  public final void setUpStorage() {
    assertThat(backend()).isNotNull();
    storage = new SynchronousFileStorage(ImmutableList.of(backend()), ImmutableList.of());
  }

  /** Returns the storage instance used in testing. */
  protected SynchronousFileStorage storage() {
    return storage;
  }

  @Test
  public void name_returnsNonEmptyString() {
    assertThat(backend().name()).isNotEmpty();
  }

  @Test
  public void openForRead_withMissingFile_throwsFileNotFound() throws Exception {
    Uri uri = uriForTestMethod();
    assertThrows(FileNotFoundException.class, () -> storage.open(uri, ReadStreamOpener.create()));
  }

  @Test
  public void openForRead_withIllegalUri_throwsIllegalArgumentException() throws Exception {
    for (Uri uri : illegalUrisToRead()) {
      assertThrows(MalformedUriException.class, () -> storage.open(uri, ReadStreamOpener.create()));
    }
  }

  @Test
  public void openForRead_readsWrittenContent() throws Exception {
    Uri uri = uriForTestMethod();
    createFile(storage, uri, TEST_CONTENT);
    assertThat(readFileInBytes(storage, uri)).isEqualTo(TEST_CONTENT);
  }

  @Test
  public void openForRead_returnsFileConvertible() throws Exception {
    assumeTrue(supportsFileConvertible());

    Uri uri = uriForTestMethod();
    createFile(storage(), uri, TEST_CONTENT);

    InputStream stream = backend().openForRead(uri);
    assertThat(stream).isInstanceOf(FileConvertible.class);
    File file = ((FileConvertible) stream).toFile();
    assertThat(readFileInBytesFromSource(new FileInputStream(file))).isEqualTo(TEST_CONTENT);
  }

  @Test
  public void openForWrite_withIllegalUri_throwsIllegalArgumentException() throws Exception {
    for (Uri uri : illegalUrisToWrite()) {
      assertThrows(
          MalformedUriException.class, () -> storage.open(uri, WriteStreamOpener.create()));
    }
  }

  @Test
  public void openForWrite_withFailedDirectoryCreation_throwsException() throws Exception {
    assumeTrue(supportsDirectories());

    Uri parent = uriForTestMethod();
    createFile(storage, parent, TEST_CONTENT);

    Uri child = parent.buildUpon().appendPath("child").build();
    assertThrows(IOException.class, () -> storage.open(child, WriteStreamOpener.create()));
  }

  @Test
  public void openForWrite_overwritesExistingContent() throws Exception {
    Uri uri = uriForTestMethod();
    createFile(storage, uri, OTHER_CONTENT);

    createFile(storage, uri, TEST_CONTENT);
    assertThat(readFileInBytes(storage, uri)).isEqualTo(TEST_CONTENT);
  }

  @Test
  public void openForWrite_createsParentDirectory() throws Exception {
    assumeTrue(supportsDirectories());

    Uri parent = uriForTestMethod();
    Uri child = parent.buildUpon().appendPath("child").build();

    createFile(storage, child, TEST_CONTENT);
    assertThat(storage.isDirectory(parent)).isTrue();
    assertThat(storage.exists(child)).isTrue();
  }

  @Test
  public void openForWrite_returnsFileConvertible() throws Exception {
    assumeTrue(supportsFileConvertible());

    Uri uri = uriForTestMethod();
    try (OutputStream stream = backend().openForWrite(uri)) {
      assertThat(stream).isInstanceOf(FileConvertible.class);
      File file = ((FileConvertible) stream).toFile();
      writeFileToSink(new FileOutputStream(file), TEST_CONTENT);
    }
    assertThat(readFileInBytes(storage(), uri)).isEqualTo(TEST_CONTENT);
  }

  @Test
  public void openForAppend_withIllegalUri_throwsIllegalArgumentException() throws Exception {
    assumeTrue(supportsAppend());

    for (Uri uri : illegalUrisToAppend()) {
      assertThrows(
          MalformedUriException.class, () -> storage.open(uri, AppendStreamOpener.create()));
    }
  }

  @Test
  public void openForAppend_appendsContent() throws Exception {
    assumeTrue(supportsAppend());

    Uri uri = uriForTestMethod();
    createFile(storage, uri, OTHER_CONTENT);

    appendFile(storage, uri, TEST_CONTENT);
    assertThat(readFileInBytes(storage, uri)).isEqualTo(Bytes.concat(OTHER_CONTENT, TEST_CONTENT));
  }

  @Test
  public void openForAppend_returnsFileConvertible() throws Exception {
    assumeTrue(supportsAppend());
    assumeTrue(supportsFileConvertible());

    Uri uri = uriForTestMethod();
    createFile(storage, uri, OTHER_CONTENT);
    try (OutputStream stream = backend().openForAppend(uri)) {
      assertThat(stream).isInstanceOf(FileConvertible.class);
      File file = ((FileConvertible) stream).toFile();
      writeFileToSink(new FileOutputStream(file, /* append= */ true), TEST_CONTENT);
    }
    assertThat(readFileInBytes(storage(), uri))
        .isEqualTo(Bytes.concat(OTHER_CONTENT, TEST_CONTENT));
  }

  @Test
  public void deleteFile_deletesFile() throws Exception {
    Uri uri = uriForTestMethod();
    createFile(storage, uri, TEST_CONTENT);

    storage.deleteFile(uri);
    assertThat(storage.exists(uri)).isFalse();
  }

  @Test
  public void deleteFile_onDirectory_throwsFileNotFound() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    storage.createDirectory(uri);

    assertThrows(FileNotFoundException.class, () -> storage.deleteFile(uri));
  }

  @Test
  public void deleteFile_onMissingFile_throwsFileNotFound() throws Exception {
    Uri uri = uriForTestMethod();
    assertThrows(FileNotFoundException.class, () -> storage.deleteFile(uri));
  }

  @Test
  public void rename_renamesFile() throws Exception {
    assumeTrue(supportsRename());

    Uri uri1 = uriForTestMethodWithSuffix("1");
    Uri uri2 = uriForTestMethodWithSuffix("2");
    Uri uri3 = uriForTestMethodWithSuffix("3");
    createFile(storage, uri1, OTHER_CONTENT);
    createFile(storage, uri2, TEST_CONTENT);

    storage.rename(uri2, uri3);
    storage.rename(uri1, uri2);
    assertThat(storage.exists(uri1)).isFalse();
    assertThat(readFileInBytes(storage, uri2)).isEqualTo(OTHER_CONTENT);
    assertThat(readFileInBytes(storage, uri3)).isEqualTo(TEST_CONTENT);
  }

  @Test
  public void rename_renamesDirectory() throws Exception {
    assumeTrue(supportsRename());
    assumeTrue(supportsDirectories());

    Uri uriA = uriForTestMethodWithSuffix("a");
    Uri uriB = uriForTestMethodWithSuffix("b");

    storage.createDirectory(uriA);
    storage.rename(uriA, uriB);
    assertThat(storage.isDirectory(uriA)).isFalse();
    assertThat(storage.isDirectory(uriB)).isTrue();
  }

  @Test
  public void exists_returnsTrueIfFileExists() throws Exception {
    Uri uri = uriForTestMethod();
    assertThat(storage.exists(uri)).isFalse();

    createFile(storage, uri, TEST_CONTENT);
    assertThat(storage.exists(uri)).isTrue();
  }

  @Test
  public void exists_returnsTrueIfDirectoryExists() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    assertThat(storage.exists(uri)).isFalse();

    storage.createDirectory(uri);
    assertThat(storage.exists(uri)).isTrue();
  }

  @Test
  public void isDirectory_returnsTrueIfDirectoryExists() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    assertThat(storage.isDirectory(uri)).isFalse();

    storage.createDirectory(uri);
    assertThat(storage.isDirectory(uri)).isTrue();
  }

  @Test
  public void isDirectory_returnsFalseIfDoesntExist() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    assertThat(storage.isDirectory(uri)).isFalse();
  }

  @Test
  public void isDirectory_returnsFalseIfIsFile() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    createFile(storage, uri, TEST_CONTENT);
    assertThat(storage.isDirectory(uri)).isFalse();
  }

  @Test
  public void createDirectory_createsDirectory() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    storage.createDirectory(uri);
    assertThat(storage.isDirectory(uri)).isTrue();
  }

  @Test
  public void createDirectory_createsParentDirectory() throws Exception {
    assumeTrue(supportsDirectories());

    Uri parent = uriForTestMethod();
    Uri child = parent.buildUpon().appendPath("child").build();

    storage.createDirectory(child);
    assertThat(storage.isDirectory(child)).isTrue();
    assertThat(storage.isDirectory(parent)).isTrue();
  }

  @Test
  public void fileSize_withMissingFile_returnsZero() throws Exception {
    Uri uri = uriForTestMethod();
    assertThat(storage.fileSize(uri)).isEqualTo(0);
  }

  @Test
  public void fileSize_returnsSizeOfFile() throws Exception {
    Uri uri = uriForTestMethod();

    backend().openForWrite(uri).close();
    assertThat(storage.fileSize(uri)).isEqualTo(0);

    createFile(storage, uri, TEST_CONTENT);
    assertThat(storage.fileSize(uri)).isEqualTo(TEST_CONTENT.length);
  }

  @Test
  public void fileSize_withDirReturns0() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    storage.createDirectory(uri);
    assertThat(storage.fileSize(uri)).isEqualTo(0);
  }

  @Test
  public void deleteDirectory_shouldDeleteEmptyDirectory() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    assertThat(storage.isDirectory(uri)).isFalse();
    storage.createDirectory(uri);
    assertThat(storage.isDirectory(uri)).isTrue();
    storage.deleteDirectory(uri);
    assertThat(storage.isDirectory(uri)).isFalse();
  }

  @Test
  public void deleteDirectory_shouldNOTDeleteNonEmptyDirectory() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    storage.createDirectory(uri);
    Uri fileUri = uri.buildUpon().appendPath("file").build();
    createFile(storage, fileUri, TEST_CONTENT);

    assertThat(storage.isDirectory(uri)).isTrue();
    assertThrows(IOException.class, () -> storage.deleteDirectory(uri));
    assertThat(storage.isDirectory(uri)).isTrue();

    storage.deleteFile(fileUri);
    storage.deleteDirectory(uri);
    assertThat(storage.isDirectory(uri)).isFalse();
  }

  @Test
  public void deleteDirectory_onFileShouldThrow() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    createFile(storage, uri, TEST_CONTENT);

    assertThrows(IOException.class, () -> storage.deleteDirectory(uri));
  }

  @Test
  public void children_withEmptyDirectoryShouldReturnEmpty() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    storage.createDirectory(uri);

    assertThat(storage.children(uri)).isEmpty();
  }

  @Test
  public void children_onNotFoundShouldThrow() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();

    assertThrows(IOException.class, () -> storage.children(uri));
  }

  @Test
  public void children_onFileShouldThrow() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    createFile(storage, uri, TEST_CONTENT);

    assertThrows(IOException.class, () -> storage.children(uri));
  }

  @Test
  public void children_shouldReturnFilesAndSubDirectories() throws Exception {
    assumeTrue(supportsDirectories());

    Uri uri = uriForTestMethod();
    storage.createDirectory(uri);

    List<Uri> fileUris =
        Arrays.asList(
            uri.buildUpon().appendPath("file1").build(),
            uri.buildUpon().appendPath("file2").build(),
            uri.buildUpon().appendPath("file3").build());
    for (Uri file : fileUris) {
      createFile(storage, file, TEST_CONTENT);
    }

    List<Uri> subdirUris =
        Arrays.asList(
            uri.buildUpon().appendPath("dir1").build(),
            uri.buildUpon().appendPath("dir2").build(),
            uri.buildUpon().appendPath("dir3").build());
    for (Uri subdir : subdirUris) {
      storage.createDirectory(subdir);
    }
    List<Uri> subdirUrisWithTrailingSlashes =
        Lists.transform(subdirUris, u -> Uri.parse(u.toString() + "/"));

    List<Uri> expected = Lists.newArrayList();
    expected.addAll(fileUris);
    expected.addAll(subdirUrisWithTrailingSlashes);
    assertThat(storage.children(uri)).containsExactlyElementsIn(expected);
  }

  @Test
  public void toFile_converts() throws Exception {
    assumeTrue(supportsToFile());

    Uri uri = uriForTestMethod();
    createFile(storage, uri, TEST_CONTENT);
    File file = backend().toFile(uri);
    assertThat(readFileInBytesFromSource(new FileInputStream(file))).isEqualTo(TEST_CONTENT);
  }

  /** Returns a URI in the test directory unique to the current test method. */
  protected Uri uriForTestMethod() throws IOException {
    return legalUriBase().buildUpon().appendPath(testName.getMethodName()).build();
  }

  /** Returns a URI in the test directory unique to the current test method and {@code suffix}. */
  private Uri uriForTestMethodWithSuffix(String suffix) throws IOException {
    return legalUriBase().buildUpon().appendPath(testName.getMethodName() + "-" + suffix).build();
  }
}
