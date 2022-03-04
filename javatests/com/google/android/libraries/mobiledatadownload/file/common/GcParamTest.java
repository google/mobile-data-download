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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import java.util.Date;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public class GcParamTest {

  @Test
  public void none_shouldHaveNoneTagAndNoExpiration() {
    GcParam none = GcParam.none();
    assertThat(none).isNotNull();
    assertThat(none.isExpiresAt()).isFalse();
    assertThrows(IllegalStateException.class, () -> none.getExpiration());
    assertThat(none.isNone()).isTrue();
  }

  @Test
  public void expiresAt_shouldHaveExpiresAtTagAndExpiration() {

    Date date1 = new Date(1L);
    Date date2 = new Date(2L);
    GcParam expiresAt1 = GcParam.expiresAt(date1);
    GcParam expiresAt2 = GcParam.expiresAt(date2);

    assertThat(expiresAt1).isNotNull();
    assertThat(expiresAt2).isNotNull();
    assertThat(expiresAt1.isNone()).isFalse();
    assertThat(expiresAt2.isNone()).isFalse();
    assertThat(expiresAt1.isExpiresAt()).isTrue();
    assertThat(expiresAt2.isExpiresAt()).isTrue();
    assertThat(expiresAt1.getExpiration()).isEqualTo(date1);
    assertThat(expiresAt2.getExpiration()).isEqualTo(date2);
  }

  @Test
  public void equals_shouldCompareDataMembers() {

    GcParam expiresAt1 = GcParam.expiresAt(new Date(1L));
    GcParam expiresAt2 = GcParam.expiresAt(new Date(2L));
    GcParam expiresAt1b = GcParam.expiresAt(new Date(1L));
    GcParam none1 = GcParam.none();
    GcParam none2 = GcParam.none();

    assertThat(expiresAt1).isNotEqualTo(null);
    assertThat(expiresAt2).isNotEqualTo(null);
    assertThat(expiresAt1b).isNotEqualTo(null);
    assertThat(none1).isNotEqualTo(null);
    assertThat(none2).isNotEqualTo(null);

    assertThat(expiresAt1).isNotEqualTo(expiresAt2);
    assertThat(expiresAt1).isEqualTo(expiresAt1b);
    assertThat(expiresAt1).isNotEqualTo(none1);
    assertThat(expiresAt1).isNotEqualTo(none2);

    assertThat(expiresAt2).isNotEqualTo(expiresAt1);
    assertThat(expiresAt2).isNotEqualTo(expiresAt1b);
    assertThat(expiresAt2).isNotEqualTo(none1);
    assertThat(expiresAt2).isNotEqualTo(none2);

    assertThat(expiresAt1b).isEqualTo(expiresAt1);
    assertThat(expiresAt1b).isNotEqualTo(expiresAt2);
    assertThat(expiresAt1b).isNotEqualTo(none1);
    assertThat(expiresAt1b).isNotEqualTo(none2);

    assertThat(none1).isNotEqualTo(expiresAt1);
    assertThat(none1).isNotEqualTo(expiresAt2);
    assertThat(none1).isNotEqualTo(expiresAt1b);
    assertThat(none1).isEqualTo(none2);

    assertThat(none2).isNotEqualTo(expiresAt1);
    assertThat(none2).isNotEqualTo(expiresAt2);
    assertThat(none2).isNotEqualTo(expiresAt1b);
    assertThat(none2).isEqualTo(none1);
  }
}
