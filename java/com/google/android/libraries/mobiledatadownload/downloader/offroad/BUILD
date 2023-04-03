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
    default_applicable_licenses = ["//:license"],
    default_visibility = [
        "//visibility:public",
    ],
    licenses = ["notice"],
)

android_library(
    name = "Offroad2FileDownloader",
    srcs = [
        "Offroad2FileDownloader.java",
    ],
    deps = [
        ":ExceptionHandler",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/integration/downloader:downloader2",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@androidx_concurrent_concurrent",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
        "@downloader",
    ],
)

android_library(
    name = "ExceptionHandler",
    srcs = [
        "ExceptionHandler.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "@downloader",
    ],
)

android_library(
    name = "TrafficStatsSocketFactory",
    srcs = ["TrafficStatsSocketFactory.java"],
)

android_library(
    name = "ThrottlingExecutor",
    srcs = [
        "ThrottlingExecutor.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)
