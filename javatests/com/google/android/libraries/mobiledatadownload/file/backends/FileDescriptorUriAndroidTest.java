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

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import java.io.Closeable;
import java.io.File;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Test {@link com.google.android.libraries.mobiledatadownload.file.backends.FileDescriptorUri}. */
@RunWith(JUnit4.class)
public class FileDescriptorUriAndroidTest {

  @Test
  public void convenienceMethod_shouldGenerateUriFromPFD() throws Exception {
    ParcelFileDescriptor stdin = ParcelFileDescriptor.adoptFd(0);
    Pair<Uri, Closeable> result = FileDescriptorUri.fromParcelFileDescriptor(stdin);
    assertThat(result.first.getScheme()).isEqualTo("fd");
    assertThat(result.first.getSchemeSpecificPart()).isEqualTo("0");
    assertThat(FileDescriptorUri.getFd(result.first)).isEqualTo(0);
    assertThat(result.first.toString()).isEqualTo("fd:0");
  }

  @Test
  public void close_shouldCloseFd() throws Exception {
    ParcelFileDescriptor cwd =
        ParcelFileDescriptor.open(new File("."), ParcelFileDescriptor.MODE_READ_ONLY);
    Pair<Uri, Closeable> result = FileDescriptorUri.fromParcelFileDescriptor(cwd);

    assertThat(result.first.getScheme()).isEqualTo("fd");
    assertThat(FileDescriptorUri.getFd(result.first)).isGreaterThan(0);

    File procFd = new File("/proc/self/fd/" + result.first.getSchemeSpecificPart());
    assertThat(procFd.exists()).isTrue();
    result.second.close();
    assertThat(procFd.exists()).isFalse();
  }

  @Test
  public void getFd_validatesUri() throws Exception {
    assertThrows(MalformedUriException.class, () -> FileDescriptorUri.getFd(Uri.parse("file:5")));
    assertThrows(MalformedUriException.class, () -> FileDescriptorUri.getFd(Uri.parse("fd:")));
    assertThrows(MalformedUriException.class, () -> FileDescriptorUri.getFd(Uri.parse("fd:abc")));
  }
}
