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
package com.google.android.libraries.mobiledatadownload.downloader;

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.INLINE_FILE_URL_SCHEME;
import static com.google.common.base.Preconditions.checkArgument;

import android.net.Uri;
import android.util.Pair;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import javax.annotation.concurrent.Immutable;

/** Request to download a file. */
@Immutable
@AutoValue
public abstract class DownloadRequest {

  // Default value for Traffic Tag if not set by clients.
  // MDD will not tag the traffic if the TrafficTag is not set to a valid value (>0).
  private static final int UNSPECIFIED_TRAFFIC_TAG = -1;

  DownloadRequest() {}

  /** The File Uri to download the file at. */
  public abstract Uri fileUri();

  /** The url to download the file from. */
  public abstract String urlToDownload();

  /**
   * Conditions under which this file should be downloaded.
   *
   * <p>These conditions relate to the type of network that should be used when downloading and must
   * be provided when performing a network download.
   *
   * <p>When performing in-memory downloads (using the "inlinefile" url scheme), this will not be
   * used.
   */
  public abstract DownloadConstraints downloadConstraints();

  /**
   * Traffic tag used for this request.
   *
   * <p>If not set, it will take the default value of UNSPECIFIED_TRAFFIC_TAG and MDD will not tag
   * the traffic.
   */
  public abstract int trafficTag();

  /** The extra HTTP headers for this request. */
  public abstract ImmutableList<Pair<String, String>> extraHttpHeaders();

  /**
   * Parameters for inline file downloads.
   *
   * <p>An instance of {@link InlineDownloadParams} must be included in the request to support
   * in-memory downloads (see <internal> for more info on the "inlinefile" url scheme).
   *
   * <p>Implementations of {@link FileDownloader} that support downloading from an inline file
   * should ensure that 1) the "inlinefile" url scheme is used and 2) an {@link
   * InlineDownloadParams} is provided.
   */
  public abstract Optional<InlineDownloadParams> inlineDownloadParamsOptional();

  public static Builder newBuilder() {
    return new AutoValue_DownloadRequest.Builder()
        .setTrafficTag(UNSPECIFIED_TRAFFIC_TAG)
        .setExtraHttpHeaders(ImmutableList.of());
  }

  /** Builder for {@link DownloadRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /** Sets the on-device destination uri of the file. */
    public abstract Builder setFileUri(Uri fileUri);

    /** Sets the url from where file content should be downloaded. */
    public abstract Builder setUrlToDownload(String urlToDownload);

    /**
     * Sets the network constraints that should be used for the download.
     *
     * <p>Only required when performing network downloads. If performing an in-memory download, this
     * is not required.
     */
    public abstract Builder setDownloadConstraints(DownloadConstraints downloadConstraints);

    /** Sets the traffic tag for this request. */
    public abstract Builder setTrafficTag(int trafficTag);

    /** Sets the extra HTTP headers for this request. */
    public abstract Builder setExtraHttpHeaders(
        ImmutableList<Pair<String, String>> extraHttpHeaders);

    /**
     * Sets the parameters for an inline file download.
     *
     * <p>Only required when performing an in-memory download (using an "inlinefile" url scheme). If
     * performing a network download, this is not required.
     */
    public abstract Builder setInlineDownloadParamsOptional(
        InlineDownloadParams inlineDownloadParams);

    /** Builds a {@link DownloadRequest} and checks for correct data. */
    public final DownloadRequest build() {
      // Ensure that inlinefile: requests include InlineDownloadParams
      if (urlToDownload().startsWith(INLINE_FILE_URL_SCHEME)) {
        checkArgument(
            inlineDownloadParamsOptional().isPresent(),
            "InlineDownloadParams must be set when using inlinefile: scheme");

        // inline file request doesn't require download constraints, so set to NONE.
        setDownloadConstraints(DownloadConstraints.NONE);
      }

      return autoBuild();
    }

    /** Tells AutoValue to generate an automated builder used in {@link #build} */
    abstract DownloadRequest autoBuild();

    abstract String urlToDownload();

    abstract Optional<InlineDownloadParams> inlineDownloadParamsOptional();
  }
}
