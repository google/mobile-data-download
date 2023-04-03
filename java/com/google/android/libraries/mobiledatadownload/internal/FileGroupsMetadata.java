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
package com.google.android.libraries.mobiledatadownload.internal;

import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKeyProperties;
import java.util.List;
import org.checkerframework.checker.nullness.compatqual.NullableType;

/** Stores and provides access to file group metadata. */
public interface FileGroupsMetadata {

  /**
   * Makes any changes that should be made before accessing the internal state of this store.
   *
   * <p>Other methods in this class do not call or check if this method was already called before
   * trying to access internal state. It is expected from the caller to call this before anything
   * else.
   */
  ListenableFuture<Void> init();

  /** Returns a future resolving to the DataFileGroupInternal associated with key "groupKey". */
  ListenableFuture<@NullableType DataFileGroupInternal> read(GroupKey groupKey);

  /**
   * Maps the key "groupKey" to the value "fileGroup" in the store. Returns future resolving to true
   * if the operation succeeds, and false if not.
   */
  // TODO(b/159828199): This method should return a Void future and signal failure using exceptions
  // instead of a Boolean.
  ListenableFuture<Boolean> write(GroupKey groupKey, DataFileGroupInternal fileGroup);

  /**
   * Removes the DataFileGroupInternal associated with the key "groupKey" in the store. Returns
   * future resolving to true if the operation succeeds, and false if not.
   */
  ListenableFuture<Boolean> remove(GroupKey groupKey);

  /** Returns a future resolving to the GroupKeyProperties associated with the key "groupKey". */
  ListenableFuture<@NullableType GroupKeyProperties> readGroupKeyProperties(GroupKey groupKey);

  /**
   * Maps the key "groupKey" to the value "groupKeyProperties" in the store. Returns future
   * resolving to true if the operation succeeds, and false if not.
   */
  ListenableFuture<Boolean> writeGroupKeyProperties(
      GroupKey groupKey, GroupKeyProperties groupKeyProperties);

  /** Returns all keys in the store. */
  ListenableFuture<List<GroupKey>> getAllGroupKeys();

  /**
   * Retrieves all groups in metadata. Will ignore groups that are unable to parse.
   *
   * @return A future resolving to a list containing pairs of serialized GroupKeys and the
   *     corresponding DataFileGroups.
   */
  ListenableFuture<List<GroupKeyAndGroup>> getAllFreshGroups();

  /**
   * Removes all entries with a key in keys from the SharedPreferencesFileGroupsMetadata's storage.
   * This method doesn't take care of garbage collecting any files used by this group, and that is
   * left for the caller to do.
   *
   * @param keys - the list of keys to remove entries for
   * @return - true if the removals were successfully persisted to disk.
   */
  ListenableFuture<Boolean> removeAllGroupsWithKeys(List<GroupKey> keys);

  /**
   * Retrieves all file groups on device that are marked as stale.
   *
   * @return Future resolving to a list of DataFileGroupInternal's stored in the garbage collection
   *     file or an empty list if an IO error occurs or if the file doesn't exist (because all
   *     previous groups had been deleted and no new groups had been added).
   */
  ListenableFuture<List<DataFileGroupInternal>> getAllStaleGroups();

  /**
   * Adds file group to list of file groups to unsubscribe from when their TTLs expire. This method
   * will set the staleExpirationDate field on the file group.
   *
   * @param fileGroup - a file group that is no longer needed (and should release all of its files
   *     once its TTL expires). The staleLifetimeSecs field must be set.
   * @return future resolving to false if an IO error occurs
   */
  ListenableFuture<Boolean> addStaleGroup(DataFileGroupInternal fileGroup);

  /**
   * Write an array of stale file groups into garbage collector file.
   *
   * @param fileGroups - an array of file groups to write to garbage collection file
   * @return future resolving to false if an IO error occurs
   */
  ListenableFuture<Boolean> writeStaleGroups(List<DataFileGroupInternal> fileGroups);

  /** Deletes all storage for stale file groups. */
  ListenableFuture<Void> removeAllStaleGroups();

  /** Clears all storage used by the SharedPreferencesFileGroupsMetadata class. */
  ListenableFuture<Void> clear();
}
