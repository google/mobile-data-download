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
package com.google.android.libraries.mobiledatadownload.file.backends;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.common.base.Splitter;
import com.google.common.io.BaseEncoding;
import java.util.List;

/** Helper class for "blobstore" URIs. */
public final class BlobUri {
  // Uri path constants
  public static final String SCHEME = "blobstore";
  private static final String LEASE_URI_SUFFIX = ".lease";
  private static final String CHECKSUM_SEPARATOR = ".";
  private static final String ALL_LEASES_PATH = "*" + LEASE_URI_SUFFIX;
  private static final int PATH_SIZE = 1;
  // Uri query constants
  private static final int QUERY_PARAMETERS = 1; // A single query element called expiryDateSecs
  private static final String EXPIRY_DATE_QUERY_KEY = "expiryDateSecs";
  // MalformedException message strings
  private static final String EXPECTED_BLOB_URI_PATH = "<non_empty_checksum>";
  private static final String EXPECTED_LEASE_URI_PATH = "<non_empty_checksum>.lease";
  private static final String EXPECTED_LEASE_URI_QUERY = "expiryDateSecs=<expiryDateSecs>";

  private static final Splitter SPLITTER = Splitter.on(CHECKSUM_SEPARATOR);

  /** Returns a "blobstore" scheme URI. */
  public static Builder builder(Context context) {
    return new Builder(context);
  }

  private BlobUri() {}

  static void validateUri(Uri uri) throws MalformedUriException {
    validatePath(uri);
    validateQuery(uri);
  }

  /**
   * Validates the path of the "blobstore" scheme URI.
   *
   * <p>Theare only two permitted paths:
   *
   * <ul>
   *   <li><non_empty_checksum>
   *   <li><non_empty_checksum>.lease
   * </ul>
   */
  private static void validatePath(Uri uri) throws MalformedUriException {
    List<String> pathSegments = uri.getPathSegments();
    if (pathSegments.size() != PATH_SIZE || !hasValidChecksumExtension(pathSegments.get(0))) {
      throw new MalformedUriException(
          String.format(
              "The uri is malformed, expected %s or %s but found %s",
              EXPECTED_BLOB_URI_PATH, EXPECTED_LEASE_URI_PATH, uri.getPath()));
    }
  }

  private static boolean hasValidChecksumExtension(String path) {
    return SPLITTER.splitToList(path).size() == 1
        || (isLeaseUri(path) && !TextUtils.equals(path, LEASE_URI_SUFFIX));
  }

  /** Returns true if the path is of type "<checksum>.lease". */
  static boolean isLeaseUri(String path) {
    return path.endsWith(LEASE_URI_SUFFIX);
  }

  /** Returns true if the path matches "*.lease". */
  static boolean isAllLeasesUri(String path) {
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    return TextUtils.equals(path, ALL_LEASES_PATH);
  }

  /**
   * If available, validates the query part of the "blobstore" scheme URI.
   *
   * <p>There is one permitted query parameter: expiryDateSecs=<expiryDateSecs>.
   */
  private static void validateQuery(Uri uri) throws MalformedUriException {
    if (TextUtils.isEmpty(uri.getQuery())) {
      return;
    }
    if (uri.getQueryParameterNames().size() != QUERY_PARAMETERS
        || uri.getQueryParameter(EXPIRY_DATE_QUERY_KEY) == null) {
      throw new MalformedUriException(
          String.format(
              "The uri query is malformed, expected %s but found query %s",
              EXPECTED_LEASE_URI_QUERY, uri.getQuery()));
    }
  }

  /**
   * Returns the checksum bytes encoded in the {@code path}.
   *
   * <p>To decode the bytes from the path, it uses the same encoding used by {@code //
   * com.google.android.libraries.mobiledatadownload.internal.downloader.FileValidator}.
   */
  static byte[] getChecksum(String path) {
    if (path.startsWith("/")) {
      path = path.substring(1);
    }
    return BaseEncoding.base16().lowerCase().decode(SPLITTER.splitToList(path).get(0));
  }

  /* Parses the {@code query} and returns the encoded {@code expiryDateSecs}. */
  static long getExpiryDateSecs(Uri uri) throws MalformedUriException {
    String query = uri.getQuery();
    if (TextUtils.isEmpty(query)) {
      throw new MalformedUriException(
          String.format("The uri query is null or empty, expected %s", EXPECTED_LEASE_URI_QUERY));
    }
    String expiryDateSecsString = uri.getQueryParameter(EXPIRY_DATE_QUERY_KEY);
    if (expiryDateSecsString == null) {
      throw new MalformedUriException(
          String.format(
              "The uri query is malformed, expected %s but found %s",
              EXPECTED_LEASE_URI_QUERY, query));
    }
    long expiryDateSecs = Long.parseLong(expiryDateSecsString);
    return expiryDateSecs;
  }

  /** A builder for "blobstore" scheme Uris. */
  public static class Builder {
    private String path = "";
    private String packageName = "";
    private long expiryDateSecs;

    private Builder(Context context) {
      // TODO(b/149260496): remove/change meaning to packageName
      this.packageName = context.getPackageName();
    }

    public Builder setBlobParameters(String checksum) {
      path = checksum;
      return this;
    }

    public Builder setLeaseParameters(String checksum, long expiryDateSecs) {
      path = checksum + LEASE_URI_SUFFIX;
      this.expiryDateSecs = expiryDateSecs;
      return this;
    }

    public Builder setAllLeasesParameters() {
      path = ALL_LEASES_PATH;
      return this;
    }

    public Uri build() throws MalformedUriException {
      Uri.Builder uriBuilder = new Uri.Builder().scheme(SCHEME).authority(packageName).path(path);
      if (isLeaseUri(path) && !isAllLeasesUri(path)) {
        uriBuilder.appendQueryParameter(EXPIRY_DATE_QUERY_KEY, String.valueOf(expiryDateSecs));
      }
      Uri uri = uriBuilder.build();
      validateUri(uri);
      return uri;
    }
  }
}
