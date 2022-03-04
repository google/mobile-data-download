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
package com.google.android.libraries.mobiledatadownload.file.transforms;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.mobiledatadownload.TransformProto;
import com.google.protobuf.contrib.android.ProtoParsers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class TransformProtosTest {

  @Test
  public void emptyProto_producesEmptyTransform() throws Exception {
    TransformProto.Transforms emptyTransforms = TransformProto.Transforms.getDefaultInstance();
    String fragment = TransformProtos.toEncodedFragment(emptyTransforms);
    assertThat(fragment).isNull();
    assertThat(
            Uri.parse("http://foo.bar#transform=foo")
                .buildUpon()
                .encodedFragment(fragment)
                .build()
                .toString())
        .isEqualTo("http://foo.bar");
  }

  @Test
  public void missingTransform_throwsException() throws Exception {
    TransformProto.Transforms invalidTransform =
        TransformProto.Transforms.newBuilder()
            .addTransform(TransformProto.Transform.getDefaultInstance())
            .build();

    assertThrows(
        IllegalArgumentException.class, () -> TransformProtos.toEncodedFragment(invalidTransform));
  }

  @Test
  public void compressProto_producesCompressTransform() throws Exception {
    TransformProto.Transforms transformsProto =
        TransformProto.Transforms.newBuilder()
            .addTransform(
                TransformProto.Transform.newBuilder()
                    .setCompress(TransformProto.CompressTransform.getDefaultInstance()))
            .build();
    String fragment = TransformProtos.toEncodedFragment(transformsProto);
    assertThat(fragment).isEqualTo("transform=compress");
  }

  @Test
  public void encryptProto_producesEncryptTransform() throws Exception {
    TransformProto.Transforms transformsProto =
        TransformProto.Transforms.newBuilder()
            .addTransform(
                TransformProto.Transform.newBuilder()
                    .setEncrypt(TransformProto.EncryptTransform.getDefaultInstance()))
            .build();
    String fragment = TransformProtos.toEncodedFragment(transformsProto);
    assertThat(fragment).isEqualTo("transform=encrypt");
  }

  @Test
  public void integrityProto_producesIntegrityTransform() throws Exception {
    TransformProto.Transforms transformsProto =
        TransformProto.Transforms.newBuilder()
            .addTransform(
                TransformProto.Transform.newBuilder()
                    .setIntegrity(TransformProto.IntegrityTransform.getDefaultInstance()))
            .build();
    String fragment = TransformProtos.toEncodedFragment(transformsProto);
    assertThat(fragment).isEqualTo("transform=integrity");
  }

  @Test
  public void zipProto_producesZipTransform() throws Exception {
    TransformProto.Transforms transformsProto =
        TransformProto.Transforms.newBuilder()
            .addTransform(
                TransformProto.Transform.newBuilder()
                    .setZip(TransformProto.ZipTransform.newBuilder().setTarget("abc")))
            .build();
    String fragment = TransformProtos.toEncodedFragment(transformsProto);
    assertThat(fragment).isEqualTo("transform=zip(target=abc)");
  }

  @Test
  public void customProto_producesCustomTransform() throws Exception {
    TransformProto.Transforms transformsProto =
        TransformProto.Transforms.newBuilder()
            .addTransform(
                TransformProto.Transform.newBuilder()
                    .setCustom(
                        TransformProto.CustomTransform.newBuilder()
                            .setName("custom")
                            .addAllSubparam(
                                ImmutableList.of(
                                    TransformProto.CustomTransform.SubParam.newBuilder()
                                        .setKey("key1")
                                        .setValue("value1")
                                        .build(),
                                    TransformProto.CustomTransform.SubParam.newBuilder()
                                        .setKey("key2")
                                        .setValue("=?!")
                                        .build()))))
            .build();
    String fragment = TransformProtos.toEncodedFragment(transformsProto);
    assertThat(fragment).isEqualTo("transform=custom(key1=value1,key2=%3D%3F%21)");
  }

  @Test
  public void textProto_producesMultiTransform() throws Exception {
    TransformProto.Transforms transformsProto = getTransformsFromTextProto();
    String fragment = TransformProtos.toEncodedFragment(transformsProto);
    assertThat(fragment)
        .isEqualTo("transform=compress+encrypt(aes_gcm_key=a%2Bbc)+integrity(sha256=ab%2Bc)");
  }

  @Test
  public void canonicalExample_addProtoTransformToUri() throws Exception {
    TransformProto.Transforms transformsProto = getTransformsFromTextProto();
    String fragment = TransformProtos.toEncodedFragment(transformsProto);
    Uri uri = Uri.parse("file:/tmp/foo.txt").buildUpon().encodedFragment(fragment).build();
    assertThat(uri.toString())
        .isEqualTo(
            "file:/tmp/foo.txt"
                + "#transform=compress+encrypt(aes_gcm_key=a%2Bbc)+integrity(sha256=ab%2Bc)");
  }

  private TransformProto.Transforms getTransformsFromTextProto() {
    return ProtoParsers.parseFromRawRes(
        ApplicationProvider.getApplicationContext(),
        TransformProto.Transforms.parser(),
        R.raw.transforms_data_pb);
  }
}
