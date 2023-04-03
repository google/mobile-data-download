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

package_group(
    name = "visibility_group",
    packages = [
        "//java/com/google/android/apps/search/assistant/verticals/ambient/places/hammerdb/testing/...",
        "//java/com/google/android/apps/tycho/common/download/largefile/testing/...",
        "//java/com/google/android/libraries/lens/view/download/...",
        "//java/com/google/android/libraries/translate/...",
        "//javatests/com/google/android/apps/gsa/shared/speech/hotword/...",
        "//javatests/com/google/android/apps/gsa/staticplugins/mdd/...",
        "//javatests/com/google/android/apps/inputmethod/...",
        "//javatests/com/google/android/apps/search/assistant/platform/ondevice/datadownload/...",
        "//javatests/com/google/android/apps/search/assistant/surfaces/voice/initialdownload/...",
        "//javatests/com/google/android/apps/search/assistant/verticals/ambient/places/hammerdb/...",
        "//javatests/com/google/android/apps/search/assistant/verticals/ambient/places/shared/...",
        "//javatests/com/google/android/apps/search/assistant/verticals/ambient/places/slices/...",
        "//javatests/com/google/android/apps/search/fedora/...",
        "//javatests/com/google/android/apps/translate/...",
        "//javatests/com/google/android/apps/turbo/...",
        "//javatests/com/google/android/apps/tycho/common/download/largefile/...",
        "//javatests/com/google/android/apps/youtube/app/common/devicecapabilities/...",
        "//javatests/com/google/android/gmscore/integ/modules/userprofile/...",
        "//javatests/com/google/android/libraries/assistant/...",
        "//javatests/com/google/android/libraries/compose/...",
        "//javatests/com/google/android/libraries/inputmethod/...",
        "//javatests/com/google/android/libraries/lens/view/...",
        "//javatests/com/google/android/libraries/lens/view/download/...",
        "//javatests/com/google/android/libraries/mobiledatadownload/file/...",
        "//javatests/com/google/android/libraries/platformcommunications/expressiondata/...",
        "//javatests/com/google/android/libraries/search/integrations/mdd/...",
        "//javatests/com/google/android/libraries/search/soda/resourcemanager/...",
        "//javatests/com/google/android/libraries/speech/modeldownload/contextual/...",
        "//javatests/com/google/android/libraries/translate/...",
        "//javatests/com/google/android/libraries/youtube/innertube/datapush/...",
        "//javatests/com/google/android/libraries/youtube/studio/commands/...",
        "//third_party/java_src/android_app/bugle/shared/java/com/google/android/apps/messaging/shared/mdd/testing",
        "//third_party/java_src/android_app/bugle/tests/robolectric/javatests/com/google/android/apps/messaging/shared/mdd/...",
        "//third_party/java_src/android_app/dialer/java/com/android/dialer/mobiledatadownload/testing",
    ],
)

android_library(
    name = "BlockingFileDownloader",
    testonly = 1,
    srcs = [
        "BlockingFileDownloader.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "@com_google_guava_guava",
        "@flogger",
    ],
)

android_library(
    name = "FakeLogger",
    testonly = 1,
    srcs = ["FakeLogger.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Logger",
        "@com_google_guava_guava",
        "@com_google_protobuf//:protobuf_lite",
    ],
)

android_library(
    name = "FakeTimeSource",
    testonly = 1,
    srcs = ["FakeTimeSource.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "@com_google_errorprone_error_prone_annotations",
    ],
)

android_library(
    name = "LocalFileDownloader",
    testonly = 1,
    srcs = ["LocalFileDownloader.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "MddNotificationCapture",
    testonly = 1,
    srcs = ["MddNotificationCapture.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload/foreground:NotificationUtil",
        "@com_google_android_testing//:util",
        "@com_google_code_findbugs_jsr305",
        "@com_google_guava_guava",
        "@truth",
    ],
)

android_library(
    name = "RobolectricFileDownloader",
    testonly = 1,
    srcs = ["RobolectricFileDownloader.java"],
    deps = [
        ":LocalFileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "@com_google_guava_guava",
        "@com_google_runfiles",
    ],
)

android_library(
    name = "TestFlags",
    testonly = 1,
    srcs = ["TestFlags.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "TestFileDownloader",
    testonly = 1,
    srcs = ["TestFileDownloader.java"],
    deps = [
        ":LocalFileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:file",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LogUtil",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "TestHttpServer",
    testonly = 1,
    srcs = ["TestHttpServer.java"],
    deps = [
        "@android_sdk_linux",
        "@com_google_errorprone_error_prone_annotations",
        "@com_google_guava_guava",
    ],
)

android_library(
    name = "MddTestDependencies",
    testonly = 1,
    srcs = ["MddTestDependencies.java"],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload:Flags",
        "//java/com/google/android/libraries/mobiledatadownload:TimeSource",
        "//java/com/google/android/libraries/mobiledatadownload/downloader:FileDownloader",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad/dagger/downloader2:base",
        "//java/com/google/android/libraries/mobiledatadownload/downloader/offroad/dagger/downloader2:base_deps",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/integration/downloader:downloader2_sp",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:LoggingStateStore",
        "//java/com/google/android/libraries/mobiledatadownload/internal/logging:SharedPreferencesLoggingState",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/monitor:DownloadProgressMonitor",
        "@com_google_guava_guava",
        "@cronet-api",
    ],
)

android_library(
    name = "FakeMobileDataDownload",
    testonly = 1,
    srcs = [
        "FakeMobileDataDownload.java",
    ],
    deps = [
        "//java/com/google/android/libraries/mobiledatadownload",
        "//java/com/google/android/libraries/mobiledatadownload:DownloadException",
        "//java/com/google/android/libraries/mobiledatadownload:UsageEvent",
        "//java/com/google/android/libraries/mobiledatadownload/account:AccountUtil",
        "//java/com/google/android/libraries/mobiledatadownload/file",
        "//java/com/google/android/libraries/mobiledatadownload/file/backends:android",
        "//java/com/google/android/libraries/mobiledatadownload/file/openers:stream",
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_java_proto_lite",
        "//java/com/google/android/libraries/mobiledatadownload/tracing:concurrent",
        "//proto:client_config_java_proto_lite",
        "//proto:download_config_java_proto_lite",
        "@androidx_test",
        "@com_google_code_findbugs_jsr305",
        "@com_google_dagger",
        "@com_google_guava_guava",
        "@flogger",
        "@javax_inject",
    ],
)

exports_files(["AndroidManifest.xml"])
