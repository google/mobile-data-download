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

import com.google.android.libraries.mobiledatadownload.TaskScheduler.ConstraintOverrides;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.ClientConfigProto.ClientFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import java.util.Map;

/** The root object and entry point for the MobileDataDownload library. */
public interface MobileDataDownload {

  /**
   * Adds for download the data file group in {@link AddFileGroupRequest}, after running validation
   * on the group. This group will replace any previous version of this group once it is downloaded.
   *
   * <p>This api takes {@link AddFileGroupRequest} that contains data file group, and it can be used
   * to set extra params such as account.
   *
   * <p>This doesn't start the download right away. The download starts later when the tasks
   * scheduled via {@link #schedulePeriodicTasks} are run.
   *
   * <p>Calling this api with the exact same parameters multiple times is a no-op.
   *
   * @param addFileGroupRequest The request to add file group in MDD.
   * @return ListenableFuture of true if the group was successfully added, or the group was already
   *     present; ListenableFuture of false if the group is invalid or an I/O error occurs.
   */
  ListenableFuture<Boolean> addFileGroup(AddFileGroupRequest addFileGroupRequest);

  /**
   * Removes all versions of the data file group that matches {@link RemoveFileGroupRequest} from
   * MDD. If no data file group matches, this call is a no-op.
   *
   * <p>This api takes {@link RemoveFileGroupRequest} that contains data file group, and it can be
   * used to set extra params such as account.
   *
   * @param removeFileGroupRequest The request to remove file group from MDD.
   * @return Listenable of true if the group was successfully removed, or no group matches;
   *     Listenable of false if the matching group fails to be removed.
   */
  ListenableFuture<Boolean> removeFileGroup(RemoveFileGroupRequest removeFileGroupRequest);

  /**
   * Removes all versions of the data file groups that match {@link RemoveFileGroupsByFilterRequest}
   * from MDD. If no data file group matches, this call is a no-op.
   *
   * <p>This api takes a {@link RemoveFileGroupsByFilterRequest} that contains optional filters for
   * the group name, group source, associated account, etc.
   *
   * <p>A resolved future will only be returned if the removal completes successfully for all
   * matching file groups. If any failures occur during this method, it will return a failed future
   * with an {@link AggregateException} containing the failures that occurred.
   *
   * <p>NOTE: This only removes the metadata from MDD, not file content. Downloaded files that are
   * no longer needed are deleted during MDD's daily maintenance task.
   *
   * @param removeFileGroupsByFilterRequest The request to remove file group from MDD.
   * @return ListenableFuture that resolves with {@link RemoveFileGroupsByFilterResponse}, or fails
   *     with {@link AggregateException}
   */
  ListenableFuture<RemoveFileGroupsByFilterResponse> removeFileGroupsByFilter(
      RemoveFileGroupsByFilterRequest removeFileGroupsByFilterRequest);

  /**
   * Gets the file group definition that was added to MDD. This API cannot be used to access files,
   * but it can be accessed by populators to manipulate the existing file group state - eg, to
   * rename a file group, or otherwise migrate from one format to another.
   *
   * @return DataFileGroup if downloaded file group is found, otherwise a failing LF.
   */
  default ListenableFuture<DataFileGroup> readDataFileGroup(
      ReadDataFileGroupRequest readDataFileGroupRequest) {
    throw new UnsupportedOperationException();
  }

