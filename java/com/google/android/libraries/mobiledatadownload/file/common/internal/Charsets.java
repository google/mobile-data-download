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
package com.google.android.libraries.mobiledatadownload.file.common.internal;

import java.nio.charset.Charset;

/**
 * Contains constant definitions for the standard {@link Charset} instances, which are guaranteed to
 * be supported by all Java platform implementations.
 *
 * <p>This is intended to be used in lieu of {@link java.nio.charset.StandardCharsets}, which was
 * added in API level 19 and thus does not support Jellybean devices. It is a trimmed-down fork of
 * the Charsets class under Guava, replicated here to avoid the heavy dependency.
 */
public final class Charsets {

  /** US-ASCII: seven-bit ASCII, the Basic Latin block of the Unicode character set (ISO646-US). */
  public static final Charset US_ASCII = Charset.forName("US-ASCII");

  /** ISO Latin Alphabet No. */
  public static final Charset ISO_8859_1 = Charset.forName("ISO-8859-1");

  /** UTF-8: eight-bit UCS Transformation Format. */
  public static final Charset UTF_8 = Charset.forName("UTF-8");

  private Charsets() {}
}
