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
package com.google.android.libraries.mobiledatadownload.internal.util;

import android.content.Context;
import android.util.Base64;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/** Stores and provides access to file group metadata using SharedPreferences. */
public final class FileGroupsMetadataUtil {

  private static final String TAG = "FileGroupsMetadataUtil";

  // Name of file groups SharedPreferences.
  public static final String MDD_FILE_GROUPS = "gms_icing_mdd_groups";

  // Name of file groups group key properties SharedPreferences.
  public static final String MDD_FILE_GROUP_KEY_PROPERTIES = "gms_icing_mdd_group_key_properties";

  // TODO(b/144033163): Migrate the Garbage Collector File to PDS.
  public static final String MDD_GARBAGE_COLLECTION_FILE = "gms_icing_mdd_garbage_file";

  /** Group key Deserialization exception. */
  public static class GroupKeyDeserializationException extends Exception {
    GroupKeyDeserializationException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }

  // TODO(b/144033163): Migrate the Garbage Collector File to PDS.
  public static List<DataFileGroupInternal> getAllStaleGroups(File garbageCollectorFile) {
    FileInputStream inputStream;
    try {
      inputStream = new FileInputStream(garbageCollectorFile);
    } catch (FileNotFoundException e) {
      LogUtil.d("File %s not found while reading.", garbageCollectorFile.getAbsolutePath());
      return ImmutableList.of();
    }

    ByteBuffer buf;
    try {
      buf = ByteBuffer.allocate((int) garbageCollectorFile.length());
    } catch (IllegalArgumentException e) {
      LogUtil.e(e, "%s: Exception while reading from stale groups into buffer.", TAG);
      return ImmutableList.of();
    }

    List<DataFileGroupInternal> fileGroups = null;
    try {
      inputStream.getChannel().read(buf);
      // Rewind so that we can read from the start of the buffer.
      buf.rewind();
      // tail_crc == false, means that each message has its own crc
      fileGroups =
          ProtoLiteUtil.readFromBuffer(
              buf, DataFileGroupInternal.class, DataFileGroupInternal.parser(), false /*tail crc*/);
      inputStream.close();
    } catch (IOException e) {
      LogUtil.e(e, "%s: IOException occurred while reading file groups.", TAG);
    }
    return fileGroups == null ? ImmutableList.of() : fileGroups;
  }

  public static File getGarbageCollectorFile(Context context, Optional<String> instanceId) {
    String fileName =
        instanceId != null && instanceId.isPresent()
            ? MDD_GARBAGE_COLLECTION_FILE + instanceId.get()
            : MDD_GARBAGE_COLLECTION_FILE;
    return new File(context.getFilesDir(), fileName);
  }

  // TODO(b/129702287): Move away from proto based serialization.
  public static String getSerializedGroupKey(GroupKey groupKey, Context context) {
    byte[] byteValue = groupKey.toByteArray();
    return Base64.encodeToString(byteValue, Base64.NO_PADDING | Base64.NO_WRAP);
  }

  /**
   * Converts a string representing a serialized GroupKey into a GroupKey.
   *
   * @return - groupKey if able to parse stringKey properly. null if parsing fails.
   */
  // TODO(b/129702287): Move away from proto based deserialization.
  public static GroupKey deserializeGroupKey(String serializedGroupKey)
      throws GroupKeyDeserializationException {
    try {
      return SharedPreferencesUtil.parseLiteFromEncodedString(
          serializedGroupKey, GroupKey.parser());
    } catch (InvalidProtocolBufferException e) {
      throw new GroupKeyDeserializationException(
          "Failed to deserialize key:" + serializedGroupKey, e);
    }
  }

  private FileGroupsMetadataUtil() {}
}
