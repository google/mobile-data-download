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

# Most clients should depend on this target. It ensures that the "standard" schemes
# are available. Care will be taken to keep the size small. However, if a client wants
# even more granular control of dependencies, it can depend on a narrower build targets below.
android_library(
    name = "backends",
    exports = [
        ":android",
        ":file",
        ":file_descriptor",
    ],
)

android_library(
    name = "android",
    srcs = [
        "AndroidFileBackend.java",
        "AndroidUri.java",
    ],
    deps = [
        ":account_manager",
        ":account_serialization",
        ":android_adapter",
        ":android_file_environment",
        ":file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:preconditions",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//proto:transform_java_proto_lite",
        "@androidx_annotation_annotation",
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "blob_uri",
    srcs = [
        "BlobUri.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

# It needs to be built against a stable android SDK, e.g. --android_sdk=//third_party/java/android/android_sdk_linux/platforms/stable:android_sdk_tools.
android_library(
    name = "blobstore_backend",
    srcs = [
        "BlobStoreBackend.java",
    ],
    deps = [
        ":blob_uri",
        ":file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "content_resolver",
    srcs = [
        "ContentResolverBackend.java",
    ],
    deps = [
        ":file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:preconditions",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_errorprone_error_prone_annotations",
    ],
)

android_library(
    name = "file_descriptor",
    srcs = [
        "FileDescriptorUri.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        # NOTE: dependency of gmscore client lib <internal>
    ],
)

android_library(
    name = "file",
    srcs = [
        "FileUri.java",
        "JavaFileBackend.java",
    ],
    deps = [
        ":file_adapter",
        ":file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:backend_stream",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//proto:transform_java_proto_lite",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "memory",
    srcs = [
        "MemoryBackend.java",
        "MemoryUri.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//proto:transform_java_proto_lite",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "asset",
    srcs = [
        "AssetFileBackend.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "android_adapter",
    srcs = [
        "AndroidUri.java",
        "AndroidUriAdapter.java",
        "UriAdapter.java",
    ],
    visibility = ["//:__subpackages__"],
    deps = [
        ":account_manager",
        ":account_serialization",
        ":android_file_environment",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:lite_transform_fragments",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:preconditions",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto",
        "//proto:transform_java_proto_lite",
        "@com_google_code_findbugs_jsr305",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "generic_adapter",
    srcs = [
        "GenericUriAdapter.java",
        "UriAdapter.java",
    ],
    visibility = ["//:__subpackages__"],
    deps = [
        ":android_adapter",
        ":file",
        ":file_adapter",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
    ],
)

android_library(
    name = "file_adapter",
    srcs = [
        "FileUriAdapter.java",
        "UriAdapter.java",
    ],
    visibility = ["//:__subpackages__"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
    ],
)

# Shared library code restricted to the internal package group
android_library(
    name = "account_serialization",
    srcs = [
        "AccountSerialization.java",
    ],
    visibility = ["//:__subpackages__"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:preconditions",
        "@com_google_errorprone_error_prone_annotations",
        # NOTE: dependency of gmscore client lib <internal>
    ],
)

android_library(
    name = "account_manager",
    srcs = [
        "AccountManager.java",
    ],
    deps = [
        "@com_google_guava_guava",
    ],
)

# Shared library code restricted to the internal package group
android_library(
    name = "android_file_environment",
    srcs = ["AndroidFileEnvironment.java"],
    visibility = ["//:__subpackages__"],
)

android_library(
    name = "uri_normalizer",
    srcs = [
        "UriNormalizer.java",
    ],
    deps = [
        "@com_google_guava_guava",
    ],
)