  /**
   * Gets DataFileGroup definitions that were added to MDD by filter. This API cannot be used to
   * access files.
   *
   * <p>Only present fields in {@link ReadDataFileGroupsByFilterRequest} will be used to perform the
   * filtering. For example, if no account is specified in the filter, file groups won't be filtered
   * based on account.
   *
   * @param readDataFileGroupsByFilterRequest The request to get multiple data file groups after
   *     filtering.
   * @return The ListenableFuture that will resolve to a list of the requested data file groups.
   *     This ListenableFuture will resolve to all data file groups when {@code
   *     readDataFileGroupsByFilterRequest.includeAllGroups} is true.
   */
  default ListenableFuture<ImmutableList<DataFileGroup>> readDataFileGroupsByFilter(
      ReadDataFileGroupsByFilterRequest readDataFileGroupsByFilterRequest) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the latest downloaded data that we have for the given group name.
   *
   * <p>This api takes an instance of {@link GetFileGroupRequest} that contains group name, and it
   * can be used to set extra params such as account.
   *
   * <p>This listenable future will return null if no group exists or has been downloaded for the
   * given group name.
   *
   * <p>Note: getFileGroup returns a snapshot of the latest state, but it's possible for the state
   * to change between a getFileGroup call and accessing the files if the ClientFileGroup gets
   * cached. Caching the returned ClientFileGroup is therefore discouraged.
   *
   * @param getFileGroupRequest The request to get a single file group.
   * @return The ListenableFuture of requested client file group for the given request.
   */
  ListenableFuture<ClientFileGroup> getFileGroup(GetFileGroupRequest getFileGroupRequest);

  /**
   * Returns all the data that we have for the given {@link GetFileGroupsByFilterRequest}.
   *
   * <p>This listenable future will return a list of file groups with their current download status.
   *
   * <p>Only present fields in {@link GetFileGroupsByFilterRequest} will be used to perform the
   * filtering, i.e. when no account is specified in the filter, file groups won't be filtered based
   * on account.
   *
   * <p>Note: getFileGroupsByFilter returns a snapshot of the latest state, but it's possible for
   * the state to change between a getFileGroupsByFilter call and accessing the files if the
   * ClientFileGroup gets cached. Caching the returned ClientFileGroup is therefore discouraged.
   *
   * @param getFileGroupsByFilterRequest The request to get multiple file groups after filtering.
   * @return The ListenableFuture that will resolve to a list of the requested client file groups,
   *     including pending and downloaded versions; this ListenableFuture will resolve to all client
   *     file groups when {@code getFileGroupsByFilterRequest.includeAllGroups} is true.
   */
  ListenableFuture<ImmutableList<ClientFileGroup>> getFileGroupsByFilter(
      GetFileGroupsByFilterRequest getFileGroupsByFilterRequest);

  /**
   * Imports Inline Files into an Existing MDD File Group.
   *
   * <p>This api takes a {@link ImportFilesRequest} containing identifying information about an
   * existing File Group, an optional list of {@link DataFile}s to import into the existing File
   * Group, and a Map of file content to import into MDD.
   *
   * <p>The identifying information is used to identify a file group and its specific version. This
   * prevents the caller from accidentally importing files into the wrong file group or the wrong
   * version of the file group. An optional {@link Account} parameter can also be specified if the
   * existing file group was associated with an account.
   *
   * <p>The given {@link DataFile} list allows updated files (still compatible with a given file
   * group version) to be imported into MDD. This API wll merge the given DataFiles into the
   * existing file group in the following manner:
   *
   * <ul>
   *   <li>DataFiles included in the DataFile list but not the existing file group will be added as
   *       new DataFiles
   *   <li>DataFiles included in the DataFile list will replace DataFiles in the existing file group
   *       if their file Ids match.
   *   <li>DataFiles included in the existing file group but not the DataFile list will remain
   *       untouched.
   * </ul>
   *
   * <p>{@link ImportFilesRequest} also requires a Map of file sources that should be imported by
   * MDD. The Map is keyed by the fileIds of DataFiles and contains the contents of the file to
   * import within a {@link ByteString}. This Map must contains an entry for all {@link DataFile}s
   * which require an inline file source. Only "Inline" {@link DataFile}s should be included in this
   * map (see details below).
   *
   * <p>An inline {@link DataFile} is the same as a standard {@link DataFile}, but instead of an
   * "https" url, the url should match the following format:
   *
   * <pre>{@code "inlinefile:<key>"}</pre>
   *
   * <p>Where {@code key} is a unique identifier of the file. In most cases, the checksum should be
   * used as this key. If the checksum is not used, another unique identifier should be used to
   * allow proper deduping of the file import within MDD.
   *
   * <p>Example inline file url:
   *
   * <pre>{@code inlinefile:sha1:9a4ea3ca81d3f1d631531cbc216a62d9b10509ee}</pre>
   *
   * <p>NOTE: Inline files can be specified by the given DataFile list in {@link
   * ImportFilesRequest}, but can also be specified by a {@link DataFileGroup} added via {@link
   * #addFileGroup}. A File Group that contains inline files will not be considered DOWNLOADED until
   * all inline files are imported via this API.
   *
   * <p>Because this method performs an update to the stored File Group metadata, the given {@link
   * ImportFilesRequest} must satisfy the following conditions:
   *
   * <ul>
   *   <li>The requests identifying information must match an existing File Group
   *   <li>All inline DataFiles must have file content specified in the request's Inline File Map
   * </ul>
   *
   * <p>If either of these conditions is not met, this operation will return a failed
   * ListenableFuture.
   *
   * <p>Finally, this API is a atomic operation. That is, <em>either all inline files will be
   * imported successfully or none will be imported</em>. If there is a failure with importing a
   * file, MDD will not update the file group (i.e. future calls to {@link #getFileGroup} will
   * return the same {@link ClientFileGroup} as before this call).
   *
   * @param importFilesRequest Request containing required parameters to perform import files
   *     operation.
   * @return ListenableFuture that resolves when all inline files are successfully imported.
   */
  ListenableFuture<Void> importFiles(ImportFilesRequest importFilesRequest);

