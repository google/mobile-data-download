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

import static com.google.common.util.concurrent.Futures.getDone;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.VisibleForTesting;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.TimeSource;
import com.google.android.libraries.mobiledatadownload.annotations.InstanceId;
import com.google.android.libraries.mobiledatadownload.internal.annotations.SequentialControlExecutor;
import com.google.android.libraries.mobiledatadownload.internal.collect.GroupKeyAndGroup;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupsMetadataUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupsMetadataUtil.GroupKeyDeserializationException;
import com.google.android.libraries.mobiledatadownload.internal.util.ProtoLiteUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.SharedPreferencesUtil;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKeyProperties;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import org.checkerframework.checker.nullness.compatqual.NullableType;

/** Stores and provides access to file group metadata using SharedPreferences. */
@CheckReturnValue
public final class SharedPreferencesFileGroupsMetadata implements FileGroupsMetadata {

  private static final String TAG = "SharedPreferencesFileGroupsMetadata";
  private static final String MDD_FILE_GROUPS = FileGroupsMetadataUtil.MDD_FILE_GROUPS;
  private static final String MDD_FILE_GROUP_KEY_PROPERTIES =
      FileGroupsMetadataUtil.MDD_FILE_GROUP_KEY_PROPERTIES;

  // TODO(b/144033163): Migrate the Garbage Collector File to PDS.
  @VisibleForTesting static final String MDD_GARBAGE_COLLECTION_FILE = "gms_icing_mdd_garbage_file";

  private final Context context;
  private final TimeSource timeSource;
  private final SilentFeedback silentFeedback;
  private final Optional<String> instanceId;
  private final Executor sequentialControlExecutor;

  @Inject
  SharedPreferencesFileGroupsMetadata(
      @ApplicationContext Context context,
      TimeSource timeSource,
      SilentFeedback silentFeedback,
      @InstanceId Optional<String> instanceId,
      @SequentialControlExecutor Executor sequentialControlExecutor) {
    this.context = context;
    this.timeSource = timeSource;
    this.silentFeedback = silentFeedback;
    this.instanceId = instanceId;
    this.sequentialControlExecutor = sequentialControlExecutor;
  }

  @Override
  public ListenableFuture<Void> init() {
    return immediateVoidFuture();
  }

  @Override
  public ListenableFuture<@NullableType DataFileGroupInternal> read(GroupKey groupKey) {
    String serializedGroupKey = FileGroupsMetadataUtil.getSerializedGroupKey(groupKey);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_FILE_GROUPS, instanceId);
    DataFileGroupInternal fileGroup =
        SharedPreferencesUtil.readProto(prefs, serializedGroupKey, DataFileGroupInternal.parser());

