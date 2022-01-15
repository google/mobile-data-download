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

# MDD Lite visibility is restricted to the following set of packages. Any
# new clients must be added to this list in order to grant build visibility.
package(
    default_visibility = [
        "//visibility:public",
    ],
    licenses = ["notice"],
)

android_library(
    name = "lite",
    srcs = glob(
        ["*.java"],
        exclude = [
            "DownloadListener.java",
            "DownloadProgressMonitor.java",
            "SingleFileDownloadProgressMonitor.java",
        ],
    ),
    deps = [
        ":DownloadListener",
        ":DownloadProgressMonitor",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/foreground:NotificationUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@androidx_core_core",
        "@com_google_auto_value",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@org_checkerframework_qual",
    ],
)

android_library(
    name = "DownloadListener",
    srcs = ["DownloadListener.java"],
    deps = [
        "//proto:client_config_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "DownloadProgressMonitor",
    srcs = [
        "DownloadProgressMonitor.java",
        "SingleFileDownloadProgressMonitor.java",
    ],
    deps = [
        ":DownloadListener",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)