  /**
   * Downloads a single file.
   *
   * <p>This api takes {@link SingleFileDownloadRequest}, which contains a download url of the file
   * to download. the destination location on device must also be specified. See
   * SingleFileDownloadRequest for full list of required/optional parameters.
   *
   * <p>The returned ListenableFuture will fail if there is an error during the download. The caller
   * is responsible for calling downloadFile again to restart the download.
   *
   * <p>The caller can be notified of progress by providing a {@link SingleFileDownloadListener}.
   * This listener will also provide callbacks for a completed download, failed download, or paused
   * download due to connectivity loss.
   *
   * <p>The caller can specify constraints that should be used for the download by providing a
   * {@link com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints}. This
   * allows downloads to only start when on Wifi, for example. By default, no constraints are
   * specified.
   *
   * @param singleFileDownloadRequest The request to download a file.
   * @return ListenableFuture that resolves when file is downloaded.
   */
  @CheckReturnValue
  ListenableFuture<Void> downloadFile(SingleFileDownloadRequest singleFileDownloadRequest);

  /**
   * Downloads and returns the latest downloaded data that we have for the given group name.
   *
   * <p>This api takes {@link DownloadFileGroupRequest} that contains group name, and it can be used
   * to set extra params such as account, download conditions, and download listener.
   *
   * <p>The group name must be added using {@link #addFileGroup} before downloading the file group.
   *
   * <p>The returned ListenableFuture will be resolved when the file group is downloaded. It can
   * also be used to cancel the download.
   *
   * <p>The returned ListenableFuture would fail if there is any error during the download. Client
   * is responsible to call the downloadFileGroup to resume the download.
   *
   * <p>Download progress is supported through the DownloadListener.
   *
   * <p>To download under any conditions, clients should use {@link
   * Constants.NO_RESTRICTIONS_DOWNLOAD_CONDITIONS}
   *
   * @param downloadFileGroupRequest The request to download file group.
   */
  ListenableFuture<ClientFileGroup> downloadFileGroup(
      DownloadFileGroupRequest downloadFileGroupRequest);

