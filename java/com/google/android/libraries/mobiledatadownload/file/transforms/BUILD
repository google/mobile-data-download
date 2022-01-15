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

# Most clients should depend on this target. It ensures that the "standard" transforms
# are available. Care will be taken to keep the size small. However, if a client wants
# even more granular control of dependencies, it can depend on a narrower build targets below.
android_library(
    name = "transforms",
    exports = [
        ":buffer",
        ":compress",
        ":integrity",
    ],
)

android_library(
    name = "integrity",
    srcs = ["IntegrityTransform.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common:fragment",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "buffer",
    srcs = ["BufferTransform.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common:fragment",  # TODO(b/132818747) Remove
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
    ],
)

android_library(
    name = "compress",
    srcs = ["CompressTransform.java"],
    deps = ["//java/com/google/android/libraries/mobiledatadownload/file/spi"],
)

android_library(
    name = "proto",
    srcs = ["TransformProtos.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:charsets",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "proto_fragments",
    srcs = ["TransformProtoFragments.java"],
    deps = [
        ":proto",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
    ],
)
