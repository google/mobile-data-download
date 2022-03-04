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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.DetailedState;
import android.net.Uri;
import android.os.Build;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStore;
import com.google.android.libraries.mobiledatadownload.internal.logging.NoOpLoggingState;
import com.google.android.libraries.mobiledatadownload.testing.FakeTimeSource;
import com.google.mobiledatadownload.internal.MetadataProto.GroupKey;
import java.util.concurrent.Executor;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowNetworkInfo;

@RunWith(RobolectricTestRunner.class)
public class NetworkUsageMonitorTest {

  private static final Executor executor = directExecutor();
  private static final String GROUP_NAME_1 = "group-name-1";
  private static final String OWNER_PACKAGE_1 = "owner-package-1";
  private static final String VARIANT_ID_1 = "variant-id-1";
  private static final int VERSION_NUMBER_1 = 1;
  private static final int BUILD_ID_1 = 123;

  private static final String GROUP_NAME_2 = "group-name-2";
  private static final String OWNER_PACKAGE_2 = "owner-package-2";
  private static final String VARIANT_ID_2 = "variant-id-2";

  private static final int VERSION_NUMBER_2 = 2;
  private static final int BUILD_ID_2 = 456;

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

  private NetworkUsageMonitor networkUsageMonitor;
  private LoggingStateStore loggingStateStore;
  private Context context;
  private final FakeTimeSource clock = new FakeTimeSource();

  ConnectivityManager connectivityManager;

  @Rule public final TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();

    loggingStateStore = new NoOpLoggingState();

    // TODO(b/177015303): use builder when available
    networkUsageMonitor = new NetworkUsageMonitor(context, clock);

