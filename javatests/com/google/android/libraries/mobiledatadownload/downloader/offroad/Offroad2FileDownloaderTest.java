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
package com.google.android.libraries.mobiledatadownload.downloader.offroad;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.net.Uri;
import android.util.Pair;
import androidx.test.core.app.ApplicationProvider;
import com.google.android.downloader.ConnectivityHandler;
import com.google.android.downloader.CookieJar;
import com.google.android.downloader.DownloadConstraints;
import com.google.android.downloader.DownloadConstraints.NetworkType;
import com.google.android.downloader.DownloadMetadata;
import com.google.android.downloader.Downloader;
import com.google.android.downloader.FloggerDownloaderLogger;
import com.google.android.downloader.OAuthTokenProvider;
import com.google.android.downloader.PlatformUrlEngine;
import com.google.android.downloader.contrib.InMemoryCookieJar;
import com.google.android.downloader.testing.TestUrlEngine;
import com.google.android.downloader.testing.TestUrlEngine.TestUrlRequest;
import com.google.android.libraries.mobiledatadownload.DownloadException;
import com.google.android.libraries.mobiledatadownload.DownloadException.DownloadResultCode;
import com.google.android.libraries.mobiledatadownload.downloader.DownloadRequest;
import com.google.android.libraries.mobiledatadownload.file.SynchronousFileStorage;
import com.google.android.libraries.mobiledatadownload.file.backends.AndroidFileBackend;
import com.google.android.libraries.mobiledatadownload.file.backends.JavaFileBackend;
import com.google.android.libraries.mobiledatadownload.file.common.testing.TemporaryUri;
import com.google.android.libraries.mobiledatadownload.file.integration.downloader.DownloadMetadataStore;
import com.google.android.libraries.mobiledatadownload.file.openers.ReadStreamOpener;
import com.google.android.libraries.mobiledatadownload.file.openers.WriteStreamOpener;
import com.google.android.libraries.mobiledatadownload.testing.TestHttpServer;
import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.runtime.RunfilesPaths;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Unit tests for {@link
 * com.google.android.libraries.mobiledatadownload.downloader.offroad.Offroad2FileDownloader}.
 */
@RunWith(RobolectricTestRunner.class)
public class Offroad2FileDownloaderTest {

  private static final int TRAFFIC_TAG = 1000;
  private static final ScheduledExecutorService DOWNLOAD_EXECUTOR =
      Executors.newScheduledThreadPool(2);
  private static final ListeningExecutorService CONTROL_EXECUTOR =
      MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

  private static final long MAX_CONNECTION_WAIT_SECS = 10L;
  private static final int MAX_PLATFORM_ENGINE_TIMEOUT_MILLIS = 1000;

  /** Endpoint that can be registered to TestHttpServer to serve a file that can be downloaded. */
  private static final String TEST_DATA_ENDPOINT = "/testfile";

  /** Path to the underlying test data that is the source of what TestHttpServer will serve. */
  private static final String TEST_DATA_PATH =
      RunfilesPaths.resolve(
              "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/full_file.txt")
          .toString();

  private static final String PARTIAL_TEST_DATA_PATH =
      RunfilesPaths.resolve(
              "third_party/java_src/android_libs/mobiledatadownload/javatests/com/google/android/libraries/mobiledatadownload/testdata/partial_file.txt")
          .toString();

  private Context context;

  private Uri.Builder testUrlPrefix;
  private TestHttpServer testHttpServer;

  private SynchronousFileStorage fileStorage;
  private FakeConnectivityHandler fakeConnectivityHandler;
  private FakeDownloadMetadataStore fakeDownloadMetadataStore;
  private FakeOAuthTokenProvider fakeOAuthTokenProvider;
  private FakeTrafficStatsTagger fakeTrafficStatsTagger;
  private TestUrlEngine testUrlEngine;
  private CookieJar cookieJar;
  private Downloader downloader;

  private Offroad2FileDownloader fileDownloader;

  @Rule(order = 1)
  public TemporaryUri tmpUri = new TemporaryUri();

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    fileStorage =
        new SynchronousFileStorage(
            /* backends= */ ImmutableList.of(
                AndroidFileBackend.builder(context).build(), new JavaFileBackend()),
            /* transforms= */ ImmutableList.of(),
            /* monitors= */ ImmutableList.of());

