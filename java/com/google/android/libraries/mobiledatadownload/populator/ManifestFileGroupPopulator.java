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
package com.google.android.libraries.mobiledatadownload.populator;

import static com.google.android.libraries.mobiledatadownload.tracing.TracePropagation.propagateAsyncCallable;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.VisibleForTesting;
import com.google.mobiledatadownload.populator.MetadataProto.ManifestFileBookkeeping;
import com.google.mobiledatadownload.populator.MetadataProto.ManifestFileBookkeeping.Status;
import com.google.android.libraries.mobiledatadownload.AggregateException;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.FileGroupPopulator;
import com.google.android.libraries.mobiledatadownload.Flags;
import com.google.android.libraries.mobiledatadownload.Logger;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.android.libraries.mobiledatadownload.downloader.CheckContentChangeRequest;
import com.google.android.libraries.mobiledatadownload.downloader.CheckContentChangeResponse;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.downloader.FileDownloader;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.DirectoryUtil;
import com.google.android.libraries.mobiledatadownload.logger.FileGroupPopulatorLogger;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFluentFuture;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ExecutionSequencer;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestFileFlag;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.inject.Singleton;

/**
 * File group populator that gets {@link ManifestFileFlag} from the caller, downloads the
 * corresponding manifest file, parses the file into {@link ManifestConfig}, and processes {@link
 * ManifestConfig}.
 *
 * <p>Client can set an optional {@link ManifestConfigOverrider} to return a list of {@link
 * DataFileGroup}'s to be added to MDD. The overrider will enable the on device targeting.
 *
 * <p>Client is responsible of reading {@link ManifestFileFlag} from P/H, and this populator would
 * get the flag via {@link Supplier<ManifestFileFlag>}.
 *
 * <p>On calling {@link #refreshFileGroups(MobileDataDownload)}, this populator would sync up with
 * server to verify if the manifest file on server has changed since last download. It would
 * re-download the file if a newer version is available. More specifically, there are 3 scenarios:
 *
 * <ul>
 *   <li>1. Current file up-to-date, status PENDING. Resume download.
 *   <li>2. Current file up-to-date, status (DOWNLOADED | COMMITTED). No download will happen.
 *   <li>3. Current file outdated. Delete the outdated file and re-download.
 * </ul>
 *
 * <p>To ensure that each time we download the most up-to-date manifest file correctly, we will
 * check for {@link FileDownloader#isContentChanged(CheckContentChangeRequest)} twice:
 *
 * <ul>
 *   <li>1. Before the download to check if the new download is necessary.
 *   <li>2. After the download to make sure that the content is not out of date.
 * </ul>
 *
 * <p>Note that the current prerequisite of using {@link ManifestFileGroupPopulator} is that, the
 * hosting service needs to support ETag (e.g. Lorry), otherwise the behavior will be unexpected.
 * Talk to <internal>@ if you are not sure if the hosting service supports ETag.
 *
 * <p>Note that {@link SynchronousFileStorage} and {@link ProtoDataStoreFactory} passed to builder
 * must be @Singleton.
 *
 * <p>This class is @Singleton, because it provides the guarantee that all the operations are
 * serialized correctly by {@link ExecutionSequencer}.
 */
@Singleton
public final class ManifestFileGroupPopulator implements FileGroupPopulator {

  private static final String TAG = "ManifestFileGroupPopulator";

  /** The parser of the manifest file. */
  public interface ManifestConfigParser {

    /** Parses the input file and returns the {@link ManifestConfig}. */
    ListenableFuture<ManifestConfig> parse(Uri fileUri);
  }

  /** Builder for {@link ManifestFileGroupPopulator}. */
  public static final class Builder {
    private boolean allowsInsecureHttp = false;
    private boolean dedupDownloadWithEtag = true;
    private Context context;
    private Supplier<ManifestFileFlag> manifestFileFlagSupplier;
    private Supplier<FileDownloader> fileDownloader;
    private ManifestConfigParser manifestConfigParser;
    private SynchronousFileStorage fileStorage;
    private Executor backgroundExecutor;
    private ManifestFileMetadataStore manifestFileMetadataStore;
    private Logger logger;
    private Optional<ManifestConfigOverrider> overriderOptional = Optional.absent();
    private Optional<String> instanceIdOptional = Optional.absent();
    private Flags flags = new Flags() {};

