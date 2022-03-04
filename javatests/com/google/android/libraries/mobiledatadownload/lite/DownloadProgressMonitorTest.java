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
package com.google.android.libraries.mobiledatadownload.lite;

import static com.google.android.libraries.mobiledatadownload.lite.DownloadProgressMonitor.BUFFERED_TIME_MS;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor.OutputMonitor;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class DownloadProgressMonitorTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  // Use directExecutor to ensure the order of test verification.
  private static final Executor CONTROL_EXECUTOR = MoreExecutors.directExecutor();

  private static final String FILE_URI_1 =
      "android://com.google.android.gms/files/datadownload/shared/public/file_1";

  // Note: We can't make those android uris static variable since the Uri.parse will fail
  // with initialization.
  private final Uri uri1 = Uri.parse(FILE_URI_1);

  private static final String FILE_URI_2 =
      "android://com.google.android.gms/files/datadownload/shared/public/file_2";
  private final Uri uri2 = Uri.parse(FILE_URI_2);

  private static final String FILE_URI_3 =
      "android://com.google.android.gms/files/datadownload/shared/public/file_3";
  private final Uri uri3 = Uri.parse(FILE_URI_3);

  private DownloadProgressMonitor downloadMonitor;
  private final FakeTimeSource clock = new FakeTimeSource();

  @Mock private DownloadListener mockDownloadListener1;
  @Mock private DownloadListener mockDownloadListener2;

  @Before
  public void setUp() throws Exception {
    downloadMonitor = DownloadProgressMonitor.create(clock, CONTROL_EXECUTOR);
  }

  @Test
  public void bytesWritten_cleanSlate() {
    downloadMonitor.addDownloadListener(uri1, mockDownloadListener1);
    downloadMonitor.addDownloadListener(uri2, mockDownloadListener2);

    OutputMonitor outputMonitor1 = downloadMonitor.monitorWrite(uri1);
    OutputMonitor outputMonitor2 = downloadMonitor.monitorWrite(uri1);
    OutputMonitor outputMonitor3 = downloadMonitor.monitorWrite(uri3);

    // outputMonitor1 is same as outputMonitor2 since they both monitor for uri1.
    assertThat(outputMonitor1).isSameInstanceAs(outputMonitor2);
    assertThat(outputMonitor1).isNotSameInstanceAs(outputMonitor3);

    // The 1st bytesWritten was buffered.
    outputMonitor1.bytesWritten(new byte[1], 0, 1);
    verifyNoInteractions(mockDownloadListener1);

    // Now the buffered time passed.
    clock.advance(BUFFERED_TIME_MS + 100, MILLISECONDS);

    // The 2nd bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 1, 2);
    // 1 (first bytesWritten) + 2 (2nd bytesWritten)
    verify(mockDownloadListener1).onProgress(1 + 2);

    // The 3rd bytesWritten was buffered
    outputMonitor1.bytesWritten(new byte[1], 3, 4);
    verifyNoMoreInteractions(mockDownloadListener1);

    // Now the buffered time passed again.
    clock.advance(BUFFERED_TIME_MS + 100, MILLISECONDS);

    // The 4th bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 7, 8);
    verify(mockDownloadListener1).onProgress(1 + 2 + 4 + 8);

    // No bytes were downloaded for FileGroup2.
    verifyNoInteractions(mockDownloadListener2);
  }

  @Test
  public void bytesWritten_addAndRemoveDownloadListener() {

    // There is no download listener for uri1.
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();

    // Adding a listener now.
    downloadMonitor.addDownloadListener(uri1, mockDownloadListener1);

    // Removing the listener.
    downloadMonitor.removeDownloadListener(uri1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();
  }

  @Test
  public void downloaderLifecycleCallback() {
    downloadMonitor.addDownloadListener(uri1, mockDownloadListener1);
    downloadMonitor.addDownloadListener(uri2, mockDownloadListener2);
    downloadMonitor.pausedForConnectivity();
    verify(mockDownloadListener1).onPausedForConnectivity();
    verify(mockDownloadListener2).onPausedForConnectivity();
  }
}
