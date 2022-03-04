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
package com.google.android.libraries.mobiledatadownload.monitor;

import static com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitor.LOG_FREQUENCY;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import android.net.Uri;
import com.google.android.libraries.mobiledatadownload.DownloadListener;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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

  // Use directExecutor to ensure the order of test verification.
  private static final Executor CONTROL_EXECUTOR = MoreExecutors.directExecutor();

  private static final String GROUP_NAME_1 = "group-name-1";

  private static final String GROUP_NAME_2 = "group-name-2";

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

  @Mock
  private com.google.android.libraries.mobiledatadownload.lite.DownloadListener
      mockLiteDownloadListener1;

  @Mock
  private com.google.android.libraries.mobiledatadownload.lite.DownloadListener
      mockLiteDownloadListener2;

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Before
  public void setUp() throws Exception {
    downloadMonitor = new DownloadProgressMonitor(clock, CONTROL_EXECUTOR);
  }

  @Test
  public void bytesWritten_cleanSlate() throws Exception {
    downloadMonitor.addDownloadListener(GROUP_NAME_1, mockDownloadListener1);
    downloadMonitor.addDownloadListener(GROUP_NAME_2, mockDownloadListener2);

    // Setup 2 FileGroups:
    // FileGroup1: file1 and file2.
    // FileGroup2: file3.
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);
    downloadMonitor.monitorUri(uri2, GROUP_NAME_1);
    downloadMonitor.monitorUri(uri3, GROUP_NAME_2);

    Monitor.OutputMonitor outputMonitor1 = downloadMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor2 = downloadMonitor.monitorWrite(uri2);
    Monitor.OutputMonitor outputMonitor3 = downloadMonitor.monitorWrite(uri3);

    // outputMonitor1 is same as outputMonitor2 since they both monitor for FileGroup1.
    assertThat(outputMonitor1).isSameInstanceAs(outputMonitor2);
    assertThat(outputMonitor1).isNotSameInstanceAs(outputMonitor3);

    // The 1st bytesWritten was buffered.
    outputMonitor1.bytesWritten(new byte[1], 0, 1);
    verifyNoInteractions(mockDownloadListener1);

    // Now the buffered time passed.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 2nd bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 1, 2);
    // 1 (first bytesWritten) + 2 (2nd bytesWritten)
    verify(mockDownloadListener1).onProgress(1 + 2);

    // The 3rd bytesWritten was buffered
    outputMonitor1.bytesWritten(new byte[1], 3, 4);
    verify(mockDownloadListener1, times(0)).onProgress(1 + 2 + 4);

    // Now the buffered time passed again.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 4th bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 7, 8);
    verify(mockDownloadListener1).onProgress(1 + 2 + 4 + 8);

    // No bytes were downloaded for FileGroup2.
    verifyNoInteractions(mockDownloadListener2);
  }

  @Test
  public void bytesWritten_forSingleFileDownload_cleanSlate() throws Exception {
    downloadMonitor.addDownloadListener(uri1, mockLiteDownloadListener1);
    downloadMonitor.addDownloadListener(uri2, mockLiteDownloadListener2);

    Monitor.OutputMonitor outputMonitor1 = downloadMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor duplicateOutputMonitor1 = downloadMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor3 = downloadMonitor.monitorWrite(uri3);

    // outputMonitor1 is same as duplicateOutputMonitor1 since they both monitor for uri1.
    assertThat(outputMonitor1).isSameInstanceAs(duplicateOutputMonitor1);
    assertThat(outputMonitor1).isNotSameInstanceAs(outputMonitor3);

    // The 1st bytesWritten was buffered.
    outputMonitor1.bytesWritten(new byte[1], 0, 1);
    verifyNoInteractions(mockLiteDownloadListener1);

    // Now the buffered time passed.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 2nd bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 1, 2);
    // 1 (first bytesWritten) + 2 (2nd bytesWritten)
    verify(mockLiteDownloadListener1).onProgress(1 + 2);

    // The 3rd bytesWritten was buffered
    outputMonitor1.bytesWritten(new byte[1], 3, 4);
    verify(mockLiteDownloadListener1, times(0)).onProgress(1 + 2 + 4);

    // Now the buffered time passed again.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 4th bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 7, 8);
    verify(mockLiteDownloadListener1).onProgress(1 + 2 + 4 + 8);

    // No bytes were downloaded for uri2
    verifyNoInteractions(mockLiteDownloadListener2);
  }

  @Test
  public void bytesWritten_forBothFileAndFileGroupDownloads_cleanSlate() throws Exception {
    downloadMonitor.addDownloadListener(GROUP_NAME_1, mockDownloadListener1);
    downloadMonitor.addDownloadListener(uri2, mockLiteDownloadListener2);

    // Setup 1 FileGroup
    // FileGroup1: file1
    // file2 is a single file download
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);

    Monitor.OutputMonitor outputMonitor1 = downloadMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor2 = downloadMonitor.monitorWrite(uri2);

    // outputMonitor1 is not the same as outputMonitor2
    assertThat(outputMonitor1).isNotSameInstanceAs(outputMonitor2);

    // The 1st bytesWritten was buffered.
    outputMonitor1.bytesWritten(new byte[1], 0, 1);
    verifyNoInteractions(mockDownloadListener1);

    outputMonitor2.bytesWritten(new byte[1], 0, 1);
    verifyNoInteractions(mockLiteDownloadListener2);

    // Now the buffered time passed.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 2nd bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 1, 2);
    // 1 (first bytesWritten) + 2 (2nd bytesWritten)
    verify(mockDownloadListener1).onProgress(1 + 2);

    outputMonitor2.bytesWritten(new byte[1], 1, 2);
    verify(mockLiteDownloadListener2).onProgress(1 + 2);

    // The 3rd bytesWritten was buffered
    outputMonitor1.bytesWritten(new byte[1], 3, 4);
    verify(mockDownloadListener1, times(0)).onProgress(1 + 2 + 4);

    outputMonitor2.bytesWritten(new byte[1], 3, 4);
    verify(mockLiteDownloadListener2, times(0)).onProgress(1 + 2 + 4);

    // Now the buffered time passed again.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 4th bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 7, 8);
    verify(mockDownloadListener1).onProgress(1 + 2 + 4 + 8);

    outputMonitor2.bytesWritten(new byte[1], 7, 8);
    verify(mockLiteDownloadListener2).onProgress(1 + 2 + 4 + 8);
  }

  @Test
  public void bytesWritten_partialAndDownloadedFiles() throws Exception {

    downloadMonitor.addDownloadListener(GROUP_NAME_1, mockDownloadListener1);
    downloadMonitor.addDownloadListener(GROUP_NAME_2, mockDownloadListener2);

    // Setup a FileGroup with 3 files. file1 is partially downloaded (1000 bytes).
    // file2 is empty. file3 is downloaded (2000 bytes).
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);
    downloadMonitor.notifyCurrentFileSize(GROUP_NAME_1, 1000 /* currentSize */);
    downloadMonitor.monitorUri(uri2, GROUP_NAME_1);
    downloadMonitor.notifyCurrentFileSize(GROUP_NAME_1, 2000 /* currentSize */);

    Monitor.OutputMonitor outputMonitor1 = downloadMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor2 = downloadMonitor.monitorWrite(uri2);

    // both monitors are the same since 3 files are from same file group.
    assertThat(outputMonitor1).isSameInstanceAs(outputMonitor2);

    // The 1st bytesWritten was buffered.
    outputMonitor1.bytesWritten(new byte[1], 0, 1);
    verifyNoInteractions(mockDownloadListener1);

    // Now the buffered time passed.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 2nd bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 1, 2);
    // 2000 (downloaded file) + 1000 (partially downloaded) + 1 (first bytesWritten) + 2 (2nd
    // bytesWritten)
    verify(mockDownloadListener1).onProgress(2000 + 1000 + 1 + 2);

    // The 3rd bytesWritten was buffered
    outputMonitor1.bytesWritten(new byte[1], 3, 4);
    verify(mockDownloadListener1, times(0)).onProgress(2000 + 1000 + 1 + 2 + 4);

    // Now the buffered time passed again.
    clock.advance(LOG_FREQUENCY + 100L, TimeUnit.MILLISECONDS);

    // The 4th bytesWritten now triggered onProgress.
    outputMonitor1.bytesWritten(new byte[1], 7, 8);
    verify(mockDownloadListener1).onProgress(2000 + 1000 + 1 + 2 + 4 + 8);

    // No bytes were downloaded for FileGroup2.
    verifyNoInteractions(mockDownloadListener2);
  }

  @Test
  public void bytesWritten_addDownloadListener() throws Exception {

    // There is no download listener for GROUP_NAME_1.
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();

    // Adding a listener now.
    downloadMonitor.addDownloadListener(GROUP_NAME_1, mockDownloadListener1);
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);

    // Removing the listener.
    downloadMonitor.removeDownloadListener(GROUP_NAME_1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();
  }

  @Test
  public void bytesWritten_forSingleFileDownload_addAndRemoveDownloadListener() throws Exception {
    // There is no download listener for uri1.
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();

    // Adding a listener now.
    downloadMonitor.addDownloadListener(uri1, mockLiteDownloadListener1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNotNull();

    // Removing the listener.
    downloadMonitor.removeDownloadListener(uri1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();
  }

  @Test
  public void bytesWritten_forBothFileAndFileGroupDownloads_addAndRemoveDownloadListener()
      throws Exception {
    // There is no download listener for uri1.
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();

    // There is no download listener for GROUP_NAME_2
    downloadMonitor.monitorUri(uri2, GROUP_NAME_2);
    assertThat(downloadMonitor.monitorWrite(uri2)).isNull();

    // Adding a listener for uri1
    downloadMonitor.addDownloadListener(uri1, mockLiteDownloadListener1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNotNull();
    // uri2 (File Group 2) should still have no monitor.
    assertThat(downloadMonitor.monitorWrite(uri2)).isNull();

    // Adding a listener for uri2 (File Group 2)
    downloadMonitor.addDownloadListener(GROUP_NAME_2, mockDownloadListener2);
    downloadMonitor.monitorUri(uri2, GROUP_NAME_2);
    assertThat(downloadMonitor.monitorWrite(uri2)).isNotNull();
    // uri1 still has monitor
    assertThat(downloadMonitor.monitorWrite(uri1)).isNotNull();

    // Removing the listener for uri1
    downloadMonitor.removeDownloadListener(uri1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();
    // uri2 (File Group 2) should still have monitor.
    assertThat(downloadMonitor.monitorWrite(uri2)).isNotNull();

    // Removing listener for uri2 (File Group 2)
    downloadMonitor.removeDownloadListener(GROUP_NAME_2);
    assertThat(downloadMonitor.monitorWrite(uri2)).isNull();
    // uri1 still has no monitor
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();
  }

  @Test
  public void bytesWritten_downloadListenerRemoved() throws Exception {
    // There is no download listener for GROUP_NAME_1.
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();

    // Adding a listener now.
    downloadMonitor.addDownloadListener(GROUP_NAME_1, mockDownloadListener1);
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);

    Monitor.OutputMonitor outputMonitor = downloadMonitor.monitorWrite(uri1);

    // Removing the listener.
    downloadMonitor.removeDownloadListener(GROUP_NAME_1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();

    outputMonitor.bytesWritten(new byte[1], 3, 4);

    // Make sure that onProgress is not triggered if the DownloadListener is no longer being used.
    verifyNoInteractions(mockDownloadListener1);
  }

  @Test
  public void bytesWritten_forSingleFileDownload_downloadListenerRemoved() throws Exception {
    downloadMonitor.addDownloadListener(uri1, mockLiteDownloadListener1);

    // Monitor should exist
    Monitor.OutputMonitor outputMonitor = downloadMonitor.monitorWrite(uri1);
    assertThat(outputMonitor).isNotNull();

    // Removing the listener
    downloadMonitor.removeDownloadListener(uri1);

    // Monitor should not exist anymore
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();

    outputMonitor.bytesWritten(new byte[1], 3, 4);

    // Make sure onProgress is not triggered if DownloadListener is not longer used.
    verify(mockLiteDownloadListener1, never()).onProgress(anyLong());
  }

  @Test
  public void bytesWritten_forBothFileAndFileGroupDownloads_downloadListenerRemoved()
      throws Exception {
    // There is no download listener for GROUP_NAME_1 and no listener for uri2
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);
    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();
    assertThat(downloadMonitor.monitorWrite(uri2)).isNull();

    // Adding a listener for GROUP_NAME_1 and single file uri2
    downloadMonitor.addDownloadListener(uri2, mockLiteDownloadListener2);
    downloadMonitor.addDownloadListener(GROUP_NAME_1, mockDownloadListener1);
    downloadMonitor.monitorUri(uri1, GROUP_NAME_1);

    Monitor.OutputMonitor outputMonitor1 = downloadMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor2 = downloadMonitor.monitorWrite(uri2);

    // Removing the listener.
    downloadMonitor.removeDownloadListener(GROUP_NAME_1);
    downloadMonitor.removeDownloadListener(uri2);

    assertThat(downloadMonitor.monitorWrite(uri1)).isNull();
    assertThat(downloadMonitor.monitorWrite(uri2)).isNull();

    outputMonitor1.bytesWritten(new byte[1], 3, 4);
    outputMonitor2.bytesWritten(new byte[1], 3, 4);

    // Make sure that onProgress is not triggered if the DownloadListener is no longer being used.
    verifyNoInteractions(mockDownloadListener1);
    verifyNoInteractions(mockLiteDownloadListener2);
  }

  @Test
  public void downloaderLifecycleCallback() throws Exception {
    downloadMonitor.addDownloadListener(GROUP_NAME_1, mockDownloadListener1);
    downloadMonitor.addDownloadListener(GROUP_NAME_2, mockDownloadListener2);
    downloadMonitor.pausedForConnectivity();
    verify(mockDownloadListener1).pausedForConnectivity();
    verify(mockDownloadListener2).pausedForConnectivity();
  }

  @Test
  public void downloaderLifecycleCallback_forFileAndFileGroupDownloads() throws Exception {
    downloadMonitor.addDownloadListener(uri1, mockLiteDownloadListener1);
    downloadMonitor.addDownloadListener(GROUP_NAME_2, mockDownloadListener2);

    downloadMonitor.pausedForConnectivity();

    verify(mockLiteDownloadListener1).onPausedForConnectivity();
    verify(mockDownloadListener2).pausedForConnectivity();
  }
}