    /**
     * Sets the flag that allows insecure http.
     *
     * <p>For testing only.
     */
    @VisibleForTesting
    Builder setAllowsInsecureHttp(boolean allowsInsecureHttp) {
      this.allowsInsecureHttp = allowsInsecureHttp;
      return this;
    }

    /**
     * By default, an HTTP HEAD request is made to avoid duplicate downloads of the manifest file.
     * Setting this to false disables that behavior.
     */
    public Builder setDedupDownloadWithEtag(boolean dedup) {
      this.dedupDownloadWithEtag = dedup;
      return this;
    }

    /** Sets the context. */
    public Builder setContext(Context context) {
      this.context = context.getApplicationContext();
      return this;
    }

    /** Sets the manifest file flag. */
    public Builder setManifestFileFlagSupplier(
        Supplier<ManifestFileFlag> manifestFileFlagSupplier) {
      this.manifestFileFlagSupplier = manifestFileFlagSupplier;
      return this;
    }

    /** Sets the file downloader. */
    public Builder setFileDownloader(Supplier<FileDownloader> fileDownloader) {
      this.fileDownloader = fileDownloader;
      return this;
    }

    /** Sets the manifest config parser that takes file uri and returns {@link ManifestConfig}. */
    public Builder setManifestConfigParser(ManifestConfigParser manifestConfigParser) {
      this.manifestConfigParser = manifestConfigParser;
      return this;
    }

    /** Sets the mobstore file storage. Mobstore file storage must be singleton. */
    public Builder setFileStorage(SynchronousFileStorage fileStorage) {
      this.fileStorage = fileStorage;
      return this;
    }

    /** Sets the background executor that executes populator's tasks sequentially. */
    public Builder setBackgroundExecutor(Executor backgroundExecutor) {
      this.backgroundExecutor = backgroundExecutor;
      return this;
    }

    /** Sets the ManifestFileMetadataStore. */
    public Builder setMetadataStore(ManifestFileMetadataStore manifestFileMetadataStore) {
      this.manifestFileMetadataStore = manifestFileMetadataStore;
      return this;
    }

    /** Sets the MDD logger. */
    public Builder setLogger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /** Sets the optional manifest config overrider. */
    public Builder setOverriderOptional(Optional<ManifestConfigOverrider> overriderOptional) {
      this.overriderOptional = overriderOptional;
      return this;
    }

    /** Sets the optional instance ID. */
    public Builder setInstanceIdOptional(Optional<String> instanceIdOptional) {
      this.instanceIdOptional = instanceIdOptional;
      return this;
    }

    public Builder setFlags(Flags flags) {
      this.flags = flags;
      return this;
    }

    public ManifestFileGroupPopulator build() {
      Preconditions.checkNotNull(context, "Must call setContext() before build().");
      Preconditions.checkNotNull(
          manifestFileFlagSupplier, "Must call setManifestFileFlagSupplier() before build().");
      Preconditions.checkNotNull(fileDownloader, "Must call setFileDownloader() before build().");
      Preconditions.checkNotNull(
          manifestConfigParser, "Must call setManifestConfigParser() before build().");
      Preconditions.checkNotNull(fileStorage, "Must call setFileStorage() before build().");
      Preconditions.checkNotNull(
          backgroundExecutor, "Must call setBackgroundExecutor() before build().");
      Preconditions.checkNotNull(
          manifestFileMetadataStore, "Must call manifestFileMetadataStore() before build().");
      Preconditions.checkNotNull(logger, "Must call setLogger() before build().");
      return new ManifestFileGroupPopulator(this);
    }
  }

  private final boolean allowsInsecureHttp;
  private final boolean dedupDownloadWithEtag;
  private final Context context;
  private final Uri manifestDirectoryUri;
  private final Supplier<ManifestFileFlag> manifestFileFlagSupplier;
  private final Supplier<FileDownloader> fileDownloader;
  private final ManifestConfigParser manifestConfigParser;
  private final SynchronousFileStorage fileStorage;
  private final Executor backgroundExecutor;
  private final Optional<ManifestConfigOverrider> overriderOptional;
  private final ManifestFileMetadataStore manifestFileMetadataStore;
  private final FileGroupPopulatorLogger eventLogger;
  // We use futureSerializer for synchronization.
  private final ExecutionSequencer futureSerializer = ExecutionSequencer.create();

  /** Returns a Builder for {@link ManifestFileGroupPopulator}. */
  public static Builder builder() {
    return new Builder();
  }

