# Copyright 2022 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
load("//javatests/com/google/android/libraries/mobiledatadownload:test_defs.bzl", "mdd_local_test")

package(
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

mdd_local_test(
    name = "DownloadProgressMonitorTest",
    srcs = ["DownloadProgressMonitorTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.lite.DownloadProgressMonitorTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadListener",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadProgressMonitor",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:FakeTimeSource",
        "@android_sdk_linux",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

mdd_local_test(
    name = "DownloaderImplTest",
    srcs = ["DownloaderImplTest.java"],
    manifest = "AndroidManifest.xml",
    test_class = "com.google.android.libraries.mobiledatadownload.lite.DownloaderImplTest",
    runtime_deps = ["//third_party/java/robolectric:multidex"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/lite",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadListener",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadProgressMonitor",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:BlockingFileDownloader",
        "@android_sdk_linux",
        "@androidx_test",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)
