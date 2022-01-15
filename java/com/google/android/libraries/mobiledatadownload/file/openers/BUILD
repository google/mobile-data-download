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
    name = "asset_file_descriptor",
    srcs = ["AssetFileDescriptorOpener.java"],
    deps = [
        ":parcel_file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file",
    ],
)

android_library(
    name = "random_access_file",
    srcs = ["RandomAccessFileOpener.java"],
    deps = [
        ":file",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "closeable_uri",
    srcs = ["CloseableUri.java"],
)

# Requires API level 21+
android_library(
    name = "file",
    srcs = [
        "Pipes.java",
        "ReadFileOpener.java",
        "WriteFileOpener.java",
    ],
    deps = [
        ":stream",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@androidx_annotation_annotation",  # buildcleaner: keep
        "@com_google_code_findbugs_jsr305",
    ],
)

android_library(
    name = "stream_mutation",
    srcs = ["StreamMutationOpener.java"],
    deps = [
        ":lock_file",
        ":scratch",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "@com_google_code_findbugs_jsr305",
    ],
)

android_library(
    name = "native",
    srcs = [
        "NativeReadOpener.java",
    ],
    deps = [
        ":closeable_uri",
        "//java/com/google/android/libraries/mobiledatadownload/file",
    ],
)

android_library(
    name = "parcel_file_descriptor",
    srcs = ["ParcelFileDescriptorOpener.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file_descriptor",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
    ],
)

android_library(
    name = "proto",
    srcs = [
        "ReadProtoOpener.java",
        "WriteProtoOpener.java",
    ],
    deps = [
        ":scratch",
        ":stream",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "lock_file",
    srcs = [
        "LockFileOpener.java",
    ],
    deps = [
        ":random_access_file",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@com_google_code_findbugs_jsr305",
    ],
)

android_library(
    name = "recursive_delete",
    srcs = ["RecursiveDeleteOpener.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:exceptions",
    ],
)

android_library(
    name = "recursive_size",
    srcs = ["RecursiveSizeOpener.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "stream",
    srcs = [
        "AppendStreamOpener.java",
        "ReadStreamOpener.java",
        "WriteStreamOpener.java",
    ],
    deps = ["//java/com/google/android/libraries/mobiledatadownload/file"],
)

android_library(
    name = "integrity_uri_computer",
    srcs = ["IntegrityUriComputingOpener.java"],
    deps = [
        ":stream",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/behaviors:compute_uri",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:integrity",
        "//java/com/google/android/libraries/mobiledatadownload/file/transforms:proto_fragments",
        "//proto:transform_java_proto_lite",
        "@com_google_guava_guava",
    ],
)

# Requires API level 21+
android_library(
    name = "system_library",
    srcs = ["SystemLibraryOpener.java"],
    deps = [
        ":file",
        ":stream",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "string",
    srcs = [
        "ReadStringOpener.java",
        "WriteStringOpener.java",
    ],
    deps = [
        ":bytes",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/internal:charsets",
    ],
)

android_library(
    name = "bytes",
    srcs = [
        "ReadByteArrayOpener.java",
        "WriteByteArrayOpener.java",
    ],
    deps = [
        ":stream",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "memory_mapped_bytes",
    srcs = ["MappedByteBufferOpener.java"],
    deps = [
        ":stream",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common",
    ],
)

android_library(
    name = "scratch",
    srcs = ["ScratchFile.java"],
    visibility = ["//:__subpackages__"],
)
