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
load("@build_bazel_rules_android//android:rules.bzl", "android_library", "android_local_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

android_local_test(
    name = "BufferTransformTest",
    size = "small",
    srcs = ["BufferTransformTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:buffer",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

android_local_test(
    name = "CompressTransformTest",
    size = "small",
    srcs = ["CompressTransformTest.java"],
    data = [
        "//javatests/com/google/android/libraries/mobiledatadownload/file/transforms/testdata:golden.deflate",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/samples",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:compress",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
        "@com_google_runfiles",
        "@truth",
    ],
)

android_local_test(
    name = "IntegrityTransformTest",
    size = "small",
    srcs = ["IntegrityTransformTest.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:compute_uri",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:integrity",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_local_test(
    name = "TransformProtosTest",
    size = "small",
    srcs = ["TransformProtosTest.java"],
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    deps = [
        ":android_library_proto_data",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//proto:transform_java_proto_lite",
        "@androidx_test",
        "@com_google_guava_guava",
        "@com_google_protobuf//:parsers",
        "@truth",
    ],
)

android_library(
    name = "android_library_proto_data",
    manifest = "//javatests/com/google/android/libraries/mobiledatadownload/file:AndroidManifest.xml",
    resource_files = ["//javatests/com/google/android/libraries/mobiledatadownload/file/transforms/testdata:transforms.pb"],
)
