load("//third_party/bazel_rules/rules_java/java:defs.bzl", "java_proto_library")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = [
        "//visibility:public",
    ],
    licenses = ["notice"],
)

proto_library(
    name = "client_config_proto",
    srcs = ["client_config.proto"],
    cc_api_version = 2,
    deps = [
        "@com_google_protobuf//:any_proto",
    ],
)

java_lite_proto_library(
    name = "client_config_java_proto_lite",
    deps = [":client_config_proto"],
)

java_proto_library(
    name = "client_config_java_proto",
    deps = [":client_config_proto"],
)

proto_library(
    name = "download_config_proto",
    srcs = ["download_config.proto"],
    cc_api_version = 2,
    deps = [
        ":transform_proto",
        "@com_google_protobuf//:any_proto",
    ],
    alwayslink = 1,
)

kt_jvm_lite_proto_library(
    name = "download_config_kt_proto_lite",
    deps = [":download_config_proto"],
)

java_lite_proto_library(
    name = "download_config_java_proto_lite",
    deps = [":download_config_proto"],
)

java_proto_library(
    name = "download_config_java_proto",
    deps = [":download_config_proto"],
)

proto_library(
    name = "transform_proto",
    srcs = ["transform.proto"],
    cc_api_version = 2,
)

java_proto_library(
    name = "transform_java_proto",
    deps = [":transform_proto"],
)

java_lite_proto_library(
    name = "transform_java_proto_lite",
    deps = [":transform_proto"],
)

proto_library(
    name = "logs_proto",
    srcs = ["logs.proto"],
    cc_api_version = 2,
    deps = [
        ":log_enums_proto",
    ],
)

java_lite_proto_library(
    name = "logs_java_proto_lite",
    deps = [":logs_proto"],
)

proto_library(
    name = "log_enums_proto",
    srcs = ["log_enums.proto"],
    cc_api_version = 2,
)

java_lite_proto_library(
    name = "log_enums_java_proto_lite",
    deps = [":log_enums_proto"],
)

proto_library(
    name = "atoms_proto",
    srcs = ["atoms.proto"],
    cc_api_version = 2,
)

java_lite_proto_library(
    name = "atoms_java_proto_lite",
    deps = [":atoms_proto"],
)
