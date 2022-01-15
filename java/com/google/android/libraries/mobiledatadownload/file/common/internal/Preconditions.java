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

import javax.annotation.Nullable;

/**
 * Static convenience methods that help a method check whether it was invoked correctly.
 *
 * <p>This is a trimmed down version of the Preconditions class under Guava, replicated here to
 * avoid the heavy dependency.
 */
public final class Preconditions {

  /**
   * Ensures the truth of an expression involving one or more parameters to the calling method.
   *
   * @param expression a boolean expression
   * @param errorMessageTemplate a template for the exception message should the check fail. The
   *     message is formed by replacing each {@code %s} placeholder in the template with an
   *     argument. These are matched by position - the first {@code %s} gets {@code
   *     errorMessageArgs[0]}, etc.
   * @param errorMessageArgs the arguments to be substituted into the message template. Arguments
   *     are converted to strings using {@link String#valueOf(Object)}.
   * @throws IllegalArgumentException if {@code expression} is false
   */
  public static void checkArgument(
      boolean expression,
      @Nullable String errorMessageTemplate,
      @Nullable Object... errorMessageArgs) {
    if (!expression) {
      throw new IllegalArgumentException(format(errorMessageTemplate, errorMessageArgs));
    }
  }

  /**
   * Ensures the truth of an expression involving the state of the calling instance, but not
   * involving any parameters to the calling method.
   *
   * @param expression a boolean expression
   * @param errorMessageTemplate a template for the exception message should the check fail. The
   *     message is formed by replacing each {@code %s} placeholder in the template with an
   *     argument. These are matched by position - the first {@code %s} gets {@code
   *     errorMessageArgs[0]}, etc.
   * @param errorMessageArgs the arguments to be substituted into the message template. Arguments
   *     are converted to strings using {@link String#valueOf(Object)}.
   * @throws IllegalStateException if {@code expression} is false
   */
  public static void checkState(
      boolean expression,
      @Nullable String errorMessageTemplate,
      @Nullable Object... errorMessageArgs) {
    if (!expression) {
      throw new IllegalStateException(format(errorMessageTemplate, errorMessageArgs));
    }
  }

  private static String format(String template, Object... args) {
    // We do not handle null parameters gracefully like Guava. If we find that we are passing in
    // invalid parameters we can do more checks here.
    return String.format(template, args);
  }

  private Preconditions() {}
}
