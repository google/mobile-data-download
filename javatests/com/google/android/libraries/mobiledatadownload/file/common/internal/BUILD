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
load("@build_bazel_rules_android//android:rules.bzl", "android_local_test")

package(
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_local_test(
    name = "BackendInputStreamTest",
    size = "small",
    srcs = ["BackendInputStreamTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:backend_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "@truth",
    ],
)

android_local_test(
    name = "BackendOutputStreamTest",
    size = "small",
    srcs = ["BackendOutputStreamTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:backend_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "@truth",
    ],
)

android_local_test(
    name = "LazyByteArrayInputStreamTest",
    size = "small",
    srcs = ["LazyByteArrayInputStreamTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lazy_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "@truth",
    ],
)

android_local_test(
    name = "LiteTransformFragmentsTest",
    size = "small",
    srcs = ["LiteTransformFragmentsTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common:fragment",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "@com_google_guava_guava",
        "@truth",
    ],
)
