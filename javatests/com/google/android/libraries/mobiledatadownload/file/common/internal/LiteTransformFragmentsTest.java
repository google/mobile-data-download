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

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.common.Fragment;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class LiteTransformFragmentsTest {

  @Test
  public void parseAbsentTransformFragment_yieldsEmpty() throws Exception {
    Uri uri = Uri.parse("scheme:path");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).isEmpty();
  }

  @Test
  public void parseEmptyTransformFragment_yieldsEmpty() throws Exception {
    Uri uri = Uri.parse("scheme:path#");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).isEmpty();
  }

  @Test
  public void parseNonTransformFragment_yieldsEmpty() throws Exception {
    Uri uri = Uri.parse("scheme:path#nontransform");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).isEmpty();
  }

  @Test
  public void parseSimpleTransformFragment_yieldsSpec() throws Exception {
    Uri uri = Uri.parse("scheme:path#transform=simple");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).containsExactly("simple");
  }

  @Test
  public void parseMixedTransformFragment_yieldsSpec() throws Exception {
    Uri uri = Uri.parse("scheme:path#transform=M1X_d");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).containsExactly("M1X_d");
  }

  @Test
  public void parseEncodedTransformFragment_yieldsInvalidSpec() throws Exception {
    // Trailing "%3D" is ignored.
    Uri uri = Uri.parse("scheme:path#transform=INVALID%3D");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).containsExactly("INVALID");
  }

  @Test
  public void parseTransformBeforeOtherFragment_yieldsSpec() throws Exception {
    Uri uri = Uri.parse("scheme:path#transform=beforeother&other");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).containsExactly("beforeother");
  }

  @Test
  public void parseTransformAfterOtherFragment_yieldsEmpty() throws Exception {
    Uri uri = Uri.parse("scheme:path#nontransform&transform=afterother");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).isEmpty();
  }

  @Test
  public void parseMultipleTransformFragments_yieldsAllSpecs() throws Exception {
    Uri uri = Uri.parse("scheme:path#transform=first+second+third");
    assertThat(LiteTransformFragments.parseTransformNames(uri))
        .containsExactly("first", "second", "third");
  }

  @Test
  public void parseTransformFragmentWithSubparams_yieldsJustName() throws Exception {
    Uri uri = Uri.parse("scheme:path#transform=withparams(foo=bar)");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).containsExactly("withparams");
  }

  @Test
  public void parseMultipleTransformFragmentsWithSubparams_yieldsAllNames() throws Exception {
    Uri uri = Uri.parse("scheme:path#transform=first(foo=bar)+second(yada=yada,x=y)+third(xxx)");
    assertThat(LiteTransformFragments.parseTransformNames(uri))
        .containsExactly("first", "second", "third");
  }

  @Test
  public void parseTransformFragmentWithEncodedSubparams_yieldsJustName() throws Exception {
    Uri uri = Uri.parse("scheme:path#transform=withencoded(k%3D=%28v%29)");
    assertThat(LiteTransformFragments.parseTransformNames(uri)).containsExactly("withencoded");
  }

  @Test
  public void joinEmpty_yieldNil() throws Exception {
    String encodedFragment = LiteTransformFragments.joinTransformSpecs(ImmutableList.of());
    assertThat(encodedFragment).isNull();

    // NOTE: Android Uri treats null as removing the fragment.
    Uri uri = Uri.parse("scheme:path#REMOVED").buildUpon().encodedFragment(encodedFragment).build();
    assertThat(uri.toString()).isEqualTo("scheme:path");
  }

  @Test
  public void joinSimple_yieldFragment() throws Exception {
    assertThat(LiteTransformFragments.joinTransformSpecs(ImmutableList.of("simple")))
        .isEqualTo("transform=simple");
  }

  @Test
  public void joinMultiple_yieldFragment() throws Exception {
    assertThat(LiteTransformFragments.joinTransformSpecs(ImmutableList.of("first", "second")))
        .isEqualTo("transform=first+second");
  }

  @Test
  public void joinMultipleWithParams_yieldEncodedFragment() throws Exception {
    String fragment =
        LiteTransformFragments.joinTransformSpecs(
            ImmutableList.of("first(foo=bar)", "second(k%3D=%28v%29)"));
    assertThat(fragment).isEqualTo("transform=first(foo=bar)+second(k%3D=%28v%29)");
    Uri uri = Uri.parse("scheme:path").buildUpon().encodedFragment(fragment).build();

    // Run it through the full fragment parser to ensure output is valid.
    Fragment fullFragment = Fragment.parse(uri);
    Fragment.Param transform = fullFragment.params().get(0);
    assertThat(transform.key()).isEqualTo("transform");

    Fragment.ParamValue first = transform.values().get(0);
    assertThat(first.name()).isEqualTo("first");
    Fragment.SubParam foo = first.subParams().get(0);
    assertThat(foo.key()).isEqualTo("foo");
    assertThat(foo.value()).isEqualTo("bar");

    Fragment.ParamValue second = transform.values().get(1);
    assertThat(second.name()).isEqualTo("second");
    Fragment.SubParam kequal = second.subParams().get(0);
    assertThat(kequal.key()).isEqualTo("k=");
    assertThat(kequal.value()).isEqualTo("(v)");
  }
}
