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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets.UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.app.blob.BlobStoreManager;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;
import android.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.common.LimitExceededException;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Random;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class BlobStoreBackendTest {
  public static final String TAG = "BlobStoreBackendTest";

  private Context context;
  private BlobStoreBackend backend;
  private BlobStoreManager blobStoreManager;

  @Before
  public final void setUpStorage() {
    context = ApplicationProvider.getApplicationContext();
    backend = new BlobStoreBackend(context);
    blobStoreManager = (BlobStoreManager) context.getSystemService(Context.BLOB_STORE_SERVICE);
  }

  @After
  public void tearDown() throws Exception {
    // Commands to clean up the blob storage.
    runShellCmd("cmd blob_store clear-all-sessions");
    runShellCmd("cmd blob_store clear-all-blobs");
    context.getFilesDir().delete();
  }

  @Test
  public void nativeReadAfterWrite_succeeds() throws Exception {
    byte[] content = "nativeReadAfterWrite_succeeds".getBytes(UTF_8);
    String checksum = computeDigest(content);
    Uri uri = BlobUri.builder(context).setBlobParameters(checksum).build();
    int numOfLeases = blobStoreManager.getLeasedBlobs().size();

    try (OutputStream out = backend.openForWrite(uri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases);

    Uri uriForRead = BlobUri.builder(context).setBlobParameters(checksum).build();
    Pair<Uri, Closeable> closeableUri = backend.openForNativeRead(uriForRead);
    assertThat(closeableUri.second).isNotNull();
    int nativeFd = FileDescriptorUri.getFd(closeableUri.first);
    try (InputStream in =
            new FileInputStream(ParcelFileDescriptor.fromFd(nativeFd).getFileDescriptor());
        Closeable c = closeableUri.second) {
      assertThat(ByteStreams.toByteArray(in)).isEqualTo(content);
    }
  }

  @Test
  public void readAfterWrite_succeeds() throws Exception {
    byte[] content = "readAfterWrite_succeeds".getBytes(UTF_8);
    String checksum = computeDigest(content);
    Uri uri = BlobUri.builder(context).setBlobParameters(checksum).build();
    int numOfLeases = blobStoreManager.getLeasedBlobs().size();

    try (OutputStream out = backend.openForWrite(uri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases);

    Uri uriForRead = BlobUri.builder(context).setBlobParameters(checksum).build();
    try (InputStream in = backend.openForRead(uriForRead)) {
      assertThat(in).isNotNull();
      assertThat(ByteStreams.toByteArray(in)).isEqualTo(content);
    }
  }

  @Test
  public void exists() throws Exception {
    byte[] content = "exists".getBytes(UTF_8);
    String checksum = computeDigest(content);
    Uri uri = BlobUri.builder(context).setBlobParameters(checksum).build();

    assertThat(backend.exists(uri)).isFalse();

    try (OutputStream out = backend.openForWrite(uri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }

    assertThat(backend.exists(uri)).isTrue();
  }

  @Test
  public void writeLease_succeeds() throws Exception {
    byte[] content = "writeLease_succeeds".getBytes(UTF_8);
    String checksum = computeDigest(content);
    Uri blobUri = BlobUri.builder(context).setBlobParameters(checksum).build();
    int numOfLeases = blobStoreManager.getLeasedBlobs().size();
    try (OutputStream out = backend.openForWrite(blobUri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }

    Uri leaseUri = BlobUri.builder(context).setLeaseParameters(checksum, 0).build();

    OutputStream out = backend.openForWrite(leaseUri);
    assertThat(out).isNull();

    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases + 1);
  }

  @Test
  public void writeLeaseNonExistentFile_shouldThrow() throws Exception {
    byte[] content = "writeLeaseNonExistentFile_shouldThrow".getBytes(UTF_8);
    String checksum = computeDigest(content);

    Uri uri = BlobUri.builder(context).setLeaseParameters(checksum, 0).build();

    assertThrows(SecurityException.class, () -> backend.openForWrite(uri));
  }

  @Test
  public void delete_succeeds() throws Exception {
    byte[] content = "delete_succeeds".getBytes(UTF_8);
    String checksum = computeDigest(content);
    Uri blobUri = BlobUri.builder(context).setBlobParameters(checksum).build();
    int numOfLeases = blobStoreManager.getLeasedBlobs().size();

    try (OutputStream out = backend.openForWrite(blobUri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }

    Uri leaseUri = BlobUri.builder(context).setLeaseParameters(checksum, 0).build();
    try (OutputStream out = backend.openForWrite(leaseUri)) {
      assertThat(out).isNull();
    }
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases + 1);

    backend.deleteFile(leaseUri);

    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases);
  }

  @Test
  public void releaseAllLeases_succeeds() throws Exception {
    // Write and acquire lease on first file
    byte[] content = "releaseAllLeases_succeeds_1".getBytes(UTF_8);
    String checksum = computeDigest(content);
    Uri blobUri = BlobUri.builder(context).setBlobParameters(checksum).build();
    int numOfLeases = blobStoreManager.getLeasedBlobs().size();
    try (OutputStream out = backend.openForWrite(blobUri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }
    Uri leaseUri = BlobUri.builder(context).setLeaseParameters(checksum, 0).build();
    try (OutputStream out = backend.openForWrite(leaseUri)) {
      assertThat(out).isNull();
    }
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases + 1);

    // Write and acquire lease on second file
    content = "releaseAllLeases_succeeds_2".getBytes(UTF_8);
    checksum = computeDigest(content);
    blobUri = BlobUri.builder(context).setBlobParameters(checksum).build();
    try (OutputStream out = backend.openForWrite(blobUri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }
    leaseUri = BlobUri.builder(context).setLeaseParameters(checksum, 0).build();
    try (OutputStream out = backend.openForWrite(leaseUri)) {
      assertThat(out).isNull();
    }
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases + 2);

    // Release all leases
    Uri allLeases = BlobUri.builder(context).setAllLeasesParameters().build();

    backend.deleteFile(allLeases);

    assertThat(blobStoreManager.getLeasedBlobs()).isEmpty();
  }

  @Test
  public void deleteNonExistentFile_shouldThrow() throws Exception {
    BlobStoreBackend backend = new BlobStoreBackend(context);
    byte[] content = "deleteNonExistentFile_shouldThrow".getBytes(UTF_8);
    String checksum = computeDigest(content);

    Uri uri = BlobUri.builder(context).setBlobParameters(checksum).build();

    assertThrows(IOException.class, () -> backend.deleteFile(uri));
  }

  private static String computeDigest(byte[] byteContent) throws Exception {
    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
    if (messageDigest == null) {
      return "";
    }
    return BaseEncoding.base16().lowerCase().encode(messageDigest.digest(byteContent));
  }

  @Test
  public void writeLeaseExceedsLimit_shouldThrow() throws Exception {
    long initialRemainingQuota = blobStoreManager.getRemainingLeaseQuotaBytes();
    int numOfLeases = blobStoreManager.getLeasedBlobs().size();
    int numberOfFiles = 20;
    int singleFileSize = (int) initialRemainingQuota / numberOfFiles;
    long expectedRemainingQuota = initialRemainingQuota - singleFileSize * numberOfFiles;

    // Create small files rather than one big file to avoid OutOfMemoryError
    for (int i = 0; i < numberOfFiles; i++) {
      byte[] content = generateRandomBytes(singleFileSize);
      String checksum = computeDigest(content);
      Uri blobUri = BlobUri.builder(context).setBlobParameters(checksum).build();

      try (OutputStream out = backend.openForWrite(blobUri)) {
        assertThat(out).isNotNull();
        out.write(content);
      }
      Uri leaseUri = BlobUri.builder(context).setLeaseParameters(checksum, 0).build();
      OutputStream out = backend.openForWrite(leaseUri);
      assertThat(out).isNull();
    }
    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases + numberOfFiles);
    assertThat(blobStoreManager.getRemainingLeaseQuotaBytes()).isEqualTo(expectedRemainingQuota);

    // Write one more file bigger than available quota. Acquiring the lease on it will throw
    // LimitExceededException.
    byte[] content = generateRandomBytes((int) expectedRemainingQuota + 1);
    String checksum = computeDigest(content);
    Uri blobUri = BlobUri.builder(context).setBlobParameters(checksum).build();
    try (OutputStream out = backend.openForWrite(blobUri)) {
      assertThat(out).isNotNull();
      out.write(content);
    }
    Uri exceedingLeaseUri = BlobUri.builder(context).setLeaseParameters(checksum, 0).build();

    assertThrows(LimitExceededException.class, () -> backend.openForWrite(exceedingLeaseUri));

    assertThat(blobStoreManager.getLeasedBlobs()).hasSize(numOfLeases + numberOfFiles);
    assertThat(blobStoreManager.getRemainingLeaseQuotaBytes()).isEqualTo(expectedRemainingQuota);
  }

  private static byte[] generateRandomBytes(int length) {
    byte[] content = new byte[length];
    new Random().nextBytes(content);
    return content;
  }

  private static String runShellCmd(String cmd) throws IOException {
    final UiDevice uiDevice = UiDevice.getInstance(getInstrumentation());
    final String result = uiDevice.executeShellCommand(cmd).trim();
    Log.i(TAG, "Output of '" + cmd + "': '" + result + "'");
    return result;
  }
}
