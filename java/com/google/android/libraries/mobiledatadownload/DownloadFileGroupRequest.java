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

import android.accounts.Account;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.mobiledatadownload.DownloadConfigProto.DownloadConditions;
import javax.annotation.concurrent.Immutable;

/** Request to download file group in MDD. */
@AutoValue
@Immutable
public abstract class DownloadFileGroupRequest {

  /** Defines notifiction behavior for foreground download requests. */
  // LINT.IfChange(show_notifications)
  public enum ShowNotifications {
    NONE,
    ALL,
  }
  // LINT.ThenChange(<internal>)

  DownloadFileGroupRequest() {}

  public abstract String groupName();

  public abstract Optional<Account> accountOptional();

  public abstract Optional<String> variantIdOptional();

  /**
   * If present, title text to display in notification when using foreground downloads. Otherwise,
   * the file group name will be used.
   *
   * <p>See <internal> for an example of the notification.
   */
  public abstract Optional<String> contentTitleOptional();

  /**
   * If present, content text to display in notification when using foreground downloads. Otherwise,
   * the file group name will be used.
   *
   * <p>See <internal> for an example of the notification.
   */
  public abstract Optional<String> contentTextOptional();

  /**
   * The conditions for the download. If absent, MDD will use the download conditions from the
   * server config.
   */
  public abstract Optional<DownloadConditions> downloadConditionsOptional();

  /** If present, will receive download progress update. */
  public abstract Optional<DownloadListener> listenerOptional();

  // The size of the being downloaded file in bytes.
  // This is used to display the progressbar.
  // If not specified, an indeterminate progressbar will be displayed.
  // https://developer.android.com/reference/android/app/Notification.Builder.html#setProgress(int,%20int,%20boolean)
  public abstract int groupSizeBytes();

  /**
   * If {@link ShowNotifications.NONE}, will not create notifications for this foreground download
   * request.
   */
  public abstract ShowNotifications showNotifications();

  public abstract boolean preserveZipDirectories();

  public static Builder newBuilder() {
    return new AutoValue_DownloadFileGroupRequest.Builder()
        .setGroupSizeBytes(0)
        .setShowNotifications(ShowNotifications.ALL)
        .setPreserveZipDirectories(false);
  }

  /** Builder for {@link DownloadFileGroupRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /** Sets the name of the file group. */
    public abstract Builder setGroupName(String groupName);

    /** Sets the optional account that is associated to the file group. */
    public abstract Builder setAccountOptional(Optional<Account> accountOptional);

    /**
     * Sets the variant id that is associated to the file group.
     *
     * <p>This parameter is only required to download a group that was added to MDD with a variantId
     * specified (see {@link AddFileGroupRequest.Builder#setVariantIdOptional}).
     *
     * <p>If a variantId was specified when adding the group to MDD and is not included here, the
     * request will fail with a {@link DownloadException} and a GROUP_NOT_FOUND result code.
     *
     * <p>Similarly, if a variantId was <em>not</em> specified when adding the group to MDD and
     * <em>is</em> included here, the request will also fail with the same exception.
     */
    public abstract Builder setVariantIdOptional(Optional<String> variantIdOptional);

    /** Sets the optional title text for a notification when using foreground downloads. */
    public abstract Builder setContentTitleOptional(Optional<String> contentTitleOptional);

    /** Sets the optional content text for a notification when using foreground downloads. */
    public abstract Builder setContentTextOptional(Optional<String> contentTextOptional);

    /**
     * Sets the optional download conditions. If absent, MDD will use the download conditions from
     * the server config.
     */
    public abstract Builder setDownloadConditionsOptional(
        Optional<DownloadConditions> downloadConditionsOptional);

    /**
     * Sets the optional download listener when using foreground downloads. If present, will receive
     * download progress update.
     */
    public abstract Builder setListenerOptional(Optional<DownloadListener> listenerOptional);

    /**
     * Sets size of the being downloaded group in bytes when using foreground downloads. This is
     * used to display the progressbar. If not specified, a indeterminate progressbar will be
     * displayed.
     * https://developer.android.com/reference/android/app/Notification.Builder.html#setProgress(int,%20int,%20boolean)
     */
    public abstract Builder setGroupSizeBytes(int groupSizeBytes);

    /**
     * Controls if notifications should be created for this download request when using foreground
     * downloads. Defaults to true.
     */
    public abstract Builder setShowNotifications(ShowNotifications notifications);

    /**
     * By default, MDD will scan the directories generated by unpacking zip files in a download
     * transform and generate a ClientDataFile for each contained file. By default, MDD also hides
     * the root directory. Setting this to true disables that behavior, and will simply return the
     * directories as ClientDataFiles.
     */
    public abstract Builder setPreserveZipDirectories(boolean preserve);

    public abstract DownloadFileGroupRequest build();
  }
}