    return immediateFuture(fileGroup);
  }

  @Override
  public ListenableFuture<Boolean> write(GroupKey groupKey, DataFileGroupInternal fileGroup) {
    String serializedGroupKey = FileGroupsMetadataUtil.getSerializedGroupKey(groupKey);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_FILE_GROUPS, instanceId);
    return immediateFuture(SharedPreferencesUtil.writeProto(prefs, serializedGroupKey, fileGroup));
  }

  @Override
  public ListenableFuture<Boolean> remove(GroupKey groupKey) {
    String serializedGroupKey = FileGroupsMetadataUtil.getSerializedGroupKey(groupKey);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_FILE_GROUPS, instanceId);
    return immediateFuture(SharedPreferencesUtil.removeProto(prefs, serializedGroupKey));
  }

  @Override
  public ListenableFuture<@NullableType GroupKeyProperties> readGroupKeyProperties(
      GroupKey groupKey) {
    String serializedGroupKey = FileGroupsMetadataUtil.getSerializedGroupKey(groupKey);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(
            context, MDD_FILE_GROUP_KEY_PROPERTIES, instanceId);
    GroupKeyProperties groupKeyProperties =
        SharedPreferencesUtil.readProto(prefs, serializedGroupKey, GroupKeyProperties.parser());

    return immediateFuture(groupKeyProperties);
  }

  @Override
  public ListenableFuture<Boolean> writeGroupKeyProperties(
      GroupKey groupKey, GroupKeyProperties groupKeyProperties) {
    String serializedGroupKey = FileGroupsMetadataUtil.getSerializedGroupKey(groupKey);

    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(
            context, MDD_FILE_GROUP_KEY_PROPERTIES, instanceId);
    return immediateFuture(
        SharedPreferencesUtil.writeProto(prefs, serializedGroupKey, groupKeyProperties));
  }

  @Override
  public ListenableFuture<List<GroupKey>> getAllGroupKeys() {
    List<GroupKey> groupKeyList = new ArrayList<>();
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_FILE_GROUPS, instanceId);
    SharedPreferences.Editor editor = null;
    for (String serializedGroupKey : prefs.getAll().keySet()) {
      try {
        GroupKey newFileKey = FileGroupsMetadataUtil.deserializeGroupKey(serializedGroupKey);
        groupKeyList.add(newFileKey);
      } catch (GroupKeyDeserializationException e) {
        LogUtil.e(e, "Failed to deserialize groupKey:" + serializedGroupKey);
        silentFeedback.send(e, "Failed to deserialize groupKey");
        // TODO(b/128850000): Refactor this code to a single corruption handling task during
        // maintenance.
        // Remove the corrupted file metadata and the related SharedFile metadata will be deleted
        // in next maintenance task.
        if (editor == null) {
          editor = prefs.edit();
        }
        editor.remove(serializedGroupKey);
        LogUtil.d("%s: Deleting null file group ", TAG);
        continue;
      }
    }
    if (editor != null) {
      editor.commit();
    }
    return immediateFuture(groupKeyList);
  }

  @Override
  public ListenableFuture<List<GroupKeyAndGroup>> getAllFreshGroups() {
    return PropagatedFutures.transformAsync(
        getAllGroupKeys(),
        groupKeyList -> {
          List<ListenableFuture<@NullableType DataFileGroupInternal>> groupReadFutures =
              new ArrayList<>();
          for (GroupKey key : groupKeyList) {
            groupReadFutures.add(read(key));
          }
          return PropagatedFutures.whenAllComplete(groupReadFutures)
              .callAsync(
                  () -> {
                    List<GroupKeyAndGroup> retrievedGroups = new ArrayList<>();
                    for (int i = 0; i < groupKeyList.size(); i++) {
                      GroupKey key = groupKeyList.get(i);
                      DataFileGroupInternal group = getDone(groupReadFutures.get(i));
                      if (group == null) {
                        continue;
                      }
                      retrievedGroups.add(GroupKeyAndGroup.create(key, group));
                    }
                    return immediateFuture(retrievedGroups);
                  },
                  sequentialControlExecutor);
        },
        sequentialControlExecutor);
  }

  @Override
  public ListenableFuture<Boolean> removeAllGroupsWithKeys(List<GroupKey> keys) {
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_FILE_GROUPS, instanceId);
    SharedPreferences.Editor editor = prefs.edit();
    for (GroupKey key : keys) {
      LogUtil.d("%s: Removing group %s %s", TAG, key.getGroupName(), key.getOwnerPackage());
      SharedPreferencesUtil.removeProto(editor, key);
    }
    return immediateFuture(editor.commit());
  }

  @Override
  public ListenableFuture<List<DataFileGroupInternal>> getAllStaleGroups() {
    return immediateFuture(
        FileGroupsMetadataUtil.getAllStaleGroups(
            FileGroupsMetadataUtil.getGarbageCollectorFile(context, instanceId)));
  }

  @Override
  public ListenableFuture<Boolean> addStaleGroup(DataFileGroupInternal fileGroup) {
    LogUtil.d("%s: Adding file group %s", TAG, fileGroup.getGroupName());

    long currentTimeSeconds = timeSource.currentTimeMillis() / 1000;
    fileGroup =
        FileGroupUtil.setStaleExpirationDate(
            fileGroup, currentTimeSeconds + fileGroup.getStaleLifetimeSecs());

    List<DataFileGroupInternal> fileGroups = new ArrayList<>();
    fileGroups.add(fileGroup);

    return writeStaleGroups(fileGroups);
  }

  @Override
  public ListenableFuture<Boolean> writeStaleGroups(List<DataFileGroupInternal> fileGroups) {
    File garbageCollectorFile = getGarbageCollectorFile();
    FileOutputStream outputStream;
    try {
      outputStream = new FileOutputStream(garbageCollectorFile, /* append */ true);
    } catch (FileNotFoundException e) {
      LogUtil.e("File %s not found while writing.", garbageCollectorFile.getAbsolutePath());
      return immediateFuture(false);
    }

    try {
      // tail_crc == false, means that each message has its own crc
      ByteBuffer buf = ProtoLiteUtil.dumpIntoBuffer(fileGroups, false /*tail crc*/);
      if (buf != null) {
        outputStream.getChannel().write(buf);
      }
      outputStream.close();
    } catch (IOException e) {
      LogUtil.e("IOException occurred while writing file groups.");
      return immediateFuture(false);
    }
    return immediateFuture(true);
  }

  @VisibleForTesting
  File getGarbageCollectorFile() {
    return FileGroupsMetadataUtil.getGarbageCollectorFile(context, instanceId);
  }

  // TODO(b/124072754): Change to package private once all code is refactored.
  @Override
  public ListenableFuture<Void> removeAllStaleGroups() {
    getGarbageCollectorFile().delete();
    return immediateVoidFuture();
  }

  @Override
  public ListenableFuture<Void> clear() {
    SharedPreferences prefs =
        SharedPreferencesUtil.getSharedPreferences(context, MDD_FILE_GROUPS, instanceId);
    prefs.edit().clear().commit();

    SharedPreferences activatedGroupPrefs =
        SharedPreferencesUtil.getSharedPreferences(
            context, MDD_FILE_GROUP_KEY_PROPERTIES, instanceId);
    activatedGroupPrefs.edit().clear().commit();

    return removeAllStaleGroups();
  }
}
