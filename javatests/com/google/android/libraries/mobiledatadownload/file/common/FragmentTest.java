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

import android.net.Uri;
import com.google.thirdparty.robolectric.GoogleRobolectricTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(GoogleRobolectricTestRunner.class)
public final class FragmentTest {

  @Test
  public void builder_empty() throws Exception {
    Fragment.Builder fragment = Fragment.builder();
    assertThat(fragment.build().toString()).isEmpty();
  }

  @Test
  public void parse_empty() throws Exception {
    Fragment fragment = Fragment.parse("");
    assertThat(fragment.toString()).isEmpty();
  }

  @Test
  public void parse_simpleParam() throws Exception {
    Fragment fragment = Fragment.parse("simple=true");
    assertThat(fragment.toString()).isEqualTo("simple=true");
    assertThat(fragment.params().get(0).key()).isEqualTo("simple");
  }

  @Test
  public void builder() throws Exception {
    Fragment fragment =
        Fragment.builder()
            .addParam(
                Fragment.Param.builder("paramKey")
                    .addValue(
                        Fragment.ParamValue.builder("paramValue")
                            .addSubParam("subparamKey", "subparamValue")))
            .build();

    assertThat(fragment.toString()).isEqualTo("paramKey=paramValue(subparamKey=subparamValue)");
  }

  @Test
  public void parse() throws Exception {
    Fragment fragment = Fragment.parse("paramKey=paramValue(subparamKey=subparamValue)");

    assertThat(fragment.toString()).isEqualTo("paramKey=paramValue(subparamKey=subparamValue)");

    Fragment.Param param = fragment.params().get(0);
    assertThat(param.key()).isEqualTo("paramKey");
    Fragment.ParamValue value = param.values().get(0);
    assertThat(value.name()).isEqualTo("paramValue");
    Fragment.SubParam subparam = value.subParams().get(0);
    assertThat(subparam.key()).isEqualTo("subparamKey");
    assertThat(subparam.value()).isEqualTo("subparamValue");
  }

  @Test
  public void parse_multipleParamsAndsubParams() throws Exception {
    Fragment fragment = Fragment.parse("k1=v1&k2=v2(sk1=sv1)&k3=v3(sk1=sv1,sk2=sv2,sk3)");

    assertThat(fragment.toString()).isEqualTo("k1=v1&k2=v2(sk1=sv1)&k3=v3(sk1=sv1,sk2=sv2,sk3)");

    Fragment.Param p1 = fragment.params().get(0);
    assertThat(p1.key()).isEqualTo("k1");
    Fragment.ParamValue v1 = p1.values().get(0);
    assertThat(v1.name()).isEqualTo("v1");
    assertThat(v1.subParams().size()).isEqualTo(0);

    Fragment.Param p2 = fragment.params().get(1);
    assertThat(p2.key()).isEqualTo("k2");
    Fragment.ParamValue v2 = p2.values().get(0);
    assertThat(v2.name()).isEqualTo("v2");
    assertThat(v2.subParams().size()).isEqualTo(1);
    Fragment.SubParam p2s = v2.subParams().get(0);
    assertThat(p2s.key()).isEqualTo("sk1");
    assertThat(p2s.value()).isEqualTo("sv1");

    Fragment.Param p3 = fragment.params().get(2);
    assertThat(p3.key()).isEqualTo("k3");
    Fragment.ParamValue v3 = p3.values().get(0);
    assertThat(v3.name()).isEqualTo("v3");
    assertThat(v3.subParams().size()).isEqualTo(3);
    Fragment.SubParam p3s1 = v3.subParams().get(0);
    assertThat(p3s1.key()).isEqualTo("sk1");
    assertThat(p3s1.hasValue()).isTrue();
    assertThat(p3s1.value()).isEqualTo("sv1");
    Fragment.SubParam p3s2 = v3.subParams().get(1);
    assertThat(p3s2.key()).isEqualTo("sk2");
    assertThat(p3s2.hasValue()).isTrue();
    assertThat(p3s2.value()).isEqualTo("sv2");
    Fragment.SubParam p3s3 = v3.subParams().get(2);
    assertThat(p3s3.key()).isEqualTo("sk3");
    assertThat(p3s3.hasValue()).isFalse();
    assertThat(p3s3.value()).isNull();
  }

  @Test
  public void builder_multipleValues() throws Exception {
    Fragment fragment =
        Fragment.builder()
            .addParam("unset")
            .addParam(Fragment.Param.builder("k1").addValue("v1"))
            .addParam(
                Fragment.Param.builder("k2")
                    .addValue("v2")
                    .addValue("v2a")
                    .addValue(Fragment.ParamValue.builder("v2b").addSubParam("sk1", "sv1")))
            .build();

    assertThat(fragment.toString()).isEqualTo("k1=v1&k2=v2+v2a+v2b(sk1=sv1)");
  }