  private ManifestFileGroupPopulator(Builder builder) {
    this.allowsInsecureHttp = builder.allowsInsecureHttp;
    this.dedupDownloadWithEtag = builder.dedupDownloadWithEtag;
    this.context = builder.context;
    this.manifestDirectoryUri =
        DirectoryUtil.getManifestDirectory(builder.context, builder.instanceIdOptional);
    this.manifestFileFlagSupplier = builder.manifestFileFlagSupplier;
    this.fileDownloader = builder.fileDownloader;
    this.manifestConfigParser = builder.manifestConfigParser;
    this.fileStorage = builder.fileStorage;
    this.backgroundExecutor = builder.backgroundExecutor;
    this.overriderOptional = builder.overriderOptional;
    this.eventLogger = new FileGroupPopulatorLogger(builder.logger, builder.flags);
    this.manifestFileMetadataStore = builder.manifestFileMetadataStore;
  }

  @Override
  public ListenableFuture<Void> refreshFileGroups(MobileDataDownload mobileDataDownload) {
    return futureSerializer.submitAsync(
        propagateAsyncCallable(
            () -> {
              LogUtil.d("%s: Add groups from ManifestFileFlag to MDD.", TAG);

              // We will return immediately if the flag is null or empty. This could happen if P/H
              // has not synced the flag or we fail to parse the flag.
              ManifestFileFlag manifestFileFlag = manifestFileFlagSupplier.get();
              if (manifestFileFlag == null
                  || manifestFileFlag.equals(ManifestFileFlag.getDefaultInstance())) {
                LogUtil.w("%s: The ManifestFileFlag is empty.", TAG);
                logRefreshResult(0, ManifestFileFlag.getDefaultInstance());
                return immediateVoidFuture();
              }

              return refreshFileGroups(mobileDataDownload, manifestFileFlag);
            }),
        backgroundExecutor);
  }

  private ListenableFuture<Void> refreshFileGroups(
      MobileDataDownload mobileDataDownload, ManifestFileFlag manifestFileFlag) {

    if (!validate(manifestFileFlag)) {
      logRefreshResult(0, manifestFileFlag);
      LogUtil.e("%s: Invalid manifest config from manifest flag.", TAG);
      return immediateFailedFuture(new IllegalArgumentException("Invalid manifest flag."));
    }

    String manifestFileUrl = manifestFileFlag.getManifestFileUrl();

    // Manifest files are named and identified with their manifest ID.
    Uri manifestFileUri =
        manifestDirectoryUri.buildUpon().appendPath(manifestFileFlag.getManifestId()).build();

    // Represents the internal state of the metadata. Using AtomicReference here because the
    // variable captured by lambda needs to be final.
    final AtomicReference<ManifestFileBookkeeping> bookkeepingRef =
        new AtomicReference<>(createDefaultManifestFileBookkeeping(manifestFileUrl));

    ListenableFuture<Void> checkFuture =
        PropagatedFluentFuture.from(readBookeeping(manifestFileFlag.getManifestId()))
            .transform(
                (final Optional<ManifestFileBookkeeping> bookkeepingOptional) -> {
                  if (bookkeepingOptional.isPresent()) {
                    bookkeepingRef.set(bookkeepingOptional.get());
                  }
                  return (Void) null;
                },
                backgroundExecutor)
            .transformAsync(
                voidArg ->
                    // We need to call checkForContentChangeBeforeDownload to sync back the latest
                    // ETag, even when there is no entry for bookkeeping.
                    checkForContentChangeBeforeDownload(
                        manifestFileUrl, manifestFileUri, bookkeepingRef),
                backgroundExecutor);

    ListenableFuture<Optional<Throwable>> transformCheckFuture =
        PropagatedFluentFuture.from(checkFuture)
            .transform(voidArg -> Optional.<Throwable>absent(), backgroundExecutor)
            .catching(Throwable.class, Optional::of, backgroundExecutor);

    ListenableFuture<Void> processFuture =
        PropagatedFluentFuture.from(transformCheckFuture)
            .transformAsync(
                (final Optional<Throwable> throwableOptional) -> {
                  // We do not want to proceed if transformCheckFuture contains failures, so return
                  // early.
                  if (throwableOptional.isPresent()) {
                    return immediateVoidFuture();
                  }

                  ManifestFileBookkeeping bookkeeping = bookkeepingRef.get();

                  if (bookkeeping.getStatus() == Status.COMMITTED) {
                    LogUtil.d("%s: Manifest file was committed.", TAG);
                    if (!overriderOptional.isPresent()) {
                      return immediateVoidFuture();
                    }

                    // When the overrider is present, it may produce different configs each time the
                    // caller triggers refresh. Therefore, we need to recommit to MDD.
                    LogUtil.d("%s: Overrider is present, commit again.", TAG);
                    return parseAndCommitManifestFile(
                        mobileDataDownload, manifestFileUri, bookkeepingRef);
                  }

                  if (bookkeeping.getStatus() == Status.DOWNLOADED) {
                    LogUtil.d("%s: Manifest file was downloaded.", TAG);
                    return parseAndCommitManifestFile(
                        mobileDataDownload, manifestFileUri, bookkeepingRef);
                  }

                  return PropagatedFluentFuture.from(
                          downloadManifestFile(manifestFileUrl, manifestFileUri))
                      .transformAsync(
                          voidArgInner ->
                              checkForContentChangeAfterDownload(
                                  manifestFileUrl, manifestFileUri, bookkeepingRef),
                          backgroundExecutor)
                      .transformAsync(
                          voidArgInner ->
                              parseAndCommitManifestFile(
                                  mobileDataDownload, manifestFileUri, bookkeepingRef),
                          backgroundExecutor);
                },
                backgroundExecutor);

    ListenableFuture<Void> catchingProcessFuture =
        PropagatedFutures.catchingAsync(
            processFuture,
            Throwable.class,
            (Throwable unused) -> {
              ManifestFileBookkeeping bookkeeping = bookkeepingRef.get();
              bookkeepingRef.set(bookkeeping.toBuilder().setStatus(Status.PENDING).build());
              deleteManifestFileChecked(manifestFileUri);
              return immediateVoidFuture();
            },
            backgroundExecutor);

    ListenableFuture<Void> updateFuture =
        PropagatedFutures.transformAsync(
            catchingProcessFuture,
            voidArg -> writeBookkeeping(manifestFileFlag.getManifestId(), bookkeepingRef.get()),
            backgroundExecutor);

    return PropagatedFutures.transformAsync(
        updateFuture,
        voidArg -> {
          logAndThrowIfFailed(
              ImmutableList.of(checkFuture, processFuture, updateFuture),
              "Failed to refresh file groups",
              manifestFileFlag);
          // If there is any failure, it should have been thrown already. Therefore, we log refresh
          // success here.
          logRefreshResult(0, manifestFileFlag);
          return immediateVoidFuture();
        },
        backgroundExecutor);
  }

