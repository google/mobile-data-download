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
    name = "base",
    srcs = ["BaseFileDownloaderModule.java"],
    deps = [
        ":base_deps",
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad:ExceptionHandler",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad:Offroad2FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad/dagger:BaseOffroadFileDownloaderModule",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/integration/downloader:downloader2",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@downloader",
        "@javax_inject",
    ],
)

android_library(
    name = "base_deps",
    srcs = ["BaseFileDownloaderDepsModule.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/annotations",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad:ExceptionHandler",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad/annotations:downloader2",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@downloader",
        "@javax_inject",
    ],
)