  /**
   * Downloads a file using a foreground service and notification.
   *
   * <p>This is similar to {@link #downloadFile}, but allows the download to continue running when
   * the app enters the background.
   *
   * <p>The notification created for the download includes a cancel action. This will allow the
   * download to be cancelled even when the app is in the background.
   *
   * <p>The cancel action in the notification menu requires the ForegroundService to be registered
   * with the application (via the AndroidManifest.xml). This allows the cancellation intents to be
   * properly picked up. To register the service, the following lines must be included in the app's
   * {@code AndroidManifest.xml}:
   *
   * <pre>{@code
   * <!-- Needed by foreground download service -->
   * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   *
   * <!-- Service for MDD foreground downloads -->
   * <service
   *   android:name="com.google.android.libraries.mobiledatadownload.foreground.sting.ForegroundDownloadService"
   *   android:exported="false" />
   * }</pre>
   *
   * <p>NOTE: The above excerpt is for Framework and Sting apps. Dagger apps should use the same
   * excerpt, but change the {@code android:name} property to:
   *
   * <pre>{@code
   * android:name="com.google.android.libraries.mobiledatadownload.foreground.dagger.ForegroundDownloadService"
   * }</pre>
   */
  @CheckReturnValue
  ListenableFuture<Void> downloadFileWithForegroundService(
      SingleFileDownloadRequest singleFileDownloadRequest);

  /**
   * Download a file group and show foreground download progress in a notification. User can cancel
   * the download from the notification menu.
   *
   * <p>The cancel action in the notification menu requires the ForegroundService to be registered
   * with the application (via the AndroidManifest.xml). This allows the cancellation intents to be
   * properly picked up. To register the service, the following lines must be included in the app's
   * {@code AndroidManifest.xml}:
   *
   * <pre>{@code
   * <!-- Needed by foreground download service -->
   * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   *
   * <!-- Service for MDD foreground downloads -->
   * <service
   *   android:name="com.google.android.libraries.mobiledatadownload.foreground.sting.ForegroundDownloadService"
   *   android:exported="false" />
   * }</pre>
   *
   * <p>NOTE: The above excerpt is for Framework and Sting apps. Dagger apps should use the same
   * excerpt, but change the {@code android:name} property to:
   *
   * <pre>{@code
   * android:name="com.google.android.libraries.mobiledatadownload.foreground.dagger.ForegroundDownloadService"
   * }</pre>
   */
  ListenableFuture<ClientFileGroup> downloadFileGroupWithForegroundService(
      DownloadFileGroupRequest downloadFileGroupRequest);

  /**
   * Cancel an on-going foreground download.
   *
   * <p>Attempts to cancel an on-going foreground download using best effort. If download is unknown
   * to MDD, this operation is a noop.
   *
   * <p>The key passed here must be created using {@link ForegroundDownloadKey}, and must match the
   * properties used from the request. Depending on which API was used to start the download, this
   * would be {@link DownloadFileGroupRequest} for {@link SingleFileDownloadRequest}.
   *
   * <p><b>NOTE:</b> In most cases, clients will not need to call this -- it is meant to allow the
   * ForegroundDownloadService to cancel a download via the Cancel action registered to a
   * notification.
   *
   * <p>Clients should prefer to cancel the future returned to them from the download call.
   *
   * @param downloadKey the key associated with the download
   */
  void cancelForegroundDownload(String downloadKey);

  /**
   * Triggers the execution of MDD maintenance.
   *
   * <p>MDD needs to run maintenance task once a day. If you call {@link
   * #schedulePeriodicBackgroundTasks} api, the maintenance will be called automatically. In case
   * you don't want to schedule MDD tasks, you can call this maintenance method directly.
   *
   * <p>If you do need to call this api, make sure that this api is called exactly once every day.
   *
   * <p>The returned ListenableFuture would fail if the maintenance execution doesn't succeed.
   */
  ListenableFuture<Void> maintenance();

  /**
   * Perform garbage collection, which includes removing expired file groups and unreferenced files.
   *
   * <p>By default, this is run as part of {@link #maintenance} so doesn't need to be invoked
   * directly by client code. If you disabled that behavior via {@link
   * Flags#mddEnableGarbageCollection} then this method should be periodically called to clean up
   * unused files.
   */
  ListenableFuture<Void> collectGarbage();