  @Test
  public void builder_nestedMutation() throws Exception {
    Fragment.Builder fragment = Fragment.parse("k0=v0a&k1=v1&k2=v2+v2a+v2b(sk1=sv1)").toBuilder();

    fragment.findParam("k0").addValue("v0b");
    fragment.findParam("k1").findValue("v1").addSubParam("sk1", "sv1");
    fragment.findParam("k2").findValue("v2b").addSubParam("sk2", "sv2");

    assertThat(fragment.build().toString())
        .isEqualTo("k0=v0a+v0b&k1=v1(sk1=sv1)&k2=v2+v2a+v2b(sk1=sv1,sk2=sv2)");
  }

  @Test
  public void parse_multipleValues() throws Exception {
    Fragment fragment = Fragment.parse("k0=v0&k1=v1&k2=v2+v2a+v2b(sk1=sv1)");

    Fragment.Param p0 = fragment.params().get(0);
    assertThat(p0.key()).isEqualTo("k0");
    Fragment.ParamValue v0 = p0.values().get(0);
    assertThat(v0.name()).isEqualTo("v0");
    assertThat(v0.subParams().isEmpty()).isTrue();

    Fragment.Param p1 = fragment.params().get(1);
    assertThat(p1.key()).isEqualTo("k1");
    Fragment.ParamValue v1 = p1.values().get(0);
    assertThat(v1.name()).isEqualTo("v1");
    assertThat(v1.subParams().isEmpty()).isTrue();

    Fragment.Param p2 = fragment.params().get(2);
    assertThat(p2.key()).isEqualTo("k2");
    Fragment.ParamValue v2 = p2.values().get(0);
    assertThat(v2.name()).isEqualTo("v2");
    assertThat(v2.subParams().isEmpty()).isTrue();

    Fragment.ParamValue v2a = p2.values().get(1);
    assertThat(v2a.name()).isEqualTo("v2a");
    assertThat(v2a.subParams().isEmpty()).isTrue();

    Fragment.ParamValue v2b = p2.values().get(2);
    assertThat(v2b.name()).isEqualTo("v2b");
    assertThat(v2b.subParams().size()).isEqualTo(1);
    Fragment.SubParam v2bs1 = v2b.subParams().get(0);
    assertThat(v2bs1.key()).isEqualTo("sk1");
    assertThat(v2bs1.value()).isEqualTo("sv1");
  }

  @Test
  public void parse_duplicateParams() throws Exception {
    Fragment fragment = Fragment.parse("k=1&k=2");
    assertThat(fragment.params().get(0).values().get(0).name()).isEqualTo("2");
  }

  @Test
  public void parse_duplicateParamValues() throws Exception {
    Fragment fragment = Fragment.parse("k=1+1(x=y)");
    assertThat(fragment.params().get(0).values().get(0).findSubParamValue("x")).isEqualTo("y");
  }

  @Test
  public void parse_duplicatesubParams() throws Exception {
    Fragment fragment = Fragment.parse("k=1(x=y,x=z)");
    assertThat(fragment.params().get(0).values().get(0).findSubParamValue("x")).isEqualTo("z");
  }

  @Test
  public void parse_duplicateUnsetSubParams() throws Exception {
    Fragment fragment = Fragment.parse("k=1(x=y,x)");
    assertThat(fragment.params().get(0).values().get(0).findSubParam("x")).isNotNull();
    assertThat(fragment.params().get(0).values().get(0).findSubParam("x").hasValue()).isFalse();
    assertThat(fragment.params().get(0).values().get(0).findSubParamValue("x")).isNull();
  }

  @Test
  public void parse_unsetSubParam() throws Exception {
    Fragment fragment = Fragment.parse("p=v(sp1)");
    assertThat(fragment.findParam("p").findValue("v").findSubParam("sp1")).isNotNull();
    assertThat(fragment.findParam("p").findValue("v").findSubParam("sp1").hasValue()).isFalse();
    assertThat(fragment.findParam("p").findValue("v").findSubParamValue("sp1")).isNull();
  }

  @Test
  public void roundTrip() throws Exception {
    Fragment fragment = Fragment.parse("a=b&c=d(e=f,g=h,i=j)+e+f");

    assertThat(fragment.toString()).isEqualTo("a=b&c=d(e=f,g=h,i=j)+e+f");
  }

