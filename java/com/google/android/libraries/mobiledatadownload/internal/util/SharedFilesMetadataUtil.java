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

import static com.google.android.libraries.mobiledatadownload.internal.MddConstants.SPLIT_CHAR;

import android.content.Context;
import com.google.android.libraries.mobiledatadownload.SilentFeedback;
import com.google.android.libraries.mobiledatadownload.internal.Migrations;
import com.google.common.base.Splitter;
import com.google.mobiledatadownload.TransformProto.Transforms;
import com.google.mobiledatadownload.internal.MetadataProto.DataFileGroupInternal.AllowedReaders;
import com.google.mobiledatadownload.internal.MetadataProto.NewFileKey;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;

/** Utilities needed by multiple implementations of {@link SharedFilesMetadata}. */
public final class SharedFilesMetadataUtil {

  private static final String TAG = "SharedFilesMetadataUtil";

  // Stores the mapping from FileKey:SharedFile.
  public static final String MDD_SHARED_FILES = "gms_icing_mdd_shared_files";

  /** File key Deserialization exception. */
  public static class FileKeyDeserializationException extends Exception {
    FileKeyDeserializationException(String msg) {
      super(msg);
    }

    FileKeyDeserializationException(String msg, Throwable cause) {
      super(msg, cause);
    }
  }

  public static String getSerializedFileKey(
      NewFileKey newFileKey, Context context, SilentFeedback silentFeedback) {
    switch (Migrations.getCurrentVersion(context, silentFeedback)) {
      case NEW_FILE_KEY:
        return serializeNewFileKey(newFileKey);
      case ADD_DOWNLOAD_TRANSFORM:
        return serializeNewFileKeyWithDownloadTransform(newFileKey);
      case USE_CHECKSUM_ONLY:
        return serializeNewFileKeyWithChecksumOnly(newFileKey);
    }
    return serializeNewFileKey(newFileKey);
  }

  public static String serializeNewFileKey(NewFileKey newFileKey) {
    return new StringBuilder(newFileKey.getUrlToDownload())
        .append(SPLIT_CHAR)
        .append(newFileKey.getByteSize())
        .append(SPLIT_CHAR)
        .append(newFileKey.getChecksum())
        .append(SPLIT_CHAR)
        .append(newFileKey.getAllowedReaders().getNumber())
        .toString();
  }

  public static String serializeNewFileKeyWithDownloadTransform(NewFileKey newFileKey) {
    return new StringBuilder(newFileKey.getUrlToDownload())
        .append(SPLIT_CHAR)
        .append(newFileKey.getByteSize())
        .append(SPLIT_CHAR)
        .append(newFileKey.getChecksum())
        .append(SPLIT_CHAR)
        .append(newFileKey.getAllowedReaders().getNumber())
        .append(SPLIT_CHAR)
        .append(
            newFileKey.hasDownloadTransforms()
                ? SharedPreferencesUtil.serializeProto(newFileKey.getDownloadTransforms())
                : "")
        .toString();
  }

  public static String serializeNewFileKeyWithChecksumOnly(NewFileKey newFileKey) {
    return new StringBuilder(newFileKey.getChecksum())
        .append(SPLIT_CHAR)
        .append(newFileKey.getAllowedReaders().getNumber())
        .toString();
  }

  // incompatible argument for parameter value of setAllowedReaders.
  @SuppressWarnings("nullness:argument.type.incompatible")
  public static NewFileKey deserializeNewFileKey(
      String serializedFileKey, Context context, SilentFeedback silentFeedback)
      throws FileKeyDeserializationException {
    List<String> fileKeyComponents = Splitter.on(SPLIT_CHAR).splitToList(serializedFileKey);
    NewFileKey.Builder newFileKey;

    switch (Migrations.getCurrentVersion(context, silentFeedback)) {
      case ADD_DOWNLOAD_TRANSFORM:
        if (fileKeyComponents.size() != 5) {
          throw new FileKeyDeserializationException(
              "Bad-format" + " serializedFileKey" + " = " + serializedFileKey);
        }
        newFileKey =
            NewFileKey.newBuilder()
                .setUrlToDownload(fileKeyComponents.get(0))
                .setByteSize(Integer.parseInt(fileKeyComponents.get(1)))
                .setChecksum(fileKeyComponents.get(2))
                .setAllowedReaders(
                    AllowedReaders.forNumber(Integer.parseInt(fileKeyComponents.get(3))));
        if (fileKeyComponents.get(4) != null && !fileKeyComponents.get(4).isEmpty()) {
          try {
            newFileKey.setDownloadTransforms(
                SharedPreferencesUtil.parseLiteFromEncodedString(
                    fileKeyComponents.get(4), Transforms.parser()));
          } catch (InvalidProtocolBufferException e) {
            throw new FileKeyDeserializationException(
                "Failed to deserialize key:" + serializedFileKey, e);
          }
        }
        break;
      case USE_CHECKSUM_ONLY:
        if (fileKeyComponents.size() != 2) {
          throw new FileKeyDeserializationException(
              "Bad-format" + " serializedFileKey" + " = s" + serializedFileKey);
        }
        newFileKey =
            NewFileKey.newBuilder()
                .setChecksum(fileKeyComponents.get(0))
                .setAllowedReaders(
                    AllowedReaders.forNumber(Integer.parseInt(fileKeyComponents.get(1))));
        break;
      default: // Fall through
        if (fileKeyComponents.size() != 4) {
          throw new FileKeyDeserializationException(
              "Bad-format" + " serializedFileKey" + " = " + serializedFileKey);
        }
        newFileKey =
            NewFileKey.newBuilder()
                .setUrlToDownload(fileKeyComponents.get(0))
                .setByteSize(Integer.parseInt(fileKeyComponents.get(1)))
                .setChecksum(fileKeyComponents.get(2))
                .setAllowedReaders(
                    AllowedReaders.forNumber(Integer.parseInt(fileKeyComponents.get(3))));
    }
    return newFileKey.build();
  }

  private SharedFilesMetadataUtil() {}
}
