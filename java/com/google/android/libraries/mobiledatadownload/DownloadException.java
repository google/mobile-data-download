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

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;

import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Thrown when there is a download failure. */
public final class DownloadException extends Exception {
  /** This error code is a representation of {@code MddDownloadResult.Code}. */
  private final DownloadResultCode downloadResultCode;

  /**
   * This is the result of calling download, which should be identical to {@code
   * MddDownloadResult.Code}.
   */
  // LINT.IfChange
  public enum DownloadResultCode {
    UNSPECIFIED(0), // unset value

    // File downloaded successfully.
    // This is just a placeholder, we currently don't log for success case.
    SUCCESS(1),

    // The error we don't know.
    UNKNOWN_ERROR(2),

    // The errors from the android downloader outside MDD, which comes from:
    // <internal>
    ANDROID_DOWNLOADER_UNKNOWN(100),
    ANDROID_DOWNLOADER_CANCELED(101),
    ANDROID_DOWNLOADER_INVALID_REQUEST(102),
    ANDROID_DOWNLOADER_HTTP_ERROR(103),
    ANDROID_DOWNLOADER_REQUEST_ERROR(104),
    ANDROID_DOWNLOADER_RESPONSE_OPEN_ERROR(105),
    ANDROID_DOWNLOADER_RESPONSE_CLOSE_ERROR(106),
    ANDROID_DOWNLOADER_NETWORK_IO_ERROR(107),
    ANDROID_DOWNLOADER_DISK_IO_ERROR(108),
    ANDROID_DOWNLOADER_FILE_SYSTEM_ERROR(109),
    ANDROID_DOWNLOADER_UNKNOWN_IO_ERROR(110),
    ANDROID_DOWNLOADER_OAUTH_ERROR(111),

    // The errors from the android downloader v2 outside MDD, which comes from:
    // <internal>
    ANDROID_DOWNLOADER2_ERROR(200),

    // The data file group has not been added to MDD by the time the caller
    // makes download API call.
    GROUP_NOT_FOUND_ERROR(300),

    // The DownloadListener is present but the DownloadMonitor is not provided.
    DOWNLOAD_MONITOR_NOT_PROVIDED_ERROR(301),

    // Errors from unsatisfied download preconditions.
    INSECURE_URL_ERROR(302),
    LOW_DISK_ERROR(303),

    // Errors from download preparation.
    UNABLE_TO_CREATE_FILE_URI_ERROR(304),
    SHARED_FILE_NOT_FOUND_ERROR(305),
    MALFORMED_FILE_URI_ERROR(306),
    UNABLE_TO_CREATE_MOBSTORE_RESPONSE_WRITER_ERROR(307),

    // Errors from file validation.
    UNABLE_TO_VALIDATE_DOWNLOAD_FILE_ERROR(308),
    DOWNLOADED_FILE_NOT_FOUND_ERROR(309),
    DOWNLOADED_FILE_CHECKSUM_MISMATCH_ERROR(310),
    CUSTOM_FILEGROUP_VALIDATION_FAILED(330),

    // Errors from download transforms.
    UNABLE_TO_SERIALIZE_DOWNLOAD_TRANSFORM_ERROR(311),
    DOWNLOAD_TRANSFORM_IO_ERROR(312),
    FINAL_FILE_CHECKSUM_MISMATCH_ERROR(313),

    // Errors from delta download.
    DELTA_DOWNLOAD_BASE_FILE_NOT_FOUND_ERROR(314),
    DELTA_DOWNLOAD_DECODE_IO_ERROR(315),

    // The error occurs after the file is ready.
    UNABLE_TO_UPDATE_FILE_STATE_ERROR(316),

    // Fail to update the file group metadata.
    UNABLE_TO_UPDATE_GROUP_METADATA_ERROR(317),

    // Errors from sharing files with the blob storage.
    // Failed to update the metadata max_expiration_date.
    UNABLE_TO_UPDATE_FILE_MAX_EXPIRATION_DATE(318),
    // Failed to share the file before SharedFileManager.startDownload is called.
    UNABLE_SHARE_FILE_BEFORE_DOWNLOAD_ERROR(319),
    // Failed to share the file after SharedFileManager.startDownload is called.
    UNABLE_SHARE_FILE_AFTER_DOWNLOAD_ERROR(320),

    // Download errors related to isolated file structure
    UNABLE_TO_REMOVE_SYMLINK_STRUCTURE(321),
    UNABLE_TO_CREATE_SYMLINK_STRUCTURE(322),

