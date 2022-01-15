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

/** Delta file name utility class. */
public class FileNameUtil {

  public static final String NAME_SEPARATOR = "_";

  /**
   * For cases that the downloaded file is different than the final file, generate a temporary file
   * for download and name it with a suffix of the downloaded file checksum to avoid naming
   * conflicts.
   */
  public static String getTempFileNameWithDownloadedFileChecksum(String fileName, String checksum) {
    return fileName + NAME_SEPARATOR + checksum;
  }

  /** Get the final file name by removing the temporary downloaded file suffix as "_checksum". */
  public static Uri getFinalFileUriWithTempDownloadedFile(Uri downloadedFileUri) {
    String serializedDeltaFileUri = downloadedFileUri.toString();
    return Uri.parse(
        serializedDeltaFileUri.substring(0, serializedDeltaFileUri.lastIndexOf(NAME_SEPARATOR)));
  }
}
