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
package com.google.android.libraries.mobiledatadownload.file.common;

// TODO: investigate using org.joda.time.instant instead of Date
import java.util.Date;

/** Specification of GC behavior for a given file. */
public final class GcParam {

  private static enum Code {
    NONE,
    EXPIRES_AT
  }

  private static final GcParam NONE_PARAM = new GcParam(Code.NONE, null);

  private final Code code;
  private final Date date;

  private GcParam(Code code, Date date) {
    this.code = code;
    this.date = date;
  }

  /**
   * @return A GcParam for expiration at the given date.
   */
  public static GcParam expiresAt(Date date) {
    return new GcParam(Code.EXPIRES_AT, date);
  }

  /**
   * @return the "none" GcParam.
   */
  public static GcParam none() {
    return NONE_PARAM;
  }

  /**
   * @return the expiration associated with {@code this}.
   * @throws IllegalStateException if the GcParam is not an expiration
   */
  public Date getExpiration() {
    if (!isExpiresAt()) {
      throw new IllegalStateException("GcParam is not an expiration");
    }
    return date;
  }

  /**
   * @return whether {@code this} is an expiration GcParam
   */
  public boolean isExpiresAt() {
    return code == Code.EXPIRES_AT;
  }

  /**
   * @return whether {@code this} is the none GcParam
   */
  public boolean isNone() {
    return code == Code.NONE;
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof GcParam)) {
      return false;
    }
    GcParam that = (GcParam) obj;
    return (this.code == that.code)
        && (this.isNone() || this.date.getTime() == that.date.getTime());
  }

  @Override
  public int hashCode() {
    return isNone() ? 0 : date.hashCode();
  }
}
