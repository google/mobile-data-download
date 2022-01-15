package(
    default_visibility = ["//visibility:public"],
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

java_lite_proto_library(
    name = "download_config_java_proto_lite",
    deps = [":download_config_proto"],
)

proto_library(
    name = "transform_proto",
    srcs = ["transform.proto"],
    cc_api_version = 2,
)

java_lite_proto_library(
    name = "transform_java_proto_lite",
    deps = [":transform_proto"],
)
