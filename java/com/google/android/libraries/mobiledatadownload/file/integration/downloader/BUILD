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
    name = "downloader2",
    srcs = [
        "DownloadDestinationOpener.java",
        "DownloadMetadataStore.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:random_access_file",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "@com_google_guava_guava",
        "@downloader",
    ],
)

android_library(
    name = "downloader2_sp",
    srcs = [
        "SharedPreferencesDownloadMetadata.java",
    ],
    deps = [
        ":downloader2",
        "@com_google_guava_guava",
        "@downloader",
    ],
)
