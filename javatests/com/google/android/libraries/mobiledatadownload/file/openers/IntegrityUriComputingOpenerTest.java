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

import static com.google.android.libraries.mobiledatadownload.file.common.testing.StreamUtils.createFile;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.transforms.IntegrityTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtoFragments;
import com.google.common.collect.ImmutableList;
import com.google.mobiledatadownload.TransformProto;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class IntegrityUriComputingOpenerTest {

  private static final String ORIGTEXT = "This is some regular old text ABC 123 !@#";
  private static final String ORIGTEXT_SHA256_B64 = "FoR1HrxdAhY05DE/gAUj0yjpzYpfWb0fJE+XBp8lY0o=";
  private static final String ORIGTEXT_SHA256_B64_INCORRECT = "INCORRECT";

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  private SynchronousFileStorage storage;

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            ImmutableList.of(new JavaFileBackend()), ImmutableList.of(new IntegrityTransform()));
  }

  @Test
  public void integrityUriComputingOpener_shouldProduceCorrectChecksum() throws Exception {

    Uri initialUri = createTestFile(ORIGTEXT);
    Uri uriWithChecksum = storage.open(initialUri, IntegrityUriComputingOpener.create());
    String digest = IntegrityTransform.getDigestIfPresent(uriWithChecksum);

    assertThat(digest).isEqualTo(ORIGTEXT_SHA256_B64);
  }

  @Test
  public void integrityUriComputingOpener_shouldIgnoreIncorrectInitialSpec() throws Exception {
    TransformProto.Transform spec =
        TransformProto.Transform.newBuilder()
            .setIntegrity(
                TransformProto.IntegrityTransform.newBuilder()
                    .setSha256(ORIGTEXT_SHA256_B64_INCORRECT))
            .build();
    Uri initialUri = TransformProtoFragments.addOrReplaceTransform(createTestFile(ORIGTEXT), spec);
    Uri uriWithChecksum = storage.open(initialUri, IntegrityUriComputingOpener.create());
    String digest = IntegrityTransform.getDigestIfPresent(uriWithChecksum);

    assertThat(digest).isEqualTo(ORIGTEXT_SHA256_B64);
  }

  @Test
  public void integrityUriComputingOpener_shouldIgnoreIntegrityParamWithNoSubparam()
      throws Exception {
    TransformProto.Transform spec =
        TransformProto.Transform.newBuilder()
            .setIntegrity(TransformProto.IntegrityTransform.getDefaultInstance())
            .build();

    Uri initialUri = TransformProtoFragments.addOrReplaceTransform(createTestFile(ORIGTEXT), spec);
    Uri uriWithChecksum = storage.open(initialUri, IntegrityUriComputingOpener.create());
    String digest = IntegrityTransform.getDigestIfPresent(uriWithChecksum);

    assertThat(digest).isEqualTo(ORIGTEXT_SHA256_B64);
  }

  private Uri createTestFile(String contents) throws Exception {
    Uri uri = tmpUri.newUri();
    createFile(storage, uri, contents);
    return uri;
  }
}
