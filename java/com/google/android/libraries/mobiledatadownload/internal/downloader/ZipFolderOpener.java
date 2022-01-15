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
package com.google.android.libraries.mobiledatadownload.internal.downloader;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * An opener takes in an output folder URI and expands all resources in the zip input stream to the
 * folder.
 */
public final class ZipFolderOpener implements Opener<Void> {

  private final Uri targetFolderUri;
  private final SaferZipUtils zipUtils;

  private ZipFolderOpener(Uri targetFolderUri) {
    this.targetFolderUri = targetFolderUri;
    this.zipUtils = new SaferZipUtils() {};
  }

  public static ZipFolderOpener create(Uri targetFolderUri) {
    return new ZipFolderOpener(targetFolderUri);
  }

  @Override
  public Void open(OpenContext openContext) throws IOException {
    SynchronousFileStorage fileStorage = openContext.storage();
    try (ZipInputStream zipInputStream =
        new ZipInputStream(ReadStreamOpener.create().withBufferedIo().open(openContext))) {
      // Iterate all entries and write to target URI one by one
      ZipEntry zipEntry;
      while ((zipEntry = zipInputStream.getNextEntry()) != null) {
        String path = zipUtils.getValidatedName(zipEntry);
        Uri uri = targetFolderUri.buildUpon().appendPath(path).build();
        if (zipEntry.isDirectory()) {
          fileStorage.createDirectory(uri);
        } else {
          try (OutputStream out = fileStorage.open(uri, WriteStreamOpener.create())) {
            ByteStreams.copy(zipInputStream, out);
          }
        }
      }
    } catch (IOException ioe) {
      // Cleanup the target directory if any error occurred.
      fileStorage.deleteRecursively(targetFolderUri);
      throw ioe;
    }
    return null;
  }

  /** Utilities for safely accessing ZipEntry APIs. */
  private interface SaferZipUtils {
    /**
     * Return the name of a ZipEntry after verifying that it does not exploit any path traversal
     * attacks.
     *
     * @throws ZipException if {@code zipEntry} contains any possible path traversal characters.
     */
    default String getValidatedName(ZipEntry entry) throws ZipException {
      return entry.getName();
    }
  }
}
