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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.android.libraries.storage.file.common.testing.TestMessageProto.ExtendableProto;
import com.google.android.libraries.storage.file.common.testing.TestMessageProto.ExtensionProto;
import com.google.android.libraries.storage.file.common.testing.TestMessageProto.FooProto;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class ReadProtoOpenerTest {

  private static final FooProto TEST_PROTO =
      FooProto.newBuilder().setText("foo text").setBoolean(true).build();

  private SynchronousFileStorage storage;

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(
            Arrays.asList(new JavaFileBackend()), Arrays.asList(new CompressTransform()));
  }

  @Test
  public void create_fromMessageParser_returnsOpenerWithCorrectGenericType() throws Exception {
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    ReadProtoOpener<FooProto> opener = ReadProtoOpener.create(FooProto.parser());
    FooProto unusedToCheckCompilation = storage.open(uri, opener);

    // Ensure Java compiler can infer the correct generic when Opener is passed in directly
    unusedToCheckCompilation = storage.open(uri, ReadProtoOpener.create(FooProto.parser()));
  }

  @Test
  public void create_fromMessageInstance_returnsOpenerWithCorrectGenericType() throws Exception {
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    ReadProtoOpener<FooProto> opener = ReadProtoOpener.create(TEST_PROTO);
    FooProto unusedToCheckCompilation = storage.open(uri, opener);

    // Ensure Java compiler can infer the correct generic when Opener is passed in directly
    unusedToCheckCompilation = storage.open(uri, ReadProtoOpener.create(TEST_PROTO));
  }

  @Test
  public void open_readsFullProtoFromFile() throws Exception {
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    assertThat(storage.open(uri, ReadProtoOpener.create(FooProto.parser()))).isEqualTo(TEST_PROTO);
    assertThat(storage.open(uri, ReadProtoOpener.create(TEST_PROTO))).isEqualTo(TEST_PROTO);
  }

  @Test
  public void open_readsEmptyProtoFromFile() throws Exception {
    FooProto emptyProto = FooProto.getDefaultInstance();
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteProtoOpener.create(emptyProto));

    assertThat(storage.open(uri, ReadProtoOpener.create(FooProto.parser()))).isEqualTo(emptyProto);
  }

  @Test
  public void open_invokesTransforms() throws Exception {
    Uri uri = tmpUri.newUriBuilder().withTransform(TransformProtos.DEFAULT_COMPRESS_SPEC).build();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    assertThat(storage.open(uri, ReadProtoOpener.create(FooProto.parser()))).isEqualTo(TEST_PROTO);
  }

  @Test
  public void open_throwsIOExceptionOnBadParse() throws Exception {
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteStringOpener.create("not a proto"));

    assertThrows(
        InvalidProtocolBufferException.class,
        () -> storage.open(uri, ReadProtoOpener.create(FooProto.parser())));
  }

  @Test
  public void withRegistry_readsExtension() throws Exception {
    Uri uri = tmpUri.newUri();
    ExtendableProto extendable = createProtoWithExtension();
    storage.open(uri, WriteProtoOpener.create(extendable));

    ReadProtoOpener<ExtendableProto> opener =
        ReadProtoOpener.create(ExtendableProto.parser())
            .withExtensionRegistry(ExtensionRegistryLite.getGeneratedRegistry());

    ExtendableProto actualExtendable = storage.open(uri, opener);
    assertThat(actualExtendable.hasExtension(ExtensionProto.extension)).isTrue();
    assertThat(actualExtendable.getExtension(ExtensionProto.extension).getFoo().getText())
        .isEqualTo("foo text");
  }

  @Test
  public void withOutRegistry_failsToReadsExtension() throws Exception {
    Uri uri = tmpUri.newUri();
    ExtendableProto extendable = createProtoWithExtension();
    storage.open(uri, WriteProtoOpener.create(extendable));

    ReadProtoOpener<ExtendableProto> opener = ReadProtoOpener.create(ExtendableProto.parser());

    ExtendableProto actualExtendable = storage.open(uri, opener);
    assertThat(actualExtendable.hasExtension(ExtensionProto.extension)).isFalse();
  }

  private ExtendableProto createProtoWithExtension() {
    ExtendableProto extendable =
        ExtendableProto.newBuilder()
            .setExtension(
                ExtensionProto.extension, ExtensionProto.newBuilder().setFoo(TEST_PROTO).build())
            .build();
    return extendable;
  }
}
