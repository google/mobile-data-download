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

import static com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets.UTF_8;

import android.util.Base64;
import com.google.android.libraries.mobiledatadownload.file.common.internal.LiteTransformFragments;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.mobiledatadownload.TransformProto;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** Utility to convert Transform proto into URI fragment. */
public final class TransformProtos {

  public static final TransformProto.Transform DEFAULT_COMPRESS_SPEC =
      TransformProto.Transform.newBuilder()
          .setCompress(TransformProto.CompressTransform.getDefaultInstance())
          .build();

  public static final TransformProto.Transform DEFAULT_ENCRYPT_SPEC =
      TransformProto.Transform.newBuilder()
          .setEncrypt(TransformProto.EncryptTransform.getDefaultInstance())
          .build();

  public static final TransformProto.Transform DEFAULT_INTEGRITY_SPEC =
      TransformProto.Transform.newBuilder()
          .setIntegrity(TransformProto.IntegrityTransform.getDefaultInstance())
          .build();

  public static TransformProto.Transform encryptTransformSpecWithKey(byte[] key) {
    return encryptTransformSpecWithBase64Key(Base64.encodeToString(key, Base64.NO_WRAP));
  }

  public static TransformProto.Transform encryptTransformSpecWithBase64Key(String base64Key) {
    return TransformProto.Transform.newBuilder()
        .setEncrypt(TransformProto.EncryptTransform.newBuilder().setAesGcmKeyBase64(base64Key))
        .build();
  }

  public static TransformProto.Transform integrityTransformSpecWithSha256(String sha256) {
    return TransformProto.Transform.newBuilder()
        .setIntegrity(TransformProto.IntegrityTransform.newBuilder().setSha256(sha256))
        .build();
  }

  public static TransformProto.Transform zipTransformSpecWithTarget(String target) {
    return TransformProto.Transform.newBuilder()
        .setZip(TransformProto.ZipTransform.newBuilder().setTarget(target))
        .build();
  }

  /**
   * Translate from proto representation to an encoded fragment, which is suitable for appending to
   * a URI.
   *
   * <p>To build an {@link android.net.Uri} with such a fragment: <code>
   * Fragment fragment = TransformProtos.toEncodedFragment(transformProto);
   * Uri uri = origUri.buildUpon().encodedFragment(fragment.toString()).build();
   * </code>
   *
   * @param transformsProto The proto to translate to a fragment.
   * @return The encoded fragment representation of the proto.
   */
  public static String toEncodedFragment(TransformProto.Transforms transformsProto) {
    ImmutableList.Builder<String> encodedSpecs = ImmutableList.builder();
    for (TransformProto.Transform transformProto : transformsProto.getTransformList()) {
      encodedSpecs.add(toEncodedSpec(transformProto));
    }
    return LiteTransformFragments.joinTransformSpecs(encodedSpecs.build());
  }

  /**
   * Translate proto representation to an encoded transform spec.
   *
   * @param transformProto The proto to translate to a spec.
   * @return An encoded spec suitable for joining with other specs to form fragment.
   */
  public static String toEncodedSpec(TransformProto.Transform transformProto) {
    String encodedSpec;
    switch (transformProto.getTransformCase()) {
      case COMPRESS:
        encodedSpec = "compress";
        break;
      case ENCRYPT:
        TransformProto.EncryptTransform encrypt = transformProto.getEncrypt();
        if (encrypt.hasAesGcmKeyBase64()) {
          encodedSpec = "encrypt(aes_gcm_key=" + urlEncode(encrypt.getAesGcmKeyBase64()) + ")";
        } else {
          encodedSpec = "encrypt";
        }
        break;
      case INTEGRITY:
        TransformProto.IntegrityTransform integrity = transformProto.getIntegrity();
        if (integrity.hasSha256()) {
          encodedSpec = "integrity(sha256=" + urlEncode(integrity.getSha256()) + ")";
        } else {
          encodedSpec = "integrity";
        }
        break;
      case ZIP:
        TransformProto.ZipTransform zip = transformProto.getZip();
        Preconditions.checkArgument(zip.hasTarget());
        encodedSpec = "zip(target=" + urlEncode(zip.getTarget()) + ")";
        break;
      case CUSTOM:
        TransformProto.CustomTransform custom = transformProto.getCustom();
        String params = "";
        if (custom.getSubparamCount() > 0) {
          ImmutableList.Builder<String> subparams = ImmutableList.builder();
          for (TransformProto.CustomTransform.SubParam subparam : custom.getSubparamList()) {
            Preconditions.checkArgument(subparam.hasKey());
            if (subparam.hasValue()) {
              subparams.add(subparam.getKey() + "=" + urlEncode(subparam.getValue()));
            } else {
              subparams.add(subparam.getKey());
            }
          }
          params = "(" + Joiner.on(",").join(subparams.build()) + ")";
        }
        encodedSpec = custom.getName() + params;
        break;
      default:
        throw new IllegalArgumentException("No transform specified");
    }
    return encodedSpec;
  }

  private static final String urlEncode(String str) {
    try {
      return URLEncoder.encode(str, UTF_8.displayName());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(e); // Expects UTF8 to be available.
    }
  }
}
