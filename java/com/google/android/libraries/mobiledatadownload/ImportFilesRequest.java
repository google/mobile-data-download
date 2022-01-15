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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import javax.annotation.concurrent.Immutable;

/** Request to import files into an existing DataFileGroup. */
@AutoValue
@Immutable
public abstract class ImportFilesRequest {

  ImportFilesRequest() {}

  /** Name that identifies the file group to import files into. */
  public abstract String groupName();

  /** Build id that identifies the file group to import files into. */
  public abstract long buildId();

  /** Variant id that identifies the file group to import files into. */
  public abstract String variantId();

  /**
   * Custom property that identifies the file group to import files into.
   *
   * <p>If a file group supports this field, it is required to identify the file group. In most
   * cases, this field does not need to be included to identify the file group.
   *
   * <p>Contact <internal>@ if you think this field is required for your use-case.
   */
  public abstract Optional<Any> customPropertyOptional();

  /** List of {@link DataFile}s to import into the existing file group. */
  public abstract ImmutableList<DataFile> updatedDataFileList();

  /**
   * Map of inline file content that should be imported.
   *
   * <p>The Map is keyed by the {@link DataFile#fileId} that represents the file content and the
   * values are the file content contained in a {@link ByteString}.
   */
  public abstract ImmutableMap<String, FileSource> inlineFileMap();

  /** Account associated with the file group. */
  public abstract Optional<Account> accountOptional();

  public static Builder newBuilder() {
    // Set updatedDataFileList as empty by default
    return new AutoValue_ImportFilesRequest.Builder().setUpdatedDataFileList(ImmutableList.of());
  }

  /** Builder for {@link ImportFilesRequest}. */
  @AutoValue.Builder
  public abstract static class Builder {
    Builder() {}

    /**
     * Sets the name of the file group to import files into.
     *
     * <p>This is required to identify the file group.
     */
    public abstract Builder setGroupName(String groupName);

    /**
     * Sets the build id of the file group to import files into.
     *
     * <p>This is required to identify the file group.
     */
    public abstract Builder setBuildId(long buildId);

    /**
     * Sets the variant id of the file group to import files into.
     *
     * <p>This is required to identify the file group.
     */
    public abstract Builder setVariantId(String variantId);

    /**
     * Sets the custom property of the file group to import files into.
     *
     * <p>This should only be provided if the file group supports this field. Most cases do not
     * require this.
     *
     * <p>Contact <internal>@ if you think this field is required for your use-case.
     */
    public abstract Builder setCustomPropertyOptional(Optional<Any> customPropertyOptional);

    /**
     * Sets the List of inline DataFiles that should be updated in the file group.
     *
     * <p>This list can be included to update DataFiles in the existing file group identified by the
     * other parameters in the request ({@link #groupName}, {@link #buildId}, and {@link
     * #variantId}).
     *
     * <p>Files in this list are merged into the existing file group based on {@link
     * DataFile#fileId}. That is:
     *
     * <ul>
     *   <li>If a File exists with the same fileId, it is replaced by the File in this List
     *   <li>If a File does not exist with the same fileId, it is added to the file group
     * </ul>
     *
     * <p>This list is only required if inline files need to be added/updated in the existing file
     * group. If the existing file group has inline files added with {@link
     * MobileDataDownload#addFileGroup}, this list may be empty and the existing inline files that
     * need to be imported can be included in {@link ImportFilesRequest#inlineFileMap}.
     */
    public abstract Builder setUpdatedDataFileList(ImmutableList<DataFile> updatedDataFileList);

    /**
     * Sets the map of inline file content to import.
     *
     * <p>The keys of this map should be fileIds of DataFiles that need to be imported. The values
     * of the map should be FileSource.
     *
     * <p>NOTE: Key/Value pairs included in this map can references inline files already in the
     * existing file group (added via {@link MobileDataDownload#addFileGroup}) or inline files
     * included in the {@link ImportFilesRequest#updatedDataFileList}.
     */
    public abstract Builder setInlineFileMap(ImmutableMap<String, FileSource> inlineFileMap);

    /** Sets the optional account that is associated with the file group. */
    public abstract Builder setAccountOptional(Optional<Account> accountOptional);

    public abstract ImportFilesRequest build();
  }
}
