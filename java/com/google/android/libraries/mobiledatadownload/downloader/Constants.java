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
package com.google.android.libraries.mobiledatadownload.downloader;

/** Network related constants. */
public final class Constants {

  private Constants() {}

  /** The HTTP {@code HEAD} request method name. */
  public static final String HEAD_REQUEST_METHOD = "HEAD";

  /** The HTTP {@code ETag} header field name. This is used to detect content change. */
  public static final String ETAG_HEADER = "ETag";

  /**
   * The HTTP {@code If-None-Match} header field name. This is used to send ETag to server to detect
   * content change.
   */
  public static final String IF_NONE_MATCH_HEADER = "If-None-Match";

  public static final int HTTP_RESPONSE_OK = 200;
  public static final int HTTP_RESPONSE_NOT_MODIFIED = 304;
  public static final int HTTP_RESPONSE_NOT_FOUND = 404;
}
