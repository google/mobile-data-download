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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.checkerframework.checker.nullness.qual.Nullable;

/** An object that contains either one of the left field or right field, but not both. */
public final class Either<A, B> {

  private final boolean hasLeft;
  private final @Nullable A left;
  private final @Nullable B right;

  private Either(boolean hasLeft, @Nullable A left, @Nullable B right) {
    this.hasLeft = hasLeft;
    this.left = left;
    this.right = right;
  }

  public static <A, B> Either<A, B> makeLeft(A left) {
    return new Either<>(true, left, null);
  }

  public static <A, B> Either<A, B> makeRight(B right) {
    return new Either<>(false, null, right);
  }

  public boolean hasLeft() {
    return hasLeft;
  }

  public boolean hasRight() {
    return !hasLeft;
  }

  public @Nullable A getLeft() {
    if (!hasLeft()) {
      throw new IllegalStateException("Either was not left");
    }
    return left;
  }

  public @Nullable B getRight() {
    if (!hasRight()) {
      throw new IllegalStateException("Either was not right");
    }
    return right;
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean equals(@Nullable Object obj) {
    if (!(obj instanceof Either)) {
      return false;
    }
    Either<A, B> either = (Either<A, B>) obj;
    if (hasLeft()) {
      return either.hasLeft() && equals(getLeft(), either.getLeft());
    } else {
      return either.hasRight() && equals(getRight(), either.getRight());
    }
  }

  /** Compares two List-based Either by (a copy of) their sorted inner List. */
  public static <A, B> boolean sortedEquals(
      @Nullable Either<List<A>, B> first,
      @Nullable Either<List<A>, B> second,
      Comparator<A> comparatorForSorting) {
    // Only sort if the Lists will actually be compared. This uses final variable "left" rather than
    // getLeft() (which "could" change between invocations) to satisfy the Nullness Checker.
    if (first != null
        && first.hasLeft()
        && first.left != null
        && second != null
        && second.hasLeft()
        && second.left != null) {
      List<A> sortedFirstLeft = new ArrayList<>(first.left);
      List<A> sortedSecondLeft = new ArrayList<>(second.left);
      Collections.sort(sortedFirstLeft, comparatorForSorting);
      Collections.sort(sortedSecondLeft, comparatorForSorting);
      return sortedFirstLeft.equals(sortedSecondLeft);
    }

    return equals(first, second);
  }

  // Inline definition of Objects.equals() since it's not available on all API levels.
  public static boolean equals(@Nullable Object a, @Nullable Object b) {
    return (a == b) || (a != null && a.equals(b));
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(new Object[] {hasLeft, left, right});
  }
}
