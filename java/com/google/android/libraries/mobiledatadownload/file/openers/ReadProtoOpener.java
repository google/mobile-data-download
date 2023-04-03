/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.libraries.mobiledatadownload.file.openers;

import com.google.android.libraries.mobiledatadownload.file.OpenContext;
import com.google.android.libraries.mobiledatadownload.file.Opener;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.io.IOException;
import java.io.InputStream;

/**
 * Opener for reading Protocol Buffers from file. It is based on the Java Proto Lite implementation;
 * see <internal> for more information. Note that this opener reads the entire stream into a single
 * message, meaning that delimited files are not supported. If the byte contents of the Uri cannot
 * be parsed as a valid protocol message, throws {@link InvalidProtocolBufferException}.
 *
 * <p>Usage: <code>
 * MyProto proto = storage.open(uri, ReadProtoOpener.create(MyProto.parser()));
 * </code>
 *
 * <p>TODO(b/75909287): consider adding alternative implementation for multi-proto files
 */
public final class ReadProtoOpener<T extends MessageLite> implements Opener<T> {

  private final Parser<T> parser;
  private ExtensionRegistryLite registry = ExtensionRegistryLite.getEmptyRegistry();

  private ReadProtoOpener(Parser<T> parser) {
    this.parser = parser;
  }

  /** Creates a new opener instance that reads protos using the given {@code parser}. */
  public static <T extends MessageLite> ReadProtoOpener<T> create(Parser<T> parser) {
    return new ReadProtoOpener<T>(parser);
  }

  /**
   * Creates a new opener instance that reads protos using the parser of the given {@code message}.
   * This can be useful if the type of T is unavailable at compile time (i.e. it's a generic).
   */
  public static <T extends MessageLite> ReadProtoOpener<T> create(T message) {
    @SuppressWarnings("unchecked") // safe cast because getParserForType API must return a Parser<T>
    Parser<T> parser = (Parser<T>) message.getParserForType();
    return new ReadProtoOpener<T>(parser);
  }

  /** Adds an extension registry used while parsing the proto. */
  @CanIgnoreReturnValue
  public ReadProtoOpener<T> withExtensionRegistry(ExtensionRegistryLite registry) {
    this.registry = registry;
    return this;
  }

  @Override
  public T open(OpenContext openContext) throws IOException {
    try (InputStream in = ReadStreamOpener.create().open(openContext)) {
      return parser.parseFrom(in, registry);
    }
  }
}
