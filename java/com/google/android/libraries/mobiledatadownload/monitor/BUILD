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
    default_visibility = [
        "//visibility:public",
    ],
    licenses = ["notice"],
)

android_library(
    name = "NetworkUsageMonitor",
    srcs = ["NetworkUsageMonitor.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/file/monitors",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/tracing",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "DownloadProgressMonitor",
    srcs = ["DownloadProgressMonitor.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadListener",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/file/monitors",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadListener",
        "//java/com/google/android/libraries/mobiledatadownload/lite:DownloadProgressMonitor",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)
