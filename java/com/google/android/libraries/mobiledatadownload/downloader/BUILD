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
    name = "FileDownloader",
    srcs = [
        "CheckContentChangeRequest.java",
        "CheckContentChangeResponse.java",
        "DownloadConstraints.java",
        "DownloadRequest.java",
        "FileDownloader.java",
        "InlineDownloadParams.java",
        "MultiSchemeFileDownloader.java",
        "OAuthTokenProvider.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:FileSource",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddConstants",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@androidx_annotation_annotation",
        "@com_google_auto_value",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "Constants",
    srcs = ["Constants.java"],
)