    // Download errors related to importing inline files
    // Failed to reserve file entries
    UNABLE_TO_RESERVE_FILE_ENTRY(323),
    // Invalid use of inlinefile url scheme
    INVALID_INLINE_FILE_URL_SCHEME(324),
    // Error performing inline file download
    INLINE_FILE_IO_ERROR(327),
    // Missing required inline download parms in FileDownloader's DownloadRequest
    MISSING_INLINE_DOWNLOAD_PARAMS(328),
    // Missing required inline file source in ImportFilesRequest
    MISSING_INLINE_FILE_SOURCE(329),

    // Download errors related to URL parsing
    MALFORMED_DOWNLOAD_URL(325),
    UNSUPPORTED_DOWNLOAD_URL_SCHEME(326),

    // Download errors for manifest file group populator.
    MANIFEST_FILE_GROUP_POPULATOR_INVALID_FLAG_ERROR(400),
    MANIFEST_FILE_GROUP_POPULATOR_CONTENT_CHANGED_DURING_DOWNLOAD_ERROR(401),
    MANIFEST_FILE_GROUP_POPULATOR_PARSE_MANIFEST_FILE_ERROR(402),
    MANIFEST_FILE_GROUP_POPULATOR_DELETE_MANIFEST_FILE_ERROR(403),
    MANIFEST_FILE_GROUP_POPULATOR_METADATA_IO_ERROR(404),

    // GDD specific download errors, reserved from 2000-2999.
    GDD_INVALID_ACCOUNT(2000),
    GDD_INVALID_AUTH_TOKEN(2001),
    GDD_FAIL_IN_SYNC_RUNNER(2002),
    GDD_INVALID_ELEMENT_COMBINATION_RECEIVED(2003),
    GDD_INVALID_INLINE_PAYLOAD_ELEMENT_DATA(2004),
    GDD_INVALID_CURRENT_ACTIVE_ELEMENT_DATA(2005),
    GDD_INVALID_NEXT_PENDING_ELEMENT_DATA(2006),
    GDD_CURRENT_ACTIVE_GROUP_HAS_NO_INLINE_FILE(2007),
    GDD_FAIL_TO_ADD_NEXT_PENDING_GROUP(2008),
    GDD_MISSING_ACCOUNT_FOR_PRIVATE_SYNC(2009),
    GDD_FAIL_IN_SYNC_RUNNER_PUBLIC(2010),
    GDD_FAIL_IN_SYNC_RUNNER_PRIVATE(2011),
    GDD_PUBLIC_SYNC_SUCCESS(2012),
    GDD_PRIVATE_SYNC_SUCCESS(2013),
    GDD_FAIL_TO_RETRIEVE_ZWIEBACK_TOKEN(2014);

    private final int code;

    DownloadResultCode(int code) {
      this.code = code;
    }

    /** Returns the int code corresponding to this enum value. */
    public int getCode() {
      return code;
    }
  }
  // LINT.ThenChange(<internal>)

  /** Builder for {@link DownloadException}. */
  public static final class Builder {
    private DownloadResultCode downloadResultCode;
    private String message;
    private Throwable cause;

    /** Sets the {@link DownloadResultCode}. */
    @CanIgnoreReturnValue
    public Builder setDownloadResultCode(DownloadResultCode downloadResultCode) {
      this.downloadResultCode = downloadResultCode;
      return this;
    }

    /** Sets the error message. */
    @CanIgnoreReturnValue
    public Builder setMessage(String message) {
      this.message = message;
      return this;
    }

    /** Sets the cause of the exception. */
    @CanIgnoreReturnValue
    public Builder setCause(Throwable cause) {
      this.cause = cause;
      return this;
    }

    /** Returns a {@link DownloadException} instance. */
    public DownloadException build() {
      Preconditions.checkNotNull(downloadResultCode);
      if (message == null) {
        message = "Download result code: " + downloadResultCode.name();
      }
      return new DownloadException(this);
    }
  }

  /** Returns a Builder for {@link DownloadException}. */
  public static Builder builder() {
    return new Builder();
  }

  public DownloadResultCode getDownloadResultCode() {
    return downloadResultCode;
  }

  /**
   * Wraps the throwable with {@link DownloadException} and returns a failed future only if the
   * input future fails.
   */
  public static <T> ListenableFuture<T> wrapIfFailed(
      ListenableFuture<T> future, DownloadResultCode code, String message) {
    return PropagatedFutures.catchingAsync(
        future,
        Throwable.class,
        (Throwable t) -> immediateFailedFuture(wrap(t, code, message)),
        MoreExecutors.directExecutor());
  }

  /** Wraps the throwable with {@link DownloadException}. */
  private static DownloadException wrap(
      Throwable throwable, DownloadResultCode code, String message) {
    return DownloadException.builder()
        .setDownloadResultCode(code)
        .setMessage(message)
        .setCause(throwable)
        .build();
  }

  private DownloadException(Builder builder) {
    super(builder.message, builder.cause);
    this.downloadResultCode = builder.downloadResultCode;
  }
}