  private boolean validate(@Nullable ManifestFileFlag manifestFileFlag) {
    if (manifestFileFlag == null) {
      return false;
    }
    if (!manifestFileFlag.hasManifestId() || manifestFileFlag.getManifestId().isEmpty()) {
      return false;
    }
    if (!manifestFileFlag.hasManifestFileUrl()
        || (!allowsInsecureHttp && !manifestFileFlag.getManifestFileUrl().startsWith("https"))) {
      return false;
    }
    return true;
  }

  private ListenableFuture<Void> parseAndCommitManifestFile(
      MobileDataDownload mobileDataDownload,
      Uri manifestFileUri,
      AtomicReference<ManifestFileBookkeeping> bookkeepingRef) {
    return PropagatedFluentFuture.from(parseManifestFile(manifestFileUri))
        .transformAsync(
            (final ManifestConfig manifestConfig) ->
                ManifestConfigHelper.refreshFromManifestConfig(
                    mobileDataDownload, manifestConfig, overriderOptional),
            backgroundExecutor)
        .transformAsync(
            voidArg -> {
              ManifestFileBookkeeping bookkeeping = bookkeepingRef.get();
              bookkeepingRef.set(bookkeeping.toBuilder().setStatus(Status.COMMITTED).build());
              return immediateVoidFuture();
            },
            backgroundExecutor);
  }

  private ListenableFuture<Void> downloadManifestFile(String urlToDownload, Uri destinationUri) {
    LogUtil.d(
        "%s: Start downloading the manifest file from %s to %s.",
        TAG, urlToDownload, destinationUri.toString());

    // We now download manifest file on any network (similar to P/H). In the future, we may want to
    // restrict the download only on WiFi, and need to introduce network policy. (However, some
    // users are never on WiFi)
    //
    // Note: Right now, if the download of manifest config file is set to WiFi only but this
    // populator is triggered in CELLULAR_CHARGING task, then the downloading will be blocked.
    DownloadConstraints downloadConstraints = DownloadConstraints.NETWORK_CONNECTED;

    return fileDownloader
        .get()
        .startDownloading(
            DownloadRequest.newBuilder()
                .setUrlToDownload(urlToDownload)
                .setFileUri(destinationUri)
                .setDownloadConstraints(downloadConstraints)
                .build());
  }

