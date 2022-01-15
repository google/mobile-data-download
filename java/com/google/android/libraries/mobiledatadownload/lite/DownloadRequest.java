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
package com.google.android.libraries.mobiledatadownload.lite;

import android.net.Uri;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

/** Request to download a file. */
@AutoValue
public abstract class DownloadRequest {

  // Default value for Traffic Tag if not set by clients.
  // MDDLite will not tag the traffic if the TrafficTag is not set to a valid value (>0).
  private static final int UNSPECIFIED_TRAFFIC_TAG = -1;

  DownloadRequest() {}

  // The Destination File Uri to download the file at.
  public abstract Uri destinationFileUri();

  // The url to download the file from.
  public abstract String urlToDownload();

  // Conditions under which this file should be downloaded.
  public abstract DownloadConstraints downloadConstraints();

  /** If present, will receive download progress update. */
  public abstract Optional<DownloadListener> listenerOptional();

  // Traffic tag used for this request.
  // If not set, it will take the default value of UNSPECIFIED_TRAFFIC_TAG and MDD will not tag the
  // traffic.
  public abstract int trafficTag();

  // The extra HTTP headers for this request.
  public abstract ImmutableList<Pair<String, String>> extraHttpHeaders();

  // The size of the being downloaded file in bytes.
  // This is used to display the progressbar.
  // If not specified, an indeterminate progressbar will be displayed.
  // https://developer.android.com/reference/android/app/Notification.Builder.html#setProgress(int,%20int,%20boolean)
  public abstract int fileSizeBytes();

  // Used only by Foreground download.
  // The Content Title of the associated Notification for this download.
  public abstract String notificationContentTitle();

  // Used only by Foreground download.
  // If Present, the Content Text (description) of the associated Notification for this download.
  // Otherwise, the Content Text will be the url to download.
  public abstract Optional<String> notificationContentTextOptional();

  // Whether to show the downloaded notification. If false, MDD will automatically remove this
  // notification when the download finished.
  public abstract boolean showDownloadedNotification();

  public static Builder newBuilder() {
    return new AutoValue_DownloadRequest.Builder()
        .setTrafficTag(UNSPECIFIED_TRAFFIC_TAG)
        .setExtraHttpHeaders(ImmutableList.of())
        .setFileSizeBytes(0)
        .setShowDownloadedNotification(true);
  }

  /** Builder for {@link DownloadRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /** Sets the destination file uri. */
    public abstract Builder setDestinationFileUri(Uri fileUri);

    /** Sets the url to download. */
    public abstract Builder setUrlToDownload(String urlToDownload);

    /** Sets the DowloadConstraints. */
    public abstract Builder setDownloadConstraints(DownloadConstraints downloadConstraints);

    /** Sets the optional download listener. If present, will receive download progress update. */
    public abstract Builder setListenerOptional(Optional<DownloadListener> listenerOptional);

    /** Sets the traffic tag for this request. */
    public abstract Builder setTrafficTag(int trafficTag);

    /** Sets the extra HTTP headers for this request. */
    public abstract Builder setExtraHttpHeaders(
        ImmutableList<Pair<String, String>> extraHttpHeaders);

    /**
     * The size of the being downloaded file in bytes. This is used to display the progressbar. If
     * not specified, a indeterminate progressbar will be displayed.
     * https://developer.android.com/reference/android/app/Notification.Builder.html#setProgress(int,%20int,%20boolean)
     */
    public abstract Builder setFileSizeBytes(int fileSizeBytes);

    /** Sets the Notification Content Tile which will be used for foreground download */
    public abstract Builder setNotificationContentTitle(String notificationContentTitle);

    /**
     * Sets the Notification Context Text which will be used for foreground downloads.
     *
     * <p>If not set, the url to download will be used instead.
     */
    public abstract Builder setNotificationContentTextOptional(
        Optional<String> notificationContentTextOptional);

    /**
     * Sets to show Downloaded Notification after the download finished successfully. This is only
     * be used for foreground download. Default value is to show the downloaded notification.
     */
    public abstract Builder setShowDownloadedNotification(boolean showDownloadedNotification);

    /** Builds {@link DownloadRequest}. */
    public final DownloadRequest build() {
      // If notification content title is not provided, use urlToDownload as a fallback
      if (!notificationContentTitle().isPresent()) {
        setNotificationContentTitle(urlToDownload());
      }
      // Use AutoValue's generated build to finish building.
      return autoBuild();
    }

    // private getter generated by AutoValue for access in build().
    abstract String urlToDownload();

    // private getter generated by AutoValue for access in build().
    abstract Optional<String> notificationContentTitle();

    // private build method to be generated by AutoValue.
    abstract DownloadRequest autoBuild();
  }
}
