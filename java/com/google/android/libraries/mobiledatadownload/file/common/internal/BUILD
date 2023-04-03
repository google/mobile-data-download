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
    name = "charsets",
    srcs = [
        "Charsets.java",
    ],
)

android_library(
    name = "exceptions",
    srcs = [
        "Exceptions.java",
    ],
)

android_library(
    name = "preconditions",
    srcs = [
        "Preconditions.java",
    ],
    deps = [
        "@com_google_code_findbugs_jsr305",
    ],
)

android_library(
    name = "forwarding_stream",
    srcs = [
        "ForwardingInputStream.java",
        "ForwardingOutputStream.java",
    ],
)

android_library(
    name = "backend_stream",
    srcs = [
        "BackendInputStream.java",
        "BackendOutputStream.java",
    ],
    deps = [
        ":forwarding_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
    ],
)

android_library(
    name = "lazy_stream",
    srcs = [
        "LazyByteArrayInputStream.java",
    ],
)

android_library(
    name = "lite_transform_fragments",
    srcs = ["LiteTransformFragments.java"],
    deps = [
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "exponential_backoff_iterator",
    srcs = ["ExponentialBackoffIterator.java"],
    deps = ["@com_google_guava_guava"],
)
