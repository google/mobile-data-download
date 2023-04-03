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
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_library(
    name = "samples",
    srcs = glob(
        ["*.java"],
        exclude = ["DemoActivity.java"],
    ),
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common:fragment",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:charsets",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@androidx_appcompat_appcompat",  # buildcleaner: keep
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)
