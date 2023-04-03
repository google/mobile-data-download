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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Iterator;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ExponentialBackoffIteratorTest {

  @Test
  public void testNegativeInitialBackoff() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ExponentialBackoffIterator.create(-1, 0));
  }

  @Test
  public void testZeroInitialBackoff() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ExponentialBackoffIterator.create(0, 0));
  }

  @Test
  public void testUpperBoundLessThanInitialBackoff() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> ExponentialBackoffIterator.create(1, 0));
  }

  @Test
  public void testLargeInitialBackoffWillNotOverflow() throws Exception {
    Iterator<Long> iterator = ExponentialBackoffIterator.create(Long.MAX_VALUE - 1, Long.MAX_VALUE);

    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(Long.MAX_VALUE - 1);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(Long.MAX_VALUE);
  }

  @Test
  public void testExponentialBackoffBackoffs() throws Exception {
    Iterator<Long> iterator = ExponentialBackoffIterator.create(10, 1000);

    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(10);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(20);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(40);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(80);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(160);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(320);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(640);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(1000);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(1000);
    assertThat(iterator.hasNext()).isTrue();
    assertThat(iterator.next()).isEqualTo(1000);
  }
}
