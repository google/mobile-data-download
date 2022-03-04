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
import com.google.android.libraries.mobiledatadownload.file.behaviors.SyncingBehavior;
import com.google.android.libraries.mobiledatadownload.file.common.testing.FakeFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.transforms.CompressTransform;
import com.google.android.libraries.storage.file.common.testing.TestMessageProto.FooProto;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class WriteProtoOpenerTest {

  private static final FooProto TEST_PROTO =
      FooProto.newBuilder()
          .setText("all work and no play makes jack a dull boy all work and no play makes jack a")
          .setBoolean(true)
          .build();

  private SynchronousFileStorage storage;
  private final FakeFileBackend backend = new FakeFileBackend();

  @Rule public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setUpStorage() throws Exception {
    storage =
        new SynchronousFileStorage(Arrays.asList(backend), Arrays.asList(new CompressTransform()));
  }

  @Test
  public void writesProto() throws Exception {
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    FooProto actual = storage.open(uri, ReadProtoOpener.create(FooProto.parser()));
    assertThat(actual).isEqualTo(TEST_PROTO);
  }

  @Test
  public void writesProto_withTransform() throws Exception {
    Uri uri = tmpUri.newUri().buildUpon().encodedFragment("transform=compress").build();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    FooProto actual = storage.open(uri, ReadProtoOpener.create(FooProto.parser()));
    assertThat(actual).isEqualTo(TEST_PROTO);
    assertThat(storage.fileSize(uri)).isLessThan(TEST_PROTO.getSerializedSize());
  }

  @Test
  public void failedWrite_noChange() throws Exception {
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    FooProto modifiedProto = TEST_PROTO.toBuilder().setBoolean(false).build();
    IOException expected = new IOException("expected");
    backend.setFailure(FakeFileBackend.OperationType.WRITE_STREAM, expected);

    assertThrows(
        IOException.class, () -> storage.open(uri, WriteProtoOpener.create(modifiedProto)));

    FooProto actual = storage.open(uri, ReadProtoOpener.create(FooProto.parser()));
    assertThat(actual).isEqualTo(TEST_PROTO);
  }

  @Test
  public void failedRename_noChange() throws Exception {
    Uri uri = tmpUri.newUri();
    storage.open(uri, WriteProtoOpener.create(TEST_PROTO));

    FooProto modifiedProto = TEST_PROTO.toBuilder().setBoolean(false).build();
    IOException expected = new IOException("expected");
    backend.setFailure(FakeFileBackend.OperationType.MANAGE, expected);

    assertThrows(
        IOException.class, () -> storage.open(uri, WriteProtoOpener.create(modifiedProto)));

    FooProto actual = storage.open(uri, ReadProtoOpener.create(FooProto.parser()));
    assertThat(actual).isEqualTo(TEST_PROTO);
  }

  @Test
  public void invokes_autoSync() throws Exception {
    Uri uri1 = tmpUri.newUri();
    SyncingBehavior syncing = Mockito.spy(new SyncingBehavior());
    storage.open(uri1, WriteProtoOpener.create(TEST_PROTO).withBehaviors(syncing));
    Mockito.verify(syncing).sync();
  }
}
