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
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.internal.logging.LogUtil;
import com.google.android.libraries.mobiledatadownload.internal.util.FileGroupUtil;
import com.google.mobiledatadownload.internal.MetadataProto.DataFile;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.annotation.Nullable;

/** Util class that validate the downloaded file. */
public final class FileValidator {
  private static final String TAG = "FileValidator";

  private static final char[] HEX_LOWERCASE = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  // <internal>
  private FileValidator() {}

  /**
   * Returns if the file checksum verification passes.
   *
   * @param fileUri - the uri of the file to calculate a sha1 hash for.
   * @param fileChecksum - the expected file checksum
   */
  public static boolean verifyChecksum(
      SynchronousFileStorage fileStorage, Uri fileUri, String fileChecksum) {
    String digest = FileValidator.computeSha1Digest(fileStorage, fileUri);
    return digest.equals(fileChecksum);
  }

  /**
   * Returns sha1 hash of file, empty string if unable to read file.
   *
   * @param uri - the uri of the file to calculate a sha1 hash for.
   */
  // TODO(b/139472295): convert this to a MobStore Opener.
  public static String computeSha1Digest(SynchronousFileStorage fileStorage, Uri uri) {
    try (InputStream inputStream = fileStorage.open(uri, ReadStreamOpener.create())) {
      return computeDigest(inputStream, "SHA1");
    } catch (IOException e) {
      // TODO(b/118137672): reconsider on the swallowed exception.
      LogUtil.e("%s: Failed to open file, uri = %s", TAG, uri);
      return "";
    }
  }

  /** Compute the SHA1 of the input string. */
  public static String computeSha1Digest(String input) {
    MessageDigest messageDigest = getMessageDigest("SHA1");
    if (messageDigest == null) {
      return "";
    }

    byte[] bytes = input.getBytes();
    messageDigest.update(bytes, 0, bytes.length);
    return bytesToStringLowercase(messageDigest.digest());
  }

  /**
   * Returns sha256 hash of file, empty string if unable to read file.
   *
   * @param uri - the uri of the file to calculate a sha256 hash for.
   */
  public static String computeSha256Digest(SynchronousFileStorage fileStorage, Uri uri) {
    try (InputStream inputStream = fileStorage.open(uri, ReadStreamOpener.create())) {
      return computeDigest(inputStream, "SHA-256");
    } catch (IOException e) {
      // TODO(b/118137672): reconsider on the swallowed exception.
      LogUtil.e("%s: Failed to open file, uri = %s", TAG, uri);
      return "";
    }
  }

  // Caller is responsible for opening and closing stream.
  private static String computeDigest(InputStream inputStream, String algorithm)
      throws IOException {
    MessageDigest messageDigest = getMessageDigest(algorithm);
    if (messageDigest == null) {
      return "";
    }

    byte[] bytes = new byte[8192];

    int byteCount = inputStream.read(bytes);
    while (byteCount != -1) {
      messageDigest.update(bytes, 0, byteCount);
      byteCount = inputStream.read(bytes);
    }
    return bytesToStringLowercase(messageDigest.digest());
  }

  /**
   * Throws {@link DownloadException} if the downloaded file doesn't exist, or the SHA1 hash of the
   * file checksum doesn't match.
   */
  public static void validateDownloadedFile(
      SynchronousFileStorage fileStorage, DataFile dataFile, Uri fileUri, String checksum)
      throws DownloadException {
    try {
      if (!fileStorage.exists(fileUri)) {
        LogUtil.e(
            "%s: Downloaded file %s is not present at %s",
            TAG, FileGroupUtil.getFileChecksum(dataFile), fileUri);
        throw DownloadException.builder()
            .setDownloadResultCode(DownloadResultCode.DOWNLOADED_FILE_NOT_FOUND_ERROR)
            .build();
      }
      if (dataFile.getChecksumType() == DataFile.ChecksumType.NONE) {
        return;
      }
      if (!verifyChecksum(fileStorage, fileUri, checksum)) {
        LogUtil.e(
            "%s: Downloaded file at uri = %s, checksum = %s verification failed",
            TAG, fileUri, checksum);
        throw DownloadException.builder()
            .setDownloadResultCode(DownloadResultCode.DOWNLOADED_FILE_CHECKSUM_MISMATCH_ERROR)
            .build();
      }
    } catch (IOException e) {
      LogUtil.e(
          e,
          "%s: Failed to validate download file %s",
          TAG,
          FileGroupUtil.getFileChecksum(dataFile));
      throw DownloadException.builder()
          .setDownloadResultCode(DownloadResultCode.UNABLE_TO_VALIDATE_DOWNLOAD_FILE_ERROR)
          .setCause(e)
          .build();
    }
  }

  @Nullable
  private static MessageDigest getMessageDigest(String hashAlgorithm) {
    try {
      MessageDigest messageDigest = MessageDigest.getInstance(hashAlgorithm);
      if (messageDigest != null) {
        return messageDigest;
      }
    } catch (NoSuchAlgorithmException e) {
      // Do nothing.
    }
    return null;
  }

  private static String bytesToStringLowercase(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    int j = 0;
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      hexChars[j++] = HEX_LOWERCASE[v >>> 4];
      hexChars[j++] = HEX_LOWERCASE[v & 0x0F];
    }
    return new String(hexChars);
  }
}
