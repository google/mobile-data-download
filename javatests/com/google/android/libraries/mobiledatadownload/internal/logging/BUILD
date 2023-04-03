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
load("//javatests/com/google/android/libraries/mobiledatadownload:test_defs.bzl", "mdd_local_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

mdd_local_test(
    name = "FileGroupStatsLoggerTest",
    srcs = ["FileGroupStatsLoggerTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.internal.logging.FileGroupStatsLoggerTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal:FileGroupManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal:FileGroupsMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal/collect",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:FileGroupStatsLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:FileGroupUtil",
        "//javatests/com/google/android/libraries/mobiledatadownload/internal:MddTestUtil",
        "//proto:log_enums_java_proto_lite",
        "//proto:logs_java_proto_lite",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

mdd_local_test(
    name = "StorageLoggerTest",
    srcs = ["StorageLoggerTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.internal.logging.StorageLoggerTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:SilentFeedback",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/spi",
        "//java/com/google/android/libraries/mobiledatadownload/internal:FileGroupsMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddConstants",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedFileManager",
        "//java/com/google/android/libraries/mobiledatadownload/internal:SharedFilesMetadata",
        "//java/com/google/android/libraries/mobiledatadownload/internal/collect",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:StorageLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:DirectoryUtil",
        "//javatests/com/google/android/libraries/mobiledatadownload/internal:MddTestUtil",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:TestFlags",
        "//proto:log_enums_java_proto_lite",
        "//proto:logs_java_proto_lite",
        "@com_google_auto_value",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

mdd_local_test(
    name = "NetworkLoggerTest",
    srcs = ["NetworkLoggerTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.internal.logging.NetworkLoggerTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:EventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:NetworkLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:SharedPreferencesLoggingState",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:FakeTimeSource",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:MddTestDependencies",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:TestFlags",
        "//proto:logs_java_proto_lite",
        "@androidx_test",
        "@com_google_guava_guava",
        "@mockito",
        "@truth",
    ],
)

mdd_local_test(
    name = "MddEventLoggerTest",
    srcs = ["MddEventLoggerTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.internal.logging.MddEventLoggerTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:Logger",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogSampler",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:MddEventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:SharedPreferencesLoggingState",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:FakeTimeSource",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:MddTestDependencies",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:TestFlags",
        "//proto:log_enums_java_proto_lite",
        "//proto:logs_java_proto_lite",
        "@com_google_guava_guava",
        "@mockito",
        "@robolectric",
    ],
)

mdd_local_test(
    name = "LoggingStateStoreTest",
    srcs = ["LoggingStateStoreTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.internal.logging.LoggingStateStoreTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing:fake_file_backend",
        "//java/com/google/android/libraries/mobiledatadownload/internal:MddConstants",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:SharedPreferencesLoggingState",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/internal/util:SharedPreferencesUtil",
        "//java/com/google/protobuf/util:time_lite",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:FakeTimeSource",
        "@androidx_test",
        "@com_google_guava_guava",
        "@truth",
    ],
)

mdd_local_test(
    name = "LogSamplerTest",
    srcs = ["LogSamplerTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.internal.logging.LogSamplerTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/file/common/testing",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogSampler",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:SharedPreferencesLoggingState",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:FakeTimeSource",
        "//javatests/com/google/android/libraries/mobiledatadownload/testing:TestFlags",
        "//proto:logs_java_proto_lite",
        "@androidx_test",
        "@com_google_guava_guava",
        "@truth",
    ],
)

mdd_local_test(
    name = "DownloadStateLoggerTest",
    srcs = ["DownloadStateLoggerTest.java"],
    test_class = "com.google.android.libraries.mobiledatadownload.internal.logging.DownloadStateLoggerTest",
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:DownloadStateLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging/testing:FakeEventLogger",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//proto:log_enums_java_proto_lite",
        "//proto:logs_java_proto_lite",
        "//third_party/java/junit",
        "@androidx_test",
        "@com_google_guava_guava",
        "@robolectric",
        "@truth",
    ],
)
