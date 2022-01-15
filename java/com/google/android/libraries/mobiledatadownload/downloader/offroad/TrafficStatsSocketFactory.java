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

import android.net.TrafficStats;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;

/** A custom SocketFactory that tags the traffic going through the socket created by it. */
// TODO(b/141362798): Make package-private when OkHttpFileDownloaderModule supports non-framework
// apps.
public final class TrafficStatsSocketFactory extends SocketFactory {

  private final SocketFactory delegate;
  private final int trafficTag;

  public TrafficStatsSocketFactory(SocketFactory delegate, int trafficTag) {
    this.delegate = delegate;
    this.trafficTag = trafficTag;
  }

  @Override
  public Socket createSocket() throws IOException {
    Socket socket = delegate.createSocket();
    TrafficStats.setThreadStatsTag(trafficTag);
    TrafficStats.tagSocket(socket);
    return socket;
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    Socket socket = delegate.createSocket(host, port);
    TrafficStats.setThreadStatsTag(trafficTag);
    TrafficStats.tagSocket(socket);
    return socket;
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    Socket socket = delegate.createSocket(host, port, localHost, localPort);
    TrafficStats.setThreadStatsTag(trafficTag);
    TrafficStats.tagSocket(socket);
    return socket;
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    Socket socket = delegate.createSocket(host, port);
    TrafficStats.setThreadStatsTag(trafficTag);
    TrafficStats.tagSocket(socket);
    return socket;
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    Socket socket = delegate.createSocket(address, port, localAddress, localPort);
    TrafficStats.setThreadStatsTag(trafficTag);
    TrafficStats.tagSocket(socket);
    return socket;
  }
}
