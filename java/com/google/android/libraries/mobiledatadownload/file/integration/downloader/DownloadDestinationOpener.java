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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.net.Uri;
import com.google.android.downloader.DownloadDestination;
import com.google.android.downloader.DownloadMetadata;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.ReleasableResource;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.openers.RandomAccessFileOpener;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * A MobStore Opener for <internal>'s {@link DownloadDestination}.
 *
 * <p>This creates a {@link DownloadDestination} that supports writing data and connects to a shared
 * PDS instance to handle metadata updates.
 *
 * <pre>{@code
 * Downloader downloader = new Downloader(...);
 * DownloadMetadataStore metadataStore = ...;
 * SynchronousFileStorage storage = new SynchronousFileStorage(...);
 *
 * DownloadDestination destination = storage.open(
 *   targetFileUri,
 *   DownloadDestinationOpener.create(metadataStore));
 *
 * downloader.execute(
 *   downloader
 *     .newRequestBuilder(urlToDownload, destination)
 *     .build());
 * }</pre>
 */
public final class DownloadDestinationOpener implements Opener<DownloadDestination> {
  private static final long TIMEOUT_MS = 1000;

  /** Implementation of {@link DownloadDestination} created by the opener. */
  private static final class DownloadDestinationImpl implements DownloadDestination {
    // We need to touch two underlying files (metadata from DownloadMetadataStore and the downloaded
    // file). Define a lock to keep the access of these files synchronized.
    private final Object lock = new Object();

    private final Uri onDeviceUri;
    private final DownloadMetadataStore metadataStore;
    private final SynchronousFileStorage fileStorage;

    private DownloadDestinationImpl(
        Uri onDeviceUri, SynchronousFileStorage fileStorage, DownloadMetadataStore metadataStore) {
      this.onDeviceUri = onDeviceUri;
      this.metadataStore = metadataStore;
      this.fileStorage = fileStorage;
    }

    @Override
    public long numExistingBytes() throws IOException {
      return fileStorage.fileSize(onDeviceUri);
    }

    @Override
    public DownloadMetadata readMetadata() throws IOException {
      synchronized (lock) {
        Optional<DownloadMetadata> existingMetadata =
            blockingGet(metadataStore.read(onDeviceUri), "Failed to read metadata.");

        // Return existing metadata, or a new instance.
        return existingMetadata.or(DownloadMetadata::create);
      }
    }

    @Override
    public WritableByteChannel openByteChannel(long byteOffset, DownloadMetadata metadata)
        throws IOException {
      // Ensure that metadata is not null
      checkArgument(metadata != null, "Received null metadata to store");
      // Check that offset is in range
      long fileSize = numExistingBytes();
      checkArgument(
          byteOffset >= 0 && byteOffset <= fileSize,
          "Offset for write (%s) out of range of existing file size (%s bytes)",
          byteOffset,
          fileSize);

      synchronized (lock) {
        // Update metadata first.
        blockingGet(metadataStore.upsert(onDeviceUri, metadata), "Failed to update metadata.");

        // Use ReleasableResource to ensure channel is setup properly before returning it.
        try (ReleasableResource<RandomAccessFile> file =
            ReleasableResource.create(
                fileStorage.open(onDeviceUri, RandomAccessFileOpener.createForReadWrite()))) {
          // Get channel and seek to correct offset.
          FileChannel channel = file.get().getChannel();
          channel.position(byteOffset);

          // Release ownership -- caller is responsible for closing the channel.
          file.release();

          return channel;
        }
      }
    }

    @Override
    public void clear() throws IOException {
      synchronized (lock) {
        // clear metadata and delete file.
        blockingGet(metadataStore.delete(onDeviceUri), "Failed to clear metadata.");

        fileStorage.deleteFile(onDeviceUri);
      }
    }

    /**
     * Helper method for async call error handling.
     *
     * <p>Exceptions due to an async call failure are handled and wrapped in an IOException.
     */
    private static <V> V blockingGet(ListenableFuture<V> future, String errorMessage)
        throws IOException {
      try {
        return future.get(TIMEOUT_MS, MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException(errorMessage, e.getCause());
      } catch (ExecutionException e) {
        throw new IOException(errorMessage, e.getCause());
      } catch (TimeoutException | CancellationException e) {
        throw new IOException(errorMessage, e);
      }
    }
  }

  private final DownloadMetadataStore metadataStore;

  private DownloadDestinationOpener(DownloadMetadataStore metadataStore) {
    this.metadataStore = metadataStore;
  }

  @Override
  public DownloadDestination open(OpenContext openContext) throws IOException {
    if (openContext.hasTransforms()) {
      throw new UnsupportedFileStorageOperation(
          "Transforms are not supported by this Opener: " + openContext.originalUri());
    }

    // Check whether or not the file uri is a directory.
    if (openContext.storage().isDirectory(openContext.originalUri())) {
      throw new IOException(
          new IllegalArgumentException("Requested file download is already a directory."));
    }

    return new DownloadDestinationImpl(
        openContext.originalUri(), openContext.storage(), metadataStore);
  }

  public static DownloadDestinationOpener create(DownloadMetadataStore metadataStore) {
    return new DownloadDestinationOpener(metadataStore);
  }
}
