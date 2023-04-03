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
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

mdd_local_test(
    name = "NetworkUsageMonitorTest",
    srcs = ["NetworkUsageMonitorTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.monitor.NetworkUsageMonitorTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:SharedPreferencesLoggingState",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:NetworkUsageMonitor",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:FakeTimeSource",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:MddTestDependencies",
        "@android_sdk_linux",
        "@com_google_guava_guava",
        "@robolectric",
        "@truth",
    ],
)

mdd_local_test(
    name = "DownloadProgressMonitorTest",
    srcs = ["DownloadProgressMonitorTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.monitor.DownloadProgressMonitorTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadListener",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadListener",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:FakeTimeSource",
        "@android_sdk_linux",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)
