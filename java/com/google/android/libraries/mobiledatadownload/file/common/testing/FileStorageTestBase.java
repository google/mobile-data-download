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
package com.google.android.libraries.mobiledatadownload.file.common.testing;

import static org.mockito.AdditionalAnswers.returnsSecondArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.net.Uri;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.junit.Before;
import org.junit.Rule;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Base class with common code for file storage tests.
 *
 * <p>Mocks use "file", "cns", "compress" and "encrypt" to make the tests easier to follow, but they
 * do not implement any of said behaviors.
 */
public abstract class FileStorageTestBase {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  protected final Uri file1Uri;
  protected final Uri file2Uri;
  protected final Uri file3Uri;
  protected final Uri dir1Uri;
  protected final String file1Filename;
  protected final String file2Filename;
  protected final Uri cnsUri;
  protected final Uri file1CompressUri;
  protected final Uri file1CompressUriWithEncoded;
  protected final Uri file2CompressEncryptUri;
  protected final Uri file3IdentityUri;
  protected final Uri dir2CompressUri;
  protected final Uri uriWithCompressParam;
  protected final Uri uriWithCompressParamWithEncoded;
  protected final Uri uriWithEncryptParam;
  protected final Uri uriWithIdentityParam;

  private static final InputStream EMPTY_INPUT = new ByteArrayInputStream(new byte[] {});
  private static final OutputStream NULL_OUTPUT =
      new OutputStream() {
        @Override
        public void write(int i) throws IOException {
          // ignore
        }
      };

  @Mock protected Backend fileBackend;
  @Mock protected Backend cnsBackend;
  @Mock protected Transform compressTransform;
  @Mock protected Transform encryptTransform;
  @Mock protected Monitor countingMonitor;

  protected InputStream compressInputStream = new ByteArrayInputStream(new byte[] {});
  protected InputStream encryptInputStream = new ByteArrayInputStream(new byte[] {});
  protected OutputStream compressOutputStream = new ByteArrayOutputStream();
  protected OutputStream encryptOutputStream = new ByteArrayOutputStream();
  protected Closeable closeable = () -> {};

  protected final Transform identityTransform =
      new Transform() {
        @Override
        public String name() {
          return "identity";
        }
      };

  protected FileStorageTestBase() {
    // Robolectric doesn't seem to work with static initializers.
    file1Uri = Uri.parse("file:///file1");
    file2Uri = Uri.parse("file:///file2");
    file3Uri = Uri.parse("file:///file3");
    dir1Uri = Uri.parse("file:///dir1/");
    file1Filename = "file1";
    file2Filename = "file2";
    cnsUri = Uri.parse("cns:///1");
    file1CompressUri = Uri.parse("file:///file1#transform=compress");
    // Percent encoding of fragment params is not in our spec (<internal>), but
    // implemented anyway.
    file1CompressUriWithEncoded = Uri.parse("file:///file1#transform=compress(k%3D=%28v%29)");
    file2CompressEncryptUri = Uri.parse("file:///file2#transform=compress+encrypt");
    file3IdentityUri = Uri.parse("file:///file3#transform=identity");
    dir2CompressUri = Uri.parse("file:///dir2/#transform=compress");
    uriWithCompressParam = Uri.parse("#transform=compress");
    uriWithCompressParamWithEncoded = Uri.parse("#transform=compress(k%3D=%28v%29)");
    uriWithEncryptParam = Uri.parse("#transform=encrypt");
    uriWithIdentityParam = Uri.parse("#transform=identity");
  }

  @Before
  public void initMocksAndCreateFileStorage() throws Exception {
    when(fileBackend.name()).thenReturn("file");
    when(fileBackend.openForRead(any())).thenReturn(EMPTY_INPUT);
    when(fileBackend.openForWrite(any())).thenReturn(NULL_OUTPUT);
    when(fileBackend.openForAppend(any())).thenReturn(NULL_OUTPUT);
    when(cnsBackend.openForRead(any())).thenReturn(EMPTY_INPUT);
    when(cnsBackend.openForWrite(any())).thenReturn(NULL_OUTPUT);
    when(cnsBackend.openForAppend(any())).thenReturn(NULL_OUTPUT);
    when(cnsBackend.name()).thenReturn("cns");
    when(compressTransform.name()).thenReturn("compress");
    when(compressTransform.encode(any(), any())).then(returnsSecondArg());
    when(compressTransform.decode(any(), any())).then(returnsSecondArg());
    when(encryptTransform.name()).thenReturn("encrypt");
    when(encryptTransform.encode(any(), any())).then(returnsSecondArg());
    when(encryptTransform.decode(any(), any())).then(returnsSecondArg());
    when(fileBackend.openForNativeRead(any()))
        .thenReturn(Pair.create(Uri.parse("fd:123"), closeable));
    when(cnsBackend.openForNativeRead(any()))
        .thenReturn(Pair.create(Uri.parse("fd:456"), closeable));
    initStorage();
  }

  // Called after mocks are initialized to create the FileStorage instance.
  // Required b/c order of @Befores is non-deterministic.
  protected abstract void initStorage();
}
