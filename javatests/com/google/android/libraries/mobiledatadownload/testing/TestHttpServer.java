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
package com.google.android.libraries.mobiledatadownload.testing;

import android.net.Uri;
import android.util.Log;
import com.google.common.base.Optional;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.HttpRequestHandlerRegistry;
import org.apache.http.protocol.HttpService;

/** TestHttpServer is a simple http server that listens to http requests on a single thread. */
public final class TestHttpServer {

  private static final String TAG = "TestHttpServer";
  private static final String TEST_HOST = "localhost";

  private static final String HEAD_REQUEST_METHOD = "HEAD";
  private static final String ETAG_HEADER = "ETag";
  private static final String IF_NONE_MATCH_HEADER = "If-None-Match";
  private static final String BINARY_CONTENT_TYPE = "application/binary";
  private static final String PROTO_CONTENT_TYPE = "application/x-protobuf";
  private static final String TEXT_CONTENT_TYPE = "text/plain";

  private final HttpParams httpParams = new BasicHttpParams();
  private final HttpService httpService;
  private final HttpRequestHandlerRegistry registry;
  private final AtomicBoolean finished = new AtomicBoolean();

  private Thread serverThread;
  private ServerSocket serverSocket;
  // 0 means user didn't specify a port number and will use automatically assigned port.
  private final int userDesignatedPort;

  public TestHttpServer() {
    this(0);
  }

  public TestHttpServer(int portNumber) {
    userDesignatedPort = portNumber;
    httpParams.setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true);
    registry = new HttpRequestHandlerRegistry();

    httpService =
        new HttpService(
            new BasicHttpProcessor(),
            new DefaultConnectionReuseStrategy(),
            new DefaultHttpResponseFactory());
    httpService.setHandlerResolver(registry);
    httpService.setParams(httpParams);
  }
  /** Registers a handler for an endpoint pattern. */
  public void registerHandler(String pattern, HttpRequestHandler handler) {
    registry.register(pattern, handler);
  }

  /** Registers a handler that binds onto a text file for an endpoint pattern. */
  public void registerTextFile(String pattern, String filepath) {
    registerFile(pattern, filepath, TEXT_CONTENT_TYPE, /* eTagOptional = */ Optional.absent());
  }

  /** Registers a handler that binds onto a file for an endpoint pattern. */
  public void registerBinaryFile(String pattern, String filepath) {
    registerFile(pattern, filepath, BINARY_CONTENT_TYPE, /*eTagOptional=*/ Optional.absent());
  }

  /**
   * Registers a handler that binds onto a proto file for an endpoint pattern with the specified
   * ETag.
   */
  public void registerProtoFileWithETag(String pattern, String filepath, String eTag) {
    registerFile(pattern, filepath, PROTO_CONTENT_TYPE, Optional.of(eTag));
  }

  private void registerFile(
      String pattern, String filepath, String contentType, Optional<String> eTagOptional) {
    registerHandler(
        pattern,
        (httpRequest, httpResponse, httpContext) -> {
          if (eTagOptional.isPresent()) {
            String eTag = eTagOptional.get();
            httpResponse.addHeader(ETAG_HEADER, eTag);
            setHttpStatusCode(httpRequest, httpResponse, eTag);
            if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_NOT_MODIFIED
                || HEAD_REQUEST_METHOD.equals(httpRequest.getRequestLine().getMethod())) {
              return;
            }
          } else { // The ETag is not present.
            httpResponse.setStatusCode(HttpStatus.SC_OK);
          }
          File file = new File(filepath);
          httpResponse.setEntity(new FileEntity(file, contentType));
        });
  }

  /** Starts the test http server and returns the prefix of the test url. */
  public Uri.Builder startServer() throws IOException {
    serverSocket =
        new ServerSocket(
            /*port=*/ userDesignatedPort, /*backlog=*/ 0, InetAddress.getByName(TEST_HOST));
    serverThread =
        new Thread(
            () -> {
              try {
                while (!finished.get()) {
                  Socket socket = serverSocket.accept();
                  handleRequest(socket);
                }
              } catch (IOException e) {
                Log.e(TAG, "Exception: " + e);
              }
            });
    serverThread.start();
    return getTestUrlPrefix();
  }

  public void stopServer() {
    try {
      finished.set(true);
      serverSocket.close();
      serverThread.join();
    } catch (IOException | InterruptedException e) {
      Log.e(TAG, "Exception when stopping server: " + e);
    }
  }

  private void handleRequest(Socket socket) {
    DefaultHttpServerConnection connection = new DefaultHttpServerConnection();
    try {
      connection.bind(socket, httpParams);
      HttpContext httpContext = new BasicHttpContext();
      httpService.handleRequest(connection, httpContext);
    } catch (IOException | HttpException e) {
      Log.e(TAG, "Unexpected exception while processing request " + e);
    } finally {
      try {
        connection.shutdown();
      } catch (IOException e) {
        // Ignore.
      }
    }
  }

  private Uri.Builder getTestUrlPrefix() {
    String authority = TEST_HOST + ":" + serverSocket.getLocalPort();
    return new Uri.Builder().scheme("http").encodedAuthority(authority);
  }

  private static void setHttpStatusCode(
      HttpRequest httpRequest, HttpResponse httpResponse, String eTag) {
    Header[] headers = httpRequest.getAllHeaders();
    // We use `If-None-Match` header and ETag to detect whether the file has been changed since the
    // last sync. If the ETag from client matches the one at server, the file is not changed and
    // HttpStatus.SC_NOT_MODIFIED is returned; otherwise, the file is changed and HttpStatus.SC_OK
    // is returned.
    for (Header header : headers) {
      // Find the `If-None-Match` header.
      if (!IF_NONE_MATCH_HEADER.equals(header.getName())) {
        continue;
      }
      httpResponse.setStatusCode(
          eTag.equals(header.getValue()) ? HttpStatus.SC_NOT_MODIFIED : HttpStatus.SC_OK);
      return;
    }
    httpResponse.setStatusCode(HttpStatus.SC_OK);
  }
}
