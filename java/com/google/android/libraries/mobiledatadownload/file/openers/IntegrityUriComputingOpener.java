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
package com.google.android.libraries.mobiledatadownload.file.openers;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.android.libraries.mobiledatadownload.file.behaviors.UriComputingBehavior;
import com.google.android.libraries.mobiledatadownload.file.transforms.IntegrityTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtoFragments;
import com.google.common.io.ByteStreams;
import com.google.mobiledatadownload.TransformProto;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;

/** An opener that produces a URI augumented with the IntegrityUriComputing of the input URI. */
public final class IntegrityUriComputingOpener implements Opener<Uri> {

  private static final TransformProto.Transform DEFAULT_SPEC =
      TransformProto.Transform.newBuilder()
          .setIntegrity(TransformProto.IntegrityTransform.getDefaultInstance())
          .build();

  private IntegrityUriComputingOpener() {}

  public static IntegrityUriComputingOpener create() {
    return new IntegrityUriComputingOpener();
  }

  @Override
  public Uri open(OpenContext openContext) throws IOException {
    Uri uri = openContext.originalUri();

    uri = TransformProtoFragments.addOrReplaceTransform(uri, DEFAULT_SPEC);
    UriComputingBehavior uriComputer = new UriComputingBehavior(uri);
    try (InputStream stream =
        openContext.storage().open(uri, ReadStreamOpener.create().withBehaviors(uriComputer))) {
      ByteStreams.exhaust(stream);
      Uri uriWithDigest;
      try {
        uriWithDigest = uriComputer.uriFuture().get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // per <internal>
        throw new IOException(e);
      } catch (ExecutionException e) {
        if (e.getCause() instanceof IOException) {
          throw (IOException) e.getCause();
        }
        throw new IOException(e);
      }
      String base64Digest = IntegrityTransform.getDigestIfPresent(uriWithDigest);
      TransformProto.Transform newSpec =
          TransformProto.Transform.newBuilder()
              .setIntegrity(TransformProto.IntegrityTransform.newBuilder().setSha256(base64Digest))
              .build();
      return TransformProtoFragments.addOrReplaceTransform(uriWithDigest, newSpec);
    }
  }
}