  private ListenableFuture<ManifestConfig> parseManifestFile(Uri manifestFileUri) {
    LogUtil.d("%s: Parse the manifest file at %s.", TAG, manifestFileUri);

    ListenableFuture<ManifestConfig> parseFuture = manifestConfigParser.parse(manifestFileUri);
    return DownloadException.wrapIfFailed(
        parseFuture,
        DownloadResultCode.MANIFEST_FILE_GROUP_POPULATOR_PARSE_MANIFEST_FILE_ERROR,
        "Failed to parse the manifest file.");
  }

  private ListenableFuture<Void> checkForContentChangeBeforeDownload(
      String urlToDownload,
      Uri manifestFileUri,
      AtomicReference<ManifestFileBookkeeping> bookkeepingRef) {
    LogUtil.d("%s: Prepare for downloading manifest file.", TAG);

    if (!dedupDownloadWithEtag) {
      return immediateVoidFuture();
    }

    ManifestFileBookkeeping bookkeeping = bookkeepingRef.get();

    ListenableFuture<CheckContentChangeResponse> isContentChangedFuture =
        fileDownloader
            .get()
            .isContentChanged(
                CheckContentChangeRequest.newBuilder()
                    .setUrl(urlToDownload)
                    .setCachedETagOptional(getCachedETag(bookkeeping))
                    .build());

    return PropagatedFutures.transformAsync(
        isContentChangedFuture,
        (final CheckContentChangeResponse response) -> {
          Status currentStatus = bookkeepingRef.get().getStatus();

          // If the manifest file on server side has been modified since last download, then the
          // manifest file previously downloaded is now stale. We need to delete it and re-download
          // the latest version.
          //
          // In case of url changes, we still want to send the network request to fetch the ETag.
          boolean urlUpdated = !urlToDownload.equals(bookkeeping.getManifestFileUrl());
          if (urlUpdated || response.contentChanged()) {
            LogUtil.d(
                "%s: Manifest file on server updated, will re-download; urlToDownload = %s;"
                    + " manifestFileUri = %s",
                TAG, urlToDownload, manifestFileUri);
            currentStatus = Status.PENDING;
            deleteManifestFileChecked(manifestFileUri);
          }

          bookkeepingRef.set(
              createManifestFileBookkeeping(
                  urlToDownload, currentStatus, response.freshETagOptional()));

          return immediateVoidFuture();
        },
        backgroundExecutor);
  }

  private ListenableFuture<Void> checkForContentChangeAfterDownload(
      String urlToDownload,
      Uri manifestFileUri,
      AtomicReference<ManifestFileBookkeeping> bookkeepingRef) {
    LogUtil.d("%s: Finalize for downloading manifest file.", TAG);

    if (!dedupDownloadWithEtag) {
      return immediateVoidFuture();
    }

    ManifestFileBookkeeping bookkeeping = bookkeepingRef.get();

    ListenableFuture<CheckContentChangeResponse> isContentChangedFuture =
        fileDownloader
            .get()
            .isContentChanged(
                CheckContentChangeRequest.newBuilder()
                    .setUrl(urlToDownload)
                    .setCachedETagOptional(getCachedETag(bookkeeping))
                    .build());

    return PropagatedFutures.transformAsync(
        isContentChangedFuture,
        (final CheckContentChangeResponse response) -> {
          // If the manifest file on server has changed during download. The manifest file we just
          // downloaded is stale during the download.
          if (response.contentChanged()) {
            LogUtil.e(
                "%s: Manifest file on server changed during download, download failed;"
                    + " urlToDownload = %s; manifestFileUri = %s",
                TAG, urlToDownload, manifestFileUri);
            return immediateFailedFuture(
                DownloadException.builder()
                    .setDownloadResultCode(
                        DownloadResultCode
                            .MANIFEST_FILE_GROUP_POPULATOR_CONTENT_CHANGED_DURING_DOWNLOAD_ERROR)
                    .setMessage("Manifest file on server changed during download.")
                    .build());
          }

          bookkeepingRef.set(
              createManifestFileBookkeeping(
                  urlToDownload, Status.DOWNLOADED, response.freshETagOptional()));

          return immediateVoidFuture();
        },
        backgroundExecutor);
  }