    fakeDownloadMetadataStore = new FakeDownloadMetadataStore();

    fakeTrafficStatsTagger = new FakeTrafficStatsTagger();

    PlatformUrlEngine urlEngine =
        new PlatformUrlEngine(
            CONTROL_EXECUTOR,
            MAX_PLATFORM_ENGINE_TIMEOUT_MILLIS,
            MAX_PLATFORM_ENGINE_TIMEOUT_MILLIS,
            /* followHttpRedirects= */ false,
            fakeTrafficStatsTagger);

    testUrlEngine = new TestUrlEngine(urlEngine);

    fakeConnectivityHandler = new FakeConnectivityHandler();

    downloader =
        new Downloader.Builder()
            .withIOExecutor(CONTROL_EXECUTOR)
            .withConnectivityHandler(fakeConnectivityHandler)
            .withMaxConcurrentDownloads(2)
            .withLogger(new FloggerDownloaderLogger())
            .addUrlEngine(ImmutableList.of("http", "https"), testUrlEngine)
            .build();

    fakeOAuthTokenProvider = new FakeOAuthTokenProvider();

    cookieJar = new InMemoryCookieJar();

    fileDownloader =
        new Offroad2FileDownloader(
            downloader,
            fileStorage,
            DOWNLOAD_EXECUTOR,
            fakeOAuthTokenProvider,
            fakeDownloadMetadataStore,
            ExceptionHandler.withDefaultHandling(),
            Optional.of(() -> cookieJar),
            Optional.absent());

