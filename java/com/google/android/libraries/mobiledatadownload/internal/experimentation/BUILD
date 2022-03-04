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
    name = "DownloadStageManager",
    srcs = ["DownloadStageManager.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "NoOpDownloadStageManager",
    srcs = ["NoOpDownloadStageManager.java"],
    deps = [
        ":DownloadStageManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)
