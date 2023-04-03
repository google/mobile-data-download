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
package com.google.android.libraries.mobiledatadownload.file.integration.downloader;

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.makeArrayOfBytesContent;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.SharedPreferences;
import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.downloader.DownloadDestination;
import com.google.android.downloader.DownloadMetadata;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadByteArrayOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteByteArrayOpener;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Bytes;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

@RunWith(ParameterizedRobolectricTestRunner.class)
public final class DownloadDestinationOpenerTest {
  private static final long TIMEOUT = 3L;

  private static final byte[] CONTENT = makeArrayOfBytesContent();

  private static final ListeningExecutorService EXECUTOR_SERVICE =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

  private SynchronousFileStorage storage;

  private final Implementation implUnderTest;
  private SharedPreferences downloadMetadataSp;

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  /* Run the same test suite on two implementations of the same interface. */
  private enum Implementation {
    SHARED_PREFERENCES,
  }

  @Parameters(name = "implementation={0}")
  public static ImmutableList<Object[]> data() {
    return ImmutableList.of(new Object[] {Implementation.SHARED_PREFERENCES});
  }

  public DownloadDestinationOpenerTest(Implementation impl) {
    this.implUnderTest = impl;
  }

  @Before
  public void setUp() throws Exception {
    storage = new SynchronousFileStorage(ImmutableList.of(new JavaFileBackend()));
    downloadMetadataSp =
        ApplicationProvider.getApplicationContext().getSharedPreferences("prefs", 0);
  }

  @Test
  public void opener_withNoExistingData_createsNewMetadata() throws Exception {
    Uri fileUri = tmpUri.newUri();
    DownloadMetadata emptyMetadata = DownloadMetadata.create("", 0);

    // Create destination.
    DownloadMetadataStore store = createMetadataStore();
    DownloadDestinationOpener opener = DownloadDestinationOpener.create(store, EXECUTOR_SERVICE);
    DownloadDestination destination = storage.open(fileUri, opener);

    // Asset that destination has initial, empty values.
    assertThat(destination.numExistingBytes().get(TIMEOUT, SECONDS)).isEqualTo(0);
    assertThat(destination.readMetadata().get(TIMEOUT, SECONDS)).isEqualTo(emptyMetadata);
  }

  @Test
  public void opener_withNoExistingData_writes() throws Exception {
    Uri fileUri = tmpUri.newUri();

    // Set up data and metadata to write.
    ByteBuffer buffer = ByteBuffer.allocate(CONTENT.length);
    buffer.put(CONTENT);
    buffer.position(0);

    DownloadMetadata metadataToWrite = DownloadMetadata.create("test", 10);

    // Create destination and write data/metadata.
    DownloadMetadataStore store = createMetadataStore();
    DownloadDestinationOpener opener = DownloadDestinationOpener.create(store, EXECUTOR_SERVICE);
    DownloadDestination destination = storage.open(fileUri, opener);

    try (WritableByteChannel writeChannel =
        destination.openByteChannel(0, metadataToWrite).get(TIMEOUT, SECONDS)) {
      writeChannel.write(buffer);
    }

    // Read back data to ensure the write completed properly.
    ReadByteArrayOpener readOpener = ReadByteArrayOpener.create();
    byte[] readContent = storage.open(fileUri, readOpener);

    assertThat(readContent).hasLength(CONTENT.length);
    assertThat(readContent).isEqualTo(CONTENT);

    // Assert that destination now reflects the latest state.
    assertThat(destination.numExistingBytes().get(TIMEOUT, SECONDS)).isEqualTo(CONTENT.length);
    assertThat(destination.readMetadata().get(TIMEOUT, SECONDS)).isEqualTo(metadataToWrite);
  }

  @Test
  public void opener_withExistingData_usesExistingMetadata() throws Exception {
    Uri fileUri = tmpUri.newUri();

    DownloadMetadata expectedMetadata = DownloadMetadata.create("test", 10);

    // Set up file with existing data and existing metadata in store.
    Void unused = storage.open(fileUri, WriteByteArrayOpener.create(CONTENT));

    writeToMetadataStore(fileUri.toString(), expectedMetadata);

    // Create destination.
    DownloadMetadataStore store = createMetadataStore();
    DownloadDestinationOpener opener = DownloadDestinationOpener.create(store, EXECUTOR_SERVICE);
    DownloadDestination destination = storage.open(fileUri, opener);

    // Assert that destination now reflects the latest state.
    assertThat(destination.numExistingBytes().get(TIMEOUT, SECONDS)).isEqualTo(CONTENT.length);
    assertThat(destination.readMetadata().get(TIMEOUT, SECONDS)).isEqualTo(expectedMetadata);
  }