  @Test
  public void parse_illegal() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x"));
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x="));

    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("="));
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("=="));
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("=x"));

    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x=y("));
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x=y)"));
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x=y()"));

    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x=y(=)"));
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x=y(==)"));
    assertThrows(IllegalArgumentException.class, () -> Fragment.parse("x=y(=z)"));
  }

  @Test
  public void parse_weirdButNotIllegal() throws Exception {
    // Unencoded chars gets encoded
    assertThat(Fragment.parse(" =y").toString()).isEqualTo("+=y");
    assertThat(Fragment.parse(" x=y ").toString()).isEqualTo("+x=y+");
    assertThat(Fragment.parse("()=y").toString()).isEqualTo("%28%29=y");
    assertThat(Fragment.parse("x==y").toString()).isEqualTo("x=%3Dy");
    assertThat(Fragment.parse("x=y(z==)").toString()).isEqualTo("x=y(z=%3D)");

    assertThat(Fragment.parse("x=y(z=)").toString()).isEqualTo("x=y(z)");
    assertThat(Fragment.parse("+=y").toString()).isEqualTo("+=y");
    assertThat(Fragment.parse("").toString()).isEmpty();
    assertThat(Fragment.parse((String) null).toString()).isEmpty();
  }

  @Test
  public void build_escapingInvalidCharacters() throws Exception {
    Fragment fragment =
        Fragment.builder()
            .addParam(
                Fragment.Param.builder("m&m")
                    .addValue(Fragment.ParamValue.builder("2+2").addSubParam("k=", "(v)")))
            .build();
    assertThat(fragment.toString()).isEqualTo("m%26m=2%2B2(k%3D=%28v%29)");

    Fragment roundtrip = Fragment.parse(fragment.toString());
    Fragment.Param mnm = roundtrip.params().get(0);
    assertThat(mnm.key()).isEqualTo("m&m");
    Fragment.ParamValue twoptwo = mnm.values().get(0);
    assertThat(twoptwo.name()).isEqualTo("2+2");
    Fragment.SubParam pqp = twoptwo.subParams().get(0);
    assertThat(pqp.key()).isEqualTo("k=");
    assertThat(pqp.value()).isEqualTo("(v)");
  }

  @Test
  public void toBuilder_shouldMakeDefensiveCopy() throws Exception {
    Fragment fragment = Fragment.parse("a=b(c=d)");
    Fragment.Builder fragmentBuilder = fragment.toBuilder();
    assertThat(fragment.toString()).isEqualTo("a=b(c=d)");
    assertThat(fragmentBuilder.build().toString()).isEqualTo("a=b(c=d)");

    fragmentBuilder.addParam(Fragment.Param.builder("X").addValue("XX"));
    fragmentBuilder.findParam("a").addValue("Y");
    fragmentBuilder.findParam("a").findValue("b").addSubParam("Z", "ZZ");

    assertThat(fragment.toString()).isEqualTo("a=b(c=d)");
    assertThat(fragmentBuilder.build().toString()).isEqualTo("a=b(c=d,Z=ZZ)+Y&X=XX");
  }

  @Test
  public void uri_withValidCharacters() throws Exception {
    String encodedFragmentString = "a=b+c(d=e)";
    Uri uri = Uri.parse("a://b/c").buildUpon().encodedFragment(encodedFragmentString).build();
    assertThat(uri.toString()).isEqualTo("a://b/c#a=b+c(d=e)");
    assertThat(uri.getEncodedFragment()).isEqualTo(encodedFragmentString);
    Fragment roundtrip = Fragment.parse(uri);
    Fragment.Param a = roundtrip.params().get(0);
    assertThat(a.key()).isEqualTo("a");
    Fragment.ParamValue b = a.values().get(0);
    assertThat(b.name()).isEqualTo("b");
    Fragment.ParamValue c = a.values().get(1);
    assertThat(c.name()).isEqualTo("c");
    Fragment.SubParam de = c.subParams().get(0);
    assertThat(de.key()).isEqualTo("d");
    assertThat(de.value()).isEqualTo("e");
  }

  @Test
  public void uri_withInvalidCharacters() throws Exception {
    String encodedFragmentString = "m%26m=2%2B2(k%3D=%28v%29)";
    Uri uri = Uri.parse("a://b/c").buildUpon().encodedFragment(encodedFragmentString).build();
    assertThat(uri.toString()).isEqualTo("a://b/c#m%26m=2%2B2(k%3D=%28v%29)");
    assertThat(uri.getEncodedFragment()).isEqualTo(encodedFragmentString);
    Fragment roundtrip = Fragment.parse(uri);
    Fragment.Param mnm = roundtrip.params().get(0);
    assertThat(mnm.key()).isEqualTo("m&m");
    Fragment.ParamValue twoptwo = mnm.values().get(0);
    assertThat(twoptwo.name()).isEqualTo("2+2");
    Fragment.SubParam pqp = twoptwo.subParams().get(0);
    assertThat(pqp.key()).isEqualTo("k=");
    assertThat(pqp.value()).isEqualTo("(v)");
  }
}