  /**
   * Schedule periodic tasks that will download and verify all file groups when the required
   * conditions are met, using the given {@link TaskScheduler}.
   *
   * <p>If the host app doesn't provide a TaskScheduler, calling this API will be a no-op.
   *
   * @deprecated Use the {@link schedulePeriodicBackgroundTasks} instead.
   */
  @Deprecated
  void schedulePeriodicTasks();

  /**
   * Schedule periodic background tasks that will download and verify all file groups when the
   * required conditions are met, using the given {@link TaskScheduler}.
   *
   * <p>If the host app doesn't provide a TaskScheduler, calling this API will be a no-op.
   */
  ListenableFuture<Void> schedulePeriodicBackgroundTasks();

  /**
   * Schedule periodic background tasks that will download and verify all file groups when the
   * required conditions are met, using the given {@link TaskScheduler}.
   *
   * <p>If the host app doesn't provide a TaskScheduler, calling this API will be a no-op.
   *
   * @param constraintOverridesMap to allow clients to override constraints requirements.
   *     <p><code>{@code
   *  ConstraintOverrides wifiOverrides =
   *     ConstraintOverrides.newBuilder()
   *         .setRequiresCharging(false)
   *         .setRequiresDeviceIdle(true)
   *         .build();
   * ConstraintOverrides cellularOverrides =
   *     ConstraintOverrides.newBuilder()
   *         .setRequiresCharging(true)
   *         .setRequiresDeviceIdle(false)
   *         .build();
   *
   *  Map<String, ConstraintOverrides> constraintOverridesMap = new HashMap<>();
   *  constraintOverridesMap.put(TaskScheduler.WIFI_CHARGING_PERIODIC_TASK, wifiOverrides);
   *  constraintOverridesMap.put(TaskScheduler.CELLULAR_CHARGING_PERIODIC_TASK, cellularOverrides);
   *
   *  mobileDataDownload.schedulePeriodicBackgroundTasks(Optional.of(constraintOverridesMap)).get();
   * }</code>
   */
  ListenableFuture<Void> schedulePeriodicBackgroundTasks(
      Optional<Map<String, ConstraintOverrides>> constraintOverridesMap);

  /**
   * Cancels previously-scheduled periodic background tasks using the given {@link TaskScheduler}.
   * Cancelling is best-effort and only meant to be used in an emergency; most apps will never need
   * to call it.
   *
   * <p>If the host app doesn't provide a TaskScheduler, calling this API is a no-op.
   */
  default ListenableFuture<Void> cancelPeriodicBackgroundTasks() {
    // TODO(b/223822302): remove default once all implementations have been updated to include it
    return Futures.immediateVoidFuture();
  }

  /**
   * Handle a task scheduled via a task scheduling service.
   *
   * <p>This method should not be called on the main thread, as it does work on the thread it is
   * called on.
   *
   * @return a listenable future which indicates when any async task scheduled is complete.
   */
  ListenableFuture<Void> handleTask(String tag);

  /** Clear MDD metadata and its managed files. MDD will be reset to a clean state. */
  ListenableFuture<Void> clear();

  /**
   * Return MDD debug info as a string. This could return some PII information so it's not
   * recommended to be called in production build.
   *
   * <p>This debug info string could be very long. In order to print them in adb logcat, we have to
   * split the string. See how it is done in our sample app: <internal>
   */
  String getDebugInfoAsString();

  /**
   * Reports usage of a file group back to MDD. This can be used to track errors with file group
   * roll outs. Each usage of the file group should result in a single call of this method in order
   * to allow for accurate metrics server side.
   *
   * @param usageEvent that will be logged.
   * @return a listenable future which indicates that the UsageEvent has been logged.
   */
  @CanIgnoreReturnValue
  ListenableFuture<Void> reportUsage(UsageEvent usageEvent);
}