  @Test
  public void opener_withExistingData_writes() throws Exception {
    Uri fileUri = tmpUri.newUri();
    byte[] newContent = makeArrayOfBytesContent();
    byte[] expectedContent = Bytes.concat(CONTENT, newContent);

    // Set up file with existing data and existing metadata in store
    Void unused = storage.open(fileUri, WriteByteArrayOpener.create(CONTENT));

    writeToMetadataStore(fileUri.toString(), DownloadMetadata.create("initial", 5L));

    // Set up data/metadata to write.
    ByteBuffer buffer = ByteBuffer.allocate(newContent.length);
    buffer.put(newContent);
    buffer.position(0);

    DownloadMetadata metadataToWrite = DownloadMetadata.create("test", 10);

    // Create destination and write data/metadata.
    DownloadMetadataStore store = createMetadataStore();
    DownloadDestinationOpener opener = DownloadDestinationOpener.create(store, EXECUTOR_SERVICE);
    DownloadDestination destination = storage.open(fileUri, opener);

    try (WritableByteChannel writeChannel =
        destination
            .openByteChannel(destination.numExistingBytes().get(TIMEOUT, SECONDS), metadataToWrite)
            .get(TIMEOUT, SECONDS)) {
      writeChannel.write(buffer);
    }

    // Read back data to ensure the write completed properly.
    byte[] readContent = storage.open(fileUri, ReadByteArrayOpener.create());

    assertThat(readContent).hasLength(expectedContent.length);
    assertThat(readContent).isEqualTo(expectedContent);

    // Assert that destination now reflects the latest state.
    assertThat(destination.numExistingBytes().get(TIMEOUT, SECONDS))
        .isEqualTo(expectedContent.length);
    assertThat(destination.readMetadata().get(TIMEOUT, SECONDS)).isEqualTo(metadataToWrite);
  }

  @Test
  public void opener_clearsMetadataAndData() throws Exception {
    Uri fileUri = tmpUri.newUri();
    DownloadMetadata emptyMetadata = DownloadMetadata.create("", 0);

    // Set up file with existing data and existing metadata in store.
    Void unused = storage.open(fileUri, WriteByteArrayOpener.create(CONTENT));

    writeToMetadataStore(fileUri.toString(), DownloadMetadata.create("test", 10L));

    // Create destination and clear.
    DownloadMetadataStore store = createMetadataStore();
    DownloadDestinationOpener opener = DownloadDestinationOpener.create(store, EXECUTOR_SERVICE);
    DownloadDestination destination = storage.open(fileUri, opener);

    destination.clear().get(TIMEOUT, SECONDS);

    // Assert that destination now reflects the latest state.
    assertThat(destination.numExistingBytes().get(TIMEOUT, SECONDS)).isEqualTo(0);
    assertThat(destination.readMetadata().get(TIMEOUT, SECONDS)).isEqualTo(emptyMetadata);
  }

  private DownloadMetadataStore createMetadataStore() throws Exception {
    switch (implUnderTest) {
      case SHARED_PREFERENCES:
        return new SharedPreferencesDownloadMetadata(downloadMetadataSp, EXECUTOR_SERVICE);
    }
    throw new AssertionError(); // Exhaustive switch
  }

  private void writeToMetadataStore(String fileUri, DownloadMetadata metadataToWrite)
      throws InterruptedException, ExecutionException, TimeoutException {
    switch (implUnderTest) {
      case SHARED_PREFERENCES:
        downloadMetadataSp.edit().clear().commit();
        new SharedPreferencesDownloadMetadata(downloadMetadataSp, EXECUTOR_SERVICE)
            .upsert(Uri.parse(fileUri), metadataToWrite)
            .get(TIMEOUT, SECONDS);
        break;
    }
  }
}
