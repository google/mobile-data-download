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
package com.google.android.libraries.mobiledatadownload.file.backends;

import android.net.Uri;
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link Backend} whose files exist only in memory. Files are never cached to disk and exist only
 * for the duration of the backend itself. It has the following limitations:
 *
 * <ul>
 *   <li>The backend is flat; directory operations are unsupported
 *   <li>{@link Backend#openForAppend} is unsupported
 *   <li>Native filesytem features such as FD's and syncing are unsupported
 * </ul>
 *
 * <p>See <internal> for further documentation.
 */
// TODO(b/111694348): consider adding directory support
public final class MemoryBackend implements Backend {

  // Uri scheme the backend is registered under (package-private for use in {@link MemoryUri})
  static final String URI_SCHEME = "memory";

  private final Map<Uri, ByteBuffer> files = new HashMap<>();

  @Override
  public String name() {
    return URI_SCHEME;
  }

  @Override
  public InputStream openForRead(Uri uri) throws IOException {
    validate(uri);
    ByteBuffer buf = files.get(uri);
    if (buf == null) {
      throw new FileNotFoundException(String.format("%s (No such file)", uri));
    }
    ByteBuffer slice = buf.slice(); // Points to the same data but has an independent read position
    return new ByteArrayInputStream(slice.array(), slice.arrayOffset(), slice.limit());
  }

  @Override
  public OutputStream openForWrite(Uri uri) throws IOException {
    validate(uri);
    // Note that this is atomic in the sense that the data isn't flushed until closing the stream
    return new ByteArrayOutputStream() {
      @Override
      public void close() throws IOException {
        files.put(uri, ByteBuffer.wrap(this.buf, 0, this.count));
      }
    };
  }

  @Override
  public void deleteFile(Uri uri) throws IOException {
    validate(uri);
    if (files.remove(uri) == null) {
      throw new FileNotFoundException(String.format("%s does not exist", uri));
    }
  }

  @Override
  public void rename(Uri from, Uri to) throws IOException {
    validate(from);
    validate(to);
    ByteBuffer buf = files.remove(from);
    if (buf == null) {
      throw new IOException(String.format("%s could not be renamed to %s", from, to));
    }
    files.put(to, buf);
  }

  @Override
  public boolean exists(Uri uri) throws IOException {
    validate(uri);
    return files.containsKey(uri);
  }

  @Override
  public long fileSize(Uri uri) throws IOException {
    validate(uri);
    ByteBuffer buf = files.get(uri);
    return buf != null ? buf.limit() : 0;
  }

  /** Validates that {@code uri} is a non-empty memory: Uri with no authority or query. */
  private static void validate(Uri uri) throws MalformedUriException {
    if (!URI_SCHEME.equals(uri.getScheme())) {
      throw new MalformedUriException("Expected scheme to be " + URI_SCHEME);
    }
    if (TextUtils.isEmpty(uri.getSchemeSpecificPart())) {
      throw new MalformedUriException("Expected a non-empty file identifier");
    }
    if (!TextUtils.isEmpty(uri.getAuthority())) {
      throw new MalformedUriException("Did not expect an authority");
    }
    if (!TextUtils.isEmpty(uri.getQuery())) {
      throw new MalformedUriException("Did not expect a query");
    }
  }
}
