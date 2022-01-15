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

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import com.google.android.libraries.mobiledatadownload.file.common.Fragment;
import com.google.android.libraries.mobiledatadownload.file.common.ParamComputer;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.common.primitives.Ints;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * A transform that implements integrity check using a cryptographic hash (SHA-256). This transform
 * can work in 3 modes:
 *
 * <ol>
 *   <li>While reading, verify that content matches hash present in URI.
 *   <li>Compute hash from existing file and return with {@link
 *       ComputedUriInputStream#consumeStreamAndGetComputedUri}
 *   <li>Compute hash while writing, and return with {@link ComputedUriOutputStream#getComputedUri}
 * </ol>
 *
 * There are vulnerabilities present in this design:
 *
 * <ul>
 *   <li>While reading, the verification happens only when the end of the stream is reached. No
 *       assumptions can be made about integrity of data until then.
 *   <li>If the hash is computed on an existing file stored on insecure medium, it's possible for
 *       that file to have already been modified, or to be modified while the hash is being
 *       computed.
 * </ul>
 *
 * <p>Clients are expected to use the computed URI methods to produce a valid URI with hash embedded
 * in it. The name of the digest subparam (eg, "sha256") is used to identify the hash and hashing
 * algorithm. Future implementations may use different algorithms and subparams, but are expected to
 * retain backwards compatibility.
 *
 * <p>Computed URIs that contain hashes must be stored in a safe place to avoid tampering.
 *
 * <p>Does not support appending to an existing file.
 *
 * <p>See <internal> for further documentation.
 */
public final class IntegrityTransform implements Transform {

  private static final String TRANSFORM_NAME = "integrity";
  private static final String SUBPARAM_NAME = "sha256";

  /** Returns the base64-encoded SHA-256 digest encoded in {@code uri}, or null if not present. */
  @Nullable
  public static String getDigestIfPresent(Uri uri) {
    return Fragment.getTransformSubParam(uri, TRANSFORM_NAME, SUBPARAM_NAME);
  }

  @Override
  public String name() {
    return TRANSFORM_NAME;
  }

  private static MessageDigest newDigester() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalArgumentException(e);
    }
  }

  @Override
  public InputStream wrapForRead(Uri uri, InputStream wrapped) throws IOException {
    Fragment.ParamValue param = Fragment.getTransformParamValue(uri, name());
    return new DigestVerifyingInputStream(wrapped, param);
  }

  @Override
  public OutputStream wrapForWrite(Uri uri, OutputStream wrapped) throws IOException {
    Fragment.ParamValue param = Fragment.getTransformParamValue(uri, name());
    return new DigestComputingOutputStream(wrapped, param);
  }

  private static class DigestComputingOutputStream extends DigestOutputStream
      implements ParamComputer {
    Fragment.ParamValue param;
    ParamComputer.Callback paramComputerCallback;
    boolean didComputeDigest = false;

    DigestComputingOutputStream(OutputStream stream, Fragment.ParamValue param) {
      super(stream, newDigester());
      this.param = param;
    }

    @Override
    public void close() throws IOException {
      super.close();
      if (!didComputeDigest) {
        didComputeDigest = true;
        if (paramComputerCallback != null) {
          paramComputerCallback.onParamValueComputed(
              param.toBuilder()
                  .addSubParam(
                      SUBPARAM_NAME, Base64.encodeToString(digest.digest(), Base64.NO_WRAP))
                  .build());
        }
      }
    }

    @Override
    public void setCallback(ParamComputer.Callback callback) {
      this.paramComputerCallback = callback;
    }
  }

  private static class DigestVerifyingInputStream extends DigestInputStream
      implements ParamComputer {
    byte[] skipBuffer = new byte[4096];
    byte[] expectedDigest;
    byte[] computedDigest;
    Fragment.ParamValue param;
    ParamComputer.Callback paramComputerCallback;

    DigestVerifyingInputStream(InputStream stream, Fragment.ParamValue param) {
      super(stream, newDigester());
      this.param = param;
      String digest = param.findSubParamValue(SUBPARAM_NAME);
      if (!TextUtils.isEmpty(digest)) {
        this.expectedDigest = Base64.decode(digest, Base64.NO_WRAP);
      }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      int result = super.read(b, off, len);
      if (result == -1) {
        checkDigest();
      }
      return result;
    }

    @Override
    public int read() throws IOException {
      int result = super.read();
      if (result == -1) {
        checkDigest();
      }
      return result;
    }

    @Override
    public void close() throws IOException {
      // Close underlying stream first since checkDigest can throw exception. If the underlying
      // close fails, OTOH, digest should be assumed to be invalid.
      super.close();
      checkDigest();
    }

    @Override
    public long skip(long n) throws IOException {
      // Consume min(buffer.length, n) bytes, processing the bytes the ensure the digest is updated.
      int length = Math.min(skipBuffer.length, Ints.checkedCast(n));
      return read(skipBuffer, 0, length);
    }

    @Override
    public void setCallback(ParamComputer.Callback callback) {
      this.paramComputerCallback = callback;
    }

    private void checkDigest() throws IOException {
      if (computedDigest != null) {
        return;
      }
      computedDigest = digest.digest();
      if (paramComputerCallback != null) {
        paramComputerCallback.onParamValueComputed(
            param.toBuilder()
                .addSubParam(SUBPARAM_NAME, Base64.encodeToString(computedDigest, Base64.NO_WRAP))
                .build());
      }
      if (expectedDigest != null && !Arrays.equals(computedDigest, expectedDigest)) {
        String a = Base64.encodeToString(computedDigest, Base64.NO_WRAP);
        String e = Base64.encodeToString(expectedDigest, Base64.NO_WRAP);
        throw new IOException("Mismatched digest: " + a + " expected: " + e);
      }
    }
  }
}