  private ListenableFuture<Optional<ManifestFileBookkeeping>> readBookeeping(String manifestId) {
    return DownloadException.wrapIfFailed(
        manifestFileMetadataStore.read(manifestId),
        DownloadResultCode.MANIFEST_FILE_GROUP_POPULATOR_METADATA_IO_ERROR,
        "Failed to read bookkeeping.");
  }

  private ListenableFuture<Void> writeBookkeeping(
      String manifestId, ManifestFileBookkeeping value) {
    return DownloadException.wrapIfFailed(
        manifestFileMetadataStore.upsert(manifestId, value),
        DownloadResultCode.MANIFEST_FILE_GROUP_POPULATOR_METADATA_IO_ERROR,
        "Failed to write bookkeeping.");
  }

  private void deleteManifestFileChecked(Uri manifestFileUri) throws DownloadException {
    try {
      deleteManifestFile(manifestFileUri);
    } catch (IOException e) {
      throw DownloadException.builder()
          .setCause(e)
          .setDownloadResultCode(
              DownloadResultCode.MANIFEST_FILE_GROUP_POPULATOR_DELETE_MANIFEST_FILE_ERROR)
          .setMessage("Failed to delete manifest file.")
          .build();
    }
  }

  private void deleteManifestFile(Uri manifestFileUri) throws IOException {
    if (fileStorage.exists(manifestFileUri)) {
      LogUtil.d("%s: Removing manifest file at: %s", TAG, manifestFileUri);
      fileStorage.deleteFile(manifestFileUri);
    } else {
      LogUtil.d("%s: Manifest file doesn't exist: %s", TAG, manifestFileUri);
    }
  }

  private void logRefreshResult(DownloadException e, ManifestFileFlag manifestFileFlag) {
    eventLogger.logManifestFileGroupPopulatorRefreshResult(
        0,
        manifestFileFlag.getManifestId(),
        context.getPackageName(),
        manifestFileFlag.getManifestFileUrl());
  }

  private void logRefreshResult(int code, ManifestFileFlag manifestFileFlag) {
    eventLogger.logManifestFileGroupPopulatorRefreshResult(
        code,
        manifestFileFlag.getManifestId(),
        context.getPackageName(),
        manifestFileFlag.getManifestFileUrl());
  }

  private void logAndThrowIfFailed(
      ImmutableList<ListenableFuture<Void>> futures,
      String message,
      ManifestFileFlag manifestFileFlag)
      throws AggregateException {
    FutureCallback<Void> logRefreshResultCallback =
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void unused) {}

          @Override
          public void onFailure(Throwable t) {
            if (t instanceof DownloadException) {
              logRefreshResult((DownloadException) t, manifestFileFlag);
            } else {
              // Here, we encountered an error that is unchecked. If UNKNOWN_ERROR is observed, we
              // will need to investigate the cause and have it checked.
              logRefreshResult(
                  DownloadException.builder()
                      .setCause(t)
                      .setDownloadResultCode(DownloadResultCode.UNKNOWN_ERROR)
                      .setMessage("Refresh failed.")
                      .build(),
                  manifestFileFlag);
            }
          }
        };
    AggregateException.throwIfFailed(futures, Optional.of(logRefreshResultCallback), message);
  }

  private static ManifestFileBookkeeping createDefaultManifestFileBookkeeping(
      String manifestFileUrl) {
    return createManifestFileBookkeeping(
        manifestFileUrl, Status.PENDING, /* eTagOptional = */ Optional.absent());
  }

  private static ManifestFileBookkeeping createManifestFileBookkeeping(
      String manifestFileUrl, Status status, Optional<String> eTagOptional) {
    ManifestFileBookkeeping.Builder bookkeeping =
        ManifestFileBookkeeping.newBuilder().setManifestFileUrl(manifestFileUrl).setStatus(status);
    if (eTagOptional.isPresent()) {
      bookkeeping.setCachedEtag(eTagOptional.get());
    }
    return bookkeeping.build();
  }

  private static Optional<String> getCachedETag(ManifestFileBookkeeping bookkeeping) {
    return bookkeeping.hasCachedEtag()
        ? Optional.of(bookkeeping.getCachedEtag())
        : Optional.absent();
  }
}
