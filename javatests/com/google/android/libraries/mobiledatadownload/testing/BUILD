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
load("@build_bazel_rules_android//android:rules.bzl", "android_library")

package(
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_library(
    name = "BlockingFileDownloader",
    testonly = 1,
    srcs = [
        "BlockingFileDownloader.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "@com_google_guava_guava",
        "@flogger",
    ],
)

android_library(
    name = "FakeLogger",
    testonly = 1,
    srcs = ["FakeLogger.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Logger",
        "@com_google_guava_guava",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "FakeTimeSource",
    testonly = 1,
    srcs = ["FakeTimeSource.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
    ],
)

android_library(
    name = "LocalFileDownloader",
    testonly = 1,
    srcs = ["LocalFileDownloader.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "MddNotificationCapture",
    testonly = 1,
    srcs = ["MddNotificationCapture.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/foreground:NotificationUtil",
        "@com_google_android_testing//:util",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_library(
    name = "RobolectricFileDownloader",
    testonly = 1,
    srcs = ["RobolectricFileDownloader.java"],
    deps = [
        ":LocalFileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "@com_google_guava_guava",
        "@com_google_runfiles",
    ],
)

android_library(
    name = "TestFlags",
    testonly = 1,
    srcs = ["TestFlags.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "TestFileDownloader",
    testonly = 1,
    srcs = ["TestFileDownloader.java"],
    deps = [
        ":LocalFileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "TestHttpServer",
    testonly = 1,
    srcs = ["TestHttpServer.java"],
    deps = [
        "@android_sdk_linux",
        "@com_google_guava_guava",
    ],
)

exports_files(["AndroidManifest.xml"])