    testHttpServer = new TestHttpServer();
    testUrlPrefix = testHttpServer.startServer();
  }

  @After
  public void tearDown() throws Exception {
    testHttpServer.stopServer();
    fakeConnectivityHandler.reset();
    fakeDownloadMetadataStore.reset();
    fakeOAuthTokenProvider.reset();
    fakeTrafficStatsTagger.reset();
  }

  @Test
  public void testStartDownloading_downloadConditionsNull_usesWifiOnly() throws Exception {
    Uri fileUri = tmpUri.newUri();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    // Setup custom handler to ensure expected constraints.
    fakeConnectivityHandler.customHandler =
        constraints -> {
          assertThat(constraints.requireUnmeteredNetwork()).isTrue();
          assertThat(constraints.requiredNetworkTypes())
              .containsExactly(NetworkType.WIFI, NetworkType.ETHERNET, NetworkType.BLUETOOTH);

          return immediateVoidFuture();
        };

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NONE)
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    assertThat(fakeConnectivityHandler.invokedCustomHandler).isTrue();

    // Check DownloadMetadataStore calls
    assertThat(fakeDownloadMetadataStore.upsertCalls).containsKey(fileUri);
    assertThat(fakeDownloadMetadataStore.deleteCalls).contains(fileUri);
    assertThat(fakeDownloadMetadataStore.read(fileUri).get(MAX_CONNECTION_WAIT_SECS, SECONDS))
        .isAbsent();
  }

  @Test
  public void testStartDownloading_wifi() throws Exception {
    Uri fileUri = tmpUri.newUri();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    // Setup custom handler to ensure expected constraints.
    fakeConnectivityHandler.customHandler =
        constraints -> {
          assertThat(constraints.requireUnmeteredNetwork()).isTrue();
          assertThat(constraints.requiredNetworkTypes())
              .containsExactly(NetworkType.WIFI, NetworkType.ETHERNET, NetworkType.BLUETOOTH);

          return immediateVoidFuture();
        };

    // Setup custom handler to add authorization token
    fakeOAuthTokenProvider.customHandler = unused -> immediateFuture("TEST_TOKEN");

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_UNMETERED)
                .setTrafficTag(TRAFFIC_TAG)
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    assertThat(testUrlEngine.storedRequests()).hasSize(1);
    TestUrlRequest request = testUrlEngine.storedRequests().get(0);
    assertThat(request.trafficTag()).isEqualTo(TRAFFIC_TAG);
    assertThat(request.headers()).containsKey(HttpHeaders.AUTHORIZATION);
    assertThat(request.headers())
        .valuesForKey(HttpHeaders.AUTHORIZATION)
        .contains("Bearer TEST_TOKEN");

    assertThat(fakeConnectivityHandler.invokedCustomHandler).isTrue();
    assertThat(fakeOAuthTokenProvider.invokedCustomHandler).isTrue();
    assertThat(fakeTrafficStatsTagger.storedTrafficTags).contains(TRAFFIC_TAG);
  }

  @Test
  public void testStartDownloading_wifi_notSettingTrafficTag() throws Exception {
    Uri fileUri = tmpUri.newUri();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_UNMETERED)
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    assertThat(testUrlEngine.storedRequests()).hasSize(1);
    TestUrlRequest request = testUrlEngine.storedRequests().get(0);
    assertThat(request.trafficTag()).isEqualTo(0);

    assertThat(fakeTrafficStatsTagger.storedTrafficTags).doesNotContain(TRAFFIC_TAG);
  }

  @Test
  public void testStartDownloading_extraHttpHeaders() throws Exception {
    Uri fileUri = tmpUri.newUri();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_UNMETERED)
                .setTrafficTag(TRAFFIC_TAG)
                .setExtraHttpHeaders(
                    ImmutableList.of(
                        Pair.create("user-agent", "mdd-downloader"),
                        Pair.create("other-header", "header-value")))
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    assertThat(testUrlEngine.storedRequests()).hasSize(1);
    TestUrlRequest request = testUrlEngine.storedRequests().get(0);
    assertThat(request.headers().keySet()).containsExactly("user-agent", "other-header");
    assertThat(request.headers()).valuesForKey("user-agent").contains("mdd-downloader");
    assertThat(request.headers()).valuesForKey("other-header").contains("header-value");
  }

  @Test
  public void testStartDownloading_cellular() throws Exception {
    Uri fileUri = tmpUri.newUri();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    // Setup custom handler to ensure expected constraints.
    fakeConnectivityHandler.customHandler =
        constraints -> {
          assertThat(constraints).isEqualTo(DownloadConstraints.NETWORK_CONNECTED);

          return immediateVoidFuture();
        };

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_CONNECTED)
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    assertThat(fakeConnectivityHandler.invokedCustomHandler).isTrue();
  }

  @Test
  public void testStartDownloading_failed() throws Exception {
    Uri fileUri = tmpUri.newUri();

    // Simulate failure due to bad url;
    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload("https://BADURL")
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_UNMETERED)
                .build());

    ExecutionException exception = assertThrows(ExecutionException.class, downloadFuture::get);
    assertThat(exception).hasCauseThat().isInstanceOf(DownloadException.class);

    DownloadException dex = (DownloadException) exception.getCause();
    assertThat(dex.getDownloadResultCode()).isEqualTo(DownloadResultCode.UNKNOWN_ERROR);
  }

  @Test
  public void testStartDownloading_whenPartialFile_whenMetadataNotPresent_getsFullFile()
      throws Exception {
    Uri fileUri = tmpUri.newUri();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    // Write partial content to file but do _not_ write partial metadata.
    try (InputStream inStream =
            fileStorage.open(
                Uri.parse("file://" + PARTIAL_TEST_DATA_PATH), ReadStreamOpener.create());
        OutputStream outStream = fileStorage.open(fileUri, WriteStreamOpener.create())) {
      ByteStreams.copy(inStream, outStream);
    }

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NONE)
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    // Check that full file is requested (no HTTP range headers)
    assertThat(testUrlEngine.storedRequests().get(0).headers())
        .doesNotContainKey(HttpHeaders.RANGE);
    assertThat(testUrlEngine.storedRequests().get(0).headers())
        .doesNotContainKey(HttpHeaders.IF_RANGE);

    // Check DownloadMetadataStore calls
    assertThat(fakeDownloadMetadataStore.readCalls).contains(fileUri);
    assertThat(fakeDownloadMetadataStore.upsertCalls).containsKey(fileUri);
    assertThat(fakeDownloadMetadataStore.deleteCalls).contains(fileUri);
    assertThat(fakeDownloadMetadataStore.read(fileUri).get(MAX_CONNECTION_WAIT_SECS, SECONDS))
        .isAbsent();
  }

  @Test
  public void testStartDownloading_whenPartialFile_whenMetadataPresent_reusesPartialFile()
      throws Exception {
    Uri fileUri = tmpUri.newUri();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    // Write partial content to file.
    try (InputStream inStream =
            fileStorage.open(
                Uri.parse("file://" + PARTIAL_TEST_DATA_PATH), ReadStreamOpener.create());
        OutputStream outStream = fileStorage.open(fileUri, WriteStreamOpener.create())) {
      ByteStreams.copy(inStream, outStream);
    }

    // Write existing metadata to file.
    fakeDownloadMetadataStore
        .upsert(fileUri, DownloadMetadata.create("test", 0))
        .get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NONE)
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    // Check that full file is requested (no HTTP range headers)
    assertThat(testUrlEngine.storedRequests().get(0).headers()).containsKey(HttpHeaders.RANGE);
    assertThat(testUrlEngine.storedRequests().get(0).headers()).containsKey(HttpHeaders.IF_RANGE);

    // Check DownloadMetadataStore calls
    assertThat(fakeDownloadMetadataStore.readCalls).contains(fileUri);
    assertThat(fakeDownloadMetadataStore.upsertCalls).containsKey(fileUri);
    assertThat(fakeDownloadMetadataStore.deleteCalls).contains(fileUri);
    assertThat(fakeDownloadMetadataStore.read(fileUri).get(MAX_CONNECTION_WAIT_SECS, SECONDS))
        .isAbsent();
  }

  @Test
  public void testCancelDownload_notFinishedFuture() throws Exception {
    // Build a file uri so it's not created by TemporaryUri -- we can then make assertions on the
    // existence of the file.
    Uri fileUri = tmpUri.newUriBuilder().appendPath("unique").build();

    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    // Block download using connectivity check
    CountDownLatch blockingLatch = new CountDownLatch(1);
    fakeConnectivityHandler.customHandler =
        unused ->
            PropagatedFutures.submitAsync(
                () -> {
                  blockingLatch.await();
                  return immediateVoidFuture();
                },
                CONTROL_EXECUTOR);

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_UNMETERED)
                .build());

    assertThat(downloadFuture.isDone()).isFalse();
    assertThat(fileStorage.exists(fileUri)).isFalse();

    downloadFuture.cancel(true);

    assertThat(downloadFuture.isCancelled()).isTrue();
    assertThat(fileStorage.exists(fileUri)).isFalse();

    // count down latch to clean up test.
    blockingLatch.countDown();
  }

  @Test
  public void testCancelDownload_onAlreadySucceededFuture() throws Exception {
    // Build a file uri so it's not created by TemporaryUri -- we can then make assertions on the
    // existence of the file.
    Uri fileUri = tmpUri.getRootUriBuilder().appendPath("unique").build();
    String urlToDownload = testUrlPrefix.path(TEST_DATA_ENDPOINT).toString();
    testHttpServer.registerTextFile(TEST_DATA_ENDPOINT, TEST_DATA_PATH);

    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload(urlToDownload)
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_UNMETERED)
                .build());

    downloadFuture.get(MAX_CONNECTION_WAIT_SECS, SECONDS);

    // Assert that on device file is created and remains even after cancel.
    assertThat(fileStorage.exists(fileUri)).isTrue();

    downloadFuture.cancel(true);

    assertThat(fileStorage.exists(fileUri)).isTrue();
  }

  @Test
  public void testCancelDownload_onAlreadyFailedFuture() throws Exception {
    // Build a file uri so it's not created by TemporaryUri -- we can then make assertions on the
    // existence of the file.
    Uri fileUri = tmpUri.getRootUriBuilder().appendPath("unique").build();

    // Simulate failure due to bad url;
    ListenableFuture<Void> downloadFuture =
        fileDownloader.startDownloading(
            DownloadRequest.newBuilder()
                .setFileUri(fileUri)
                .setUrlToDownload("https://BADURL")
                .setDownloadConstraints(
                    com.google.android.libraries.mobiledatadownload.downloader.DownloadConstraints
                        .NETWORK_UNMETERED)
                .build());

    Exception unused = assertThrows(ExecutionException.class, downloadFuture::get);

    // Assert that on device file is not created and doesn't get created after cancel.
    assertThat(fileStorage.exists(fileUri)).isFalse();

    downloadFuture.cancel(true);

    assertThat(fileStorage.exists(fileUri)).isFalse();
  }

  /** Custom {@link ConnectivityHandler} that allows custom logic to be used for each test. */
  static final class FakeConnectivityHandler implements ConnectivityHandler {
    private static final AsyncFunction<DownloadConstraints, Void> DEFAULT_HANDLER =
        unused -> immediateVoidFuture();

    private AsyncFunction<DownloadConstraints, Void> customHandler = DEFAULT_HANDLER;

    private boolean invokedCustomHandler = false;

    @Override
    public ListenableFuture<Void> checkConnectivity(DownloadConstraints constraints) {
      ListenableFuture<Void> returnFuture;
      try {
        returnFuture = customHandler.apply(constraints);
      } catch (Exception e) {
        returnFuture = immediateFailedFuture(e);
      }

      invokedCustomHandler = true;
      return returnFuture;
    }

    public boolean invokedCustomHandler() {
      return invokedCustomHandler;
    }

    /**
     * Reset inner state to initial values.
     *
     * <p>This prevents failures caused by cross test pollution.
     */
    public void reset() {
      customHandler = DEFAULT_HANDLER;
      invokedCustomHandler = false;
    }
  }

  /** Custom {@link OAuthTokenProvider} that allows custom logic for each test. */
  static final class FakeOAuthTokenProvider implements OAuthTokenProvider {
    private static final AsyncFunction<URI, String> DEFAULT_HANDLER =
        unused -> immediateFuture(null);

    private AsyncFunction<URI, String> customHandler = DEFAULT_HANDLER;

    private boolean invokedCustomHandler = false;

    @Override
    public ListenableFuture<String> provideOAuthToken(URI uri) {
      ListenableFuture<String> returnFuture;
      try {
        returnFuture = customHandler.apply(uri);
      } catch (Exception e) {
        returnFuture = immediateFailedFuture(e);
      }

      invokedCustomHandler = true;
      return returnFuture;
    }

    /**
     * Reset inner state to initial values.
     *
     * <p>This prevents failures caused by cross test pollution.
     */
    public void reset() {
      customHandler = DEFAULT_HANDLER;
      invokedCustomHandler = false;
    }
  }

  private static final class FakeTrafficStatsTagger
      implements PlatformUrlEngine.TrafficStatsTagger {
    private final List<Integer> storedTrafficTags = new ArrayList<>();

    @Override
    public int getAndSetThreadStatsTag(int tag) {
      int prevTag = storedTrafficTags.isEmpty() ? 0 : Iterables.getLast(storedTrafficTags);
      storedTrafficTags.add(tag);
      return prevTag;
    }

    @Override
    public void restoreThreadStatsTag(int tag) {
      storedTrafficTags.add(tag);
    }

    public void reset() {
      storedTrafficTags.clear();
    }
  }

  private static final class FakeDownloadMetadataStore implements DownloadMetadataStore {

    // Backing storage structure for metadata.
    private final Map<Uri, DownloadMetadata> storedMetadata = new HashMap<>();

    // Tracking of what calls are made on this fake.
    final List<Uri> readCalls = new ArrayList<>();
    final Map<Uri, List<DownloadMetadata>> upsertCalls = new HashMap<>();
    final List<Uri> deleteCalls = new ArrayList<>();

    @Override
    public ListenableFuture<Optional<DownloadMetadata>> read(Uri uri) {
      readCalls.add(uri);

      return immediateFuture(Optional.fromNullable(storedMetadata.get(uri)));
    }

    @Override
    public ListenableFuture<Void> upsert(Uri uri, DownloadMetadata downloadMetadata) {
      upsertCalls.putIfAbsent(uri, new ArrayList<>());
      upsertCalls.get(uri).add(downloadMetadata);

      storedMetadata.put(uri, downloadMetadata);
      return immediateVoidFuture();
    }

    @Override
    public ListenableFuture<Void> delete(Uri uri) {
      deleteCalls.add(uri);

      storedMetadata.remove(uri);
      return immediateVoidFuture();
    }

    public void reset() {
      storedMetadata.clear();

      readCalls.clear();
      upsertCalls.clear();
      deleteCalls.clear();
    }
  }
}
