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
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.net.Uri;
import com.google.android.downloader.DownloadDestination;
import com.google.android.downloader.DownloadMetadata;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.common.ReleasableResource;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.openers.RandomAccessFileOpener;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.util.concurrent.ExecutionSequencer;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.Executor;

/**
 * A MobStore Opener for <internal>'s {@link DownloadDestination}.
 *
 * <p>This creates an {@link DownloadDestination} that supports writing data and connects to a
 * shared PDS instance to handle metadata updates.
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
  /** Implementation of {@link DownloadDestination} created by the opener. */
  private static final class DownloadDestinationImpl implements DownloadDestination {

    private final Uri onDeviceUri;
    private final DownloadMetadataStore metadataStore;
    private final SynchronousFileStorage fileStorage;
    private final Executor lightweightExecutor;

    // Create an ExecutionSequencer to ensure that access to file metadata and data happen in a
    // single "transaction." This ensures that metadata and data appear in a consistent state to
    // callers even if methods are called simultaneously (i.e. ensuring that if clear() is called
    // before openByteChannel(), the file is removed
    private final ExecutionSequencer sequencer = ExecutionSequencer.create();

    private DownloadDestinationImpl(
        Uri onDeviceUri,
        SynchronousFileStorage fileStorage,
        DownloadMetadataStore metadataStore,
        Executor lightweightExecutor) {
      this.onDeviceUri = onDeviceUri;
      this.metadataStore = metadataStore;
      this.fileStorage = fileStorage;
      this.lightweightExecutor = lightweightExecutor;
    }

    @Override
    public ListenableFuture<Long> numExistingBytes() {
      return sequencer.submit(() -> fileStorage.fileSize(onDeviceUri), lightweightExecutor);
    }

    @Override
    public ListenableFuture<DownloadMetadata> readMetadata() {
      // Return existing metadata, or a new instance.
      return sequencer.submitAsync(
          () ->
              PropagatedFutures.transform(
                  metadataStore.read(onDeviceUri),
                  existingMetadata -> existingMetadata.or(DownloadMetadata::create),
                  lightweightExecutor),
          lightweightExecutor);
    }

    @Override
    public ListenableFuture<WritableByteChannel> openByteChannel(
        long byteOffset, DownloadMetadata metadata) {
      // Ensure that metadata is not null
      checkArgument(metadata != null, "Received null metadata to store");
      // Check that offset is in range
      return PropagatedFutures.transformAsync(
          numExistingBytes(),
          fileSize -> {
            checkArgument(
                byteOffset >= 0 && byteOffset <= fileSize,
                "Offset for write (%s) out of range of existing file size (%s bytes)",
                byteOffset,
                fileSize);

            return sequencer.submitAsync(
                () ->
                    // Update metadata first.
                    PropagatedFluentFuture.from(metadataStore.upsert(onDeviceUri, metadata))
                        .transformAsync(
                            unused -> {
                              // Use ReleasableResource to ensure channel is setup properly before
                              // returning it.
                              try (ReleasableResource<RandomAccessFile> file =
                                  ReleasableResource.create(
                                      fileStorage.open(
                                          onDeviceUri,
                                          RandomAccessFileOpener.createForReadWrite()))) {
                                // Get channel and seek to correct offset.
                                FileChannel channel = file.get().getChannel();
                                channel.position(byteOffset);

                                // Release ownership -- caller is responsible for closing the
                                // channel.
                                file.release();

                                return immediateFuture(channel);
                              } catch (IOException ioException) {
                                return immediateFailedFuture(ioException);
                              }
                            },
                            lightweightExecutor),
                lightweightExecutor);
          },
          lightweightExecutor);
    }

    @Override
    public ListenableFuture<Void> clear() {
      // clear metadata and delete file.
      return sequencer.submitAsync(
          () ->
              PropagatedFutures.transformAsync(
                  metadataStore.delete(onDeviceUri),
                  unused -> {
                    try {
                      fileStorage.deleteFile(onDeviceUri);
                      return immediateVoidFuture();
                    } catch (IOException ioException) {
                      return immediateFailedFuture(ioException);
                    }
                  },
                  lightweightExecutor),
          lightweightExecutor);
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof DownloadDestinationImpl)) {
        return false;
      }

      // Use onDeviceUri to determine equality
      return this.onDeviceUri.equals(((DownloadDestinationImpl) obj).onDeviceUri);
    }

    @Override
    public int hashCode() {
      // Use hashcode of the onDeviceUri
      return this.onDeviceUri.hashCode();
    }
  }

  private final DownloadMetadataStore metadataStore;
  private final Executor lightweightExecutor;

  private DownloadDestinationOpener(
      DownloadMetadataStore metadataStore, Executor lightweightExecutor) {
    this.metadataStore = metadataStore;
    this.lightweightExecutor = lightweightExecutor;
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
        openContext.originalUri(), openContext.storage(), metadataStore, lightweightExecutor);
  }

  public static DownloadDestinationOpener create(
      DownloadMetadataStore metadataStore, Executor lightweightExecutor) {
    return new DownloadDestinationOpener(metadataStore, lightweightExecutor);
  }
}