    this.connectivityManager =
        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
  }

  private void setNetworkConnectivityType(int networkConnectivityType) {
    Shadows.shadowOf(connectivityManager)
        .setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                DetailedState.CONNECTED,
                networkConnectivityType,
                0 /* subtype */,
                true /* isAvailable */,
                true /* isConnected */));
  }

  @Test
  public void testBytesWritten() throws Exception {
    // Setup 2 FileGroups:
    // FileGroup1: file1 and file2.
    // FileGroup2: file3.

    GroupKey groupKey1 =
        GroupKey.newBuilder()
            .setOwnerPackage(OWNER_PACKAGE_1)
            .setGroupName(GROUP_NAME_1)
            .setVariantId(VARIANT_ID_1)
            .build();
    networkUsageMonitor.monitorUri(
        uri1, groupKey1, BUILD_ID_1, VERSION_NUMBER_1, loggingStateStore);
    networkUsageMonitor.monitorUri(
        uri2, groupKey1, BUILD_ID_1, VERSION_NUMBER_1, loggingStateStore);

    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setOwnerPackage(OWNER_PACKAGE_2)
            .setGroupName(GROUP_NAME_2)
            .setVariantId(VARIANT_ID_2)
            .build();

    networkUsageMonitor.monitorUri(
        uri3, groupKey2, BUILD_ID_2, VERSION_NUMBER_2, loggingStateStore);

    Monitor.OutputMonitor outputMonitor1 = networkUsageMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor2 = networkUsageMonitor.monitorWrite(uri2);
    Monitor.OutputMonitor outputMonitor3 = networkUsageMonitor.monitorWrite(uri3);

    // outputMonitor1 is same as outputMonitor2 since they both monitor for FileGroup1.
    assertThat(outputMonitor1).isSameInstanceAs(outputMonitor2);
    assertThat(outputMonitor1).isNotSameInstanceAs(outputMonitor3);

    // First we have WIFI connection.
    // Downloaded 1 bytes on WIFI for uri1
    setNetworkConnectivityType(ConnectivityManager.TYPE_WIFI);
    outputMonitor1.bytesWritten(new byte[1], 0, 1);

    // Downloaded 2 bytes on WIFI for uri1
    outputMonitor1.bytesWritten(new byte[2], 0, 2);

    // Downloaded 4 bytes on WIFI for uri2
    outputMonitor2.bytesWritten(new byte[4], 0, 4);

    // Downloaded 8 bytes on WIFI for uri3
    outputMonitor3.bytesWritten(new byte[8], 0, 8);

    // Then we have CELLULAR connection.
    // Downloaded 16 bytes on CELLULAR for uri1
    setNetworkConnectivityType(ConnectivityManager.TYPE_MOBILE);
    outputMonitor1.bytesWritten(new byte[16], 0, 16);

    // Downloaded 32 bytes on CELLULAR for uri2
    outputMonitor2.bytesWritten(new byte[32], 0, 32);

    // Downloaded 64 bytes on CELLULAR for uri3
    outputMonitor3.bytesWritten(new byte[64], 0, 64);

    // close() will trigger saving counters to LoggingStateStore.
    outputMonitor1.close();
    outputMonitor2.close();
    outputMonitor3.close();

    // await executors idle here if we switch from directExecutor...
  }

  @Test
  public void testBytesWritten_multipleVersions() throws Exception {
    // Setup 2 versions of a FileGroup:
    // FileGroup v1: file1 and file2.
    // FileGroup v2: file2 and file3.
    GroupKey groupKey1 =
        GroupKey.newBuilder()
            .setOwnerPackage(OWNER_PACKAGE_1)
            .setGroupName(GROUP_NAME_1)
            .setVariantId(VARIANT_ID_1)
            .build();
    networkUsageMonitor.monitorUri(
        uri1, groupKey1, BUILD_ID_1, VERSION_NUMBER_1, loggingStateStore);
    networkUsageMonitor.monitorUri(
        uri2, groupKey1, BUILD_ID_1, VERSION_NUMBER_1, loggingStateStore);

    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setOwnerPackage(OWNER_PACKAGE_2)
            .setGroupName(GROUP_NAME_2)
            .setVariantId(VARIANT_ID_2)
            .build();

    // This would update uri2 to belong to FileGroup v2.
    networkUsageMonitor.monitorUri(
        uri2, groupKey2, BUILD_ID_2, VERSION_NUMBER_2, loggingStateStore);
    networkUsageMonitor.monitorUri(
        uri3, groupKey2, BUILD_ID_2, VERSION_NUMBER_2, loggingStateStore);

    Monitor.OutputMonitor outputMonitor1 = networkUsageMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor2 = networkUsageMonitor.monitorWrite(uri2);
    Monitor.OutputMonitor outputMonitor3 = networkUsageMonitor.monitorWrite(uri3);

    // outputMonitor2 is same as outputMonitor3 since they both monitor for the same version of the
    // same FileGroup.
    assertThat(outputMonitor1).isNotSameInstanceAs(outputMonitor2);
    assertThat(outputMonitor2).isSameInstanceAs(outputMonitor3);

    // First we have WIFI connection.
    // Downloaded 1 bytes on WIFI for uri1
    setNetworkConnectivityType(ConnectivityManager.TYPE_WIFI);
    outputMonitor1.bytesWritten(new byte[1], 0, 1);

    // Downloaded 2 bytes on WIFI for uri1
    outputMonitor1.bytesWritten(new byte[2], 0, 2);

    // Downloaded 4 bytes on WIFI for uri2
    outputMonitor2.bytesWritten(new byte[4], 0, 4);

    // Downloaded 8 bytes on WIFI for uri3
    outputMonitor3.bytesWritten(new byte[8], 0, 8);

    // Then we have CELLULAR connection.
    // Downloaded 16 bytes on CELLULAR for uri1
    setNetworkConnectivityType(ConnectivityManager.TYPE_MOBILE);
    outputMonitor1.bytesWritten(new byte[16], 0, 16);

    // Downloaded 32 bytes on CELLULAR for uri2
    outputMonitor2.bytesWritten(new byte[32], 0, 32);

    // Downloaded 64 bytes on CELLULAR for uri3
    outputMonitor3.bytesWritten(new byte[64], 0, 64);

    // close() will trigger saving counters to SharedPreference.
    outputMonitor1.close();
    outputMonitor2.close();
    outputMonitor3.close();
  }

  @Test
  public void testBytesWritten_flush_interval() throws Exception {
    // Setup 1 FileGroups:
    // FileGroup1: file1

    GroupKey groupKey1 =
        GroupKey.newBuilder()
            .setOwnerPackage(OWNER_PACKAGE_1)
            .setGroupName(GROUP_NAME_1)
            .setVariantId(VARIANT_ID_1)
            .build();
    networkUsageMonitor.monitorUri(
        uri1, groupKey1, BUILD_ID_1, VERSION_NUMBER_1, loggingStateStore);

    Monitor.OutputMonitor outputMonitor1 = networkUsageMonitor.monitorWrite(uri1);

    // Advance time so counters are flushed
    clock.advance(NetworkUsageMonitor.LOG_FREQUENCY_SECONDS + 1, SECONDS);

    // Downloaded 1 bytes on WIFI for uri1
    setNetworkConnectivityType(ConnectivityManager.TYPE_WIFI);
    outputMonitor1.bytesWritten(new byte[1], 0, 1);

    // Advance the clock by < LOG_FREQUENCY_SECONDS
    clock.advance(1, MILLISECONDS);
    outputMonitor1.bytesWritten(new byte[2], 0, 2);

    clock.advance(1, MILLISECONDS);
    outputMonitor1.bytesWritten(new byte[16], 0, 4);

    // Only the 1st and 2nd chunks were saved.
    assertThat(loggingStateStore.getAndResetAllDataUsage().get()).isEmpty();

    // Advance the clock by > LOG_FREQUENCY_SECONDS
    clock.advance(NetworkUsageMonitor.LOG_FREQUENCY_SECONDS + 1, SECONDS);
    outputMonitor1.bytesWritten(new byte[16], 0, 8);
  }

  @Test
  public void testBytesWritten_mix_write_append() throws Exception {
    // Setup 2 FileGroups:
    // FileGroup1: file1 and file2.
    // FileGroup2: file3.

    GroupKey groupKey1 =
        GroupKey.newBuilder()
            .setOwnerPackage(OWNER_PACKAGE_1)
            .setGroupName(GROUP_NAME_1)
            .setVariantId(VARIANT_ID_1)
            .build();
    networkUsageMonitor.monitorUri(
        uri1, groupKey1, BUILD_ID_1, VERSION_NUMBER_1, loggingStateStore);
    networkUsageMonitor.monitorUri(
        uri2, groupKey1, BUILD_ID_1, VERSION_NUMBER_1, loggingStateStore);

    GroupKey groupKey2 =
        GroupKey.newBuilder()
            .setOwnerPackage(OWNER_PACKAGE_2)
            .setGroupName(GROUP_NAME_2)
            .setVariantId(VARIANT_ID_2)
            .build();

    networkUsageMonitor.monitorUri(
        uri3, groupKey2, BUILD_ID_2, VERSION_NUMBER_2, loggingStateStore);

    Monitor.OutputMonitor outputMonitor1 = networkUsageMonitor.monitorWrite(uri1);
    Monitor.OutputMonitor outputMonitor2 = networkUsageMonitor.monitorAppend(uri2);
    Monitor.OutputMonitor outputMonitor3 = networkUsageMonitor.monitorAppend(uri3);

    // outputMonitor1 is same as outputMonitor2 since they both monitor for FileGroup1.
    assertThat(outputMonitor1).isSameInstanceAs(outputMonitor2);
    assertThat(outputMonitor1).isNotSameInstanceAs(outputMonitor3);

    // First we have WIFI connection.
    // Downloaded 1 bytes on WIFI for uri1
    setNetworkConnectivityType(ConnectivityManager.TYPE_WIFI);
    outputMonitor1.bytesWritten(new byte[1], 0, 1);

    // Downloaded 2 bytes on WIFI for uri1
    outputMonitor1.bytesWritten(new byte[2], 0, 2);

    // Downloaded 4 bytes on WIFI for uri2
    outputMonitor2.bytesWritten(new byte[4], 0, 4);

    // Downloaded 8 bytes on WIFI for uri3
    outputMonitor3.bytesWritten(new byte[8], 0, 8);

    // Then we have CELLULAR connection.
    // Downloaded 16 bytes on CELLULAR for uri1
    setNetworkConnectivityType(ConnectivityManager.TYPE_MOBILE);
    outputMonitor1.bytesWritten(new byte[16], 0, 16);

    // Downloaded 32 bytes on CELLULAR for uri2
    outputMonitor2.bytesWritten(new byte[32], 0, 32);

    // Downloaded 64 bytes on CELLULAR for uri3
    outputMonitor3.bytesWritten(new byte[64], 0, 64);

    // close() will trigger saving counters to SharedPreference.
    outputMonitor1.close();
    outputMonitor2.close();
    outputMonitor3.close();

    // await executors idle here if we switch from directExecutor...
  }

  @Test
  public void getNetworkConnectivityType() {
    setNetworkConnectivityType(ConnectivityManager.TYPE_WIFI);
    assertThat(NetworkUsageMonitor.isCellular(context)).isFalse();

    setNetworkConnectivityType(ConnectivityManager.TYPE_ETHERNET);
    assertThat(NetworkUsageMonitor.isCellular(context)).isFalse();

    setNetworkConnectivityType(ConnectivityManager.TYPE_VPN);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      assertThat(NetworkUsageMonitor.isCellular(context)).isFalse();

    } else {
      assertThat(NetworkUsageMonitor.isCellular(context)).isTrue();
    }

    setNetworkConnectivityType(ConnectivityManager.TYPE_MOBILE);
    assertThat(NetworkUsageMonitor.isCellular(context)).isTrue();

    // Fail to get NetworkInfo(return null) will return TYPE_WIFI.
    Shadows.shadowOf(connectivityManager).setActiveNetworkInfo(null);
    assertThat(NetworkUsageMonitor.isCellular(context)).isFalse();
  }

  @Test
  public void testNotRegisterUri() {
    // Creating the outputMonitor before registering the uri through monitorUri will return
    // null.
    Monitor.OutputMonitor outputMonitor = networkUsageMonitor.monitorWrite(uri1);
    assertThat(outputMonitor).isNull();

    outputMonitor = networkUsageMonitor.monitorAppend(uri1);
    assertThat(outputMonitor).isNull();
  }
}
