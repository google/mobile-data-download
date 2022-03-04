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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.util.Comparator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class EitherTest {

  // ImmutableList ensures the Either List isn't modified by sortedEquals.
  private static final ImmutableList<String> LIST = ImmutableList.of("a", "b", "c");
  private static final ImmutableList<String> JUMBLED_LIST = ImmutableList.of("c", "a", "b");
  private static final ImmutableList<String> OTHER_LIST = ImmutableList.of("c");

  private static final Comparator<String> COMPARATOR = Ordering.natural();

  @Test
  public void sortedEquals_comparesSortedList() throws Exception {
    assertThat(Either.sortedEquals(Either.makeLeft(LIST), Either.makeLeft(LIST), COMPARATOR))
        .isTrue();
    assertThat(
            Either.sortedEquals(Either.makeLeft(LIST), Either.makeLeft(JUMBLED_LIST), COMPARATOR))
        .isTrue();

    assertThat(Either.sortedEquals(Either.makeLeft(LIST), Either.makeLeft(OTHER_LIST), COMPARATOR))
        .isFalse();
  }

  @Test
  public void sortedEquals_handlesNull() throws Exception {
    assertThat(Either.sortedEquals(Either.makeLeft(LIST), null, COMPARATOR)).isFalse();
    assertThat(Either.sortedEquals(Either.makeLeft(LIST), Either.makeLeft(null), COMPARATOR))
        .isFalse();

    assertThat(Either.sortedEquals(Either.makeLeft(null), Either.makeLeft(null), COMPARATOR))
        .isTrue();
    assertThat(Either.sortedEquals(null, null, COMPARATOR)).isTrue();
  }
}
