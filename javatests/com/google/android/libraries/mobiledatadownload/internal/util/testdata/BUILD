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
load("//tools/build_rules/text_to_binary:def.bzl", "proto_data")

package(
    default_visibility = ["//:__subpackages__"],
    licenses = ["notice"],
)

filegroup(
    name = "raw_group",
    testonly = 1,
    srcs = [
        "raw_group_with_extension",
    ],
)

proto_data(
    name = "group.pb",
    src = "group.pb.txt",
    out = "res/raw/group_data_pb",
    proto_deps = [
        "//proto:download_config_proto",
    ],
    proto_name = "mdi.download.DataFileGroup",
)

proto_data(
    name = "group_internal.pb",
    src = "group_internal.pb.txt",
    out = "res/raw/group_internal_data_pb",
    proto_deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_proto",
    ],
    proto_name = "mdi.download.internal.DataFileGroupInternal",
)

proto_data(
    name = "group_optional_unset.pb",
    src = "group_optional_unset.pb.txt",
    out = "res/raw/group_optional_unset_data_pb",
    proto_deps = [
        "//proto:download_config_proto",
    ],
    proto_name = "mdi.download.DataFileGroup",
)

proto_data(
    name = "group_internal_optional_unset.pb",
    src = "group_internal_optional_unset.pb.txt",
    out = "res/raw/group_internal_optional_unset_data_pb",
    proto_deps = [
        "//java/com/google/android/libraries/mobiledatadownload/internal/proto:metadata_proto",
    ],
    proto_name = "mdi.download.internal.DataFileGroupInternal",
)
