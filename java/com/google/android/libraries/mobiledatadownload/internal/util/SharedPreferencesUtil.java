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
package com.google.android.libraries.mobiledatadownload.internal.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.google.common.base.Optional;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Simple util to read/write protos from/to {@link SharedPreferences}.
 *
 * <p>Protos are serialized, and the binary value is base-64 encoded without padding or wrapping.
 */
@CheckReturnValue
public class SharedPreferencesUtil {

  /**
   * Reads the shared pref value corresponding to the specified key as a lite proto of type 'T'. The
   * read value is populated to 'protoValue' which should already be constructed by the caller.
   *
   * @return the proto or null if no such element was found or could not be parsed.
   */
  @Nullable
  public static <K extends MessageLite, V extends MessageLite> V readProto(
      SharedPreferences prefs, K liteKey, Parser<V> parser) {
    return readProto(prefs, serializeProto(liteKey), parser);
  }

  /**
   * Reads the shared pref value corresponding to the specified key as a lite proto of type 'T'. The
   * read value is populated to 'protoValue' which should already be constructed by the caller.
   *
   * @return the proto or null if no such element was found or could not be parse.
   */
  @Nullable
  public static <T extends MessageLite> T readProto(
      SharedPreferences prefs, String key, Parser<T> parser) {
    String encodedLiteString = prefs.getString(key, null);
    if (encodedLiteString == null) {
      return null;
    }
    try {
      return parseLiteFromEncodedString(encodedLiteString, parser);
    } catch (InvalidProtocolBufferException e) {
      return null;
    }
  }

  /**
   * Write and commit the serialized form of the proto into pref, corresponding to the give proto
   * key.
   */
  public static <T extends MessageLite> boolean writeProto(
      SharedPreferences prefs, final String key, final T protoValue) {
    SharedPreferences.Editor editor = prefs.edit();
    writeProto(editor, key, protoValue);
    return editor.commit();
  }

  /**
   * Write and commit the serialized form of the proto into pref, corresponding to the give proto
   * key.
   */
  public static <K extends MessageLite, T extends MessageLite> boolean writeProto(
      SharedPreferences prefs, final K protoKey, final T protoValue) {
    SharedPreferences.Editor editor = prefs.edit();
    writeProto(editor, protoKey, protoValue);
    return editor.commit();
  }

  /**
   * Write the serialized form of the proto into shared pref, corresponding to the given proto key.
   */
  public static <K extends MessageLite, T extends MessageLite> void writeProto(
      SharedPreferences.Editor editor, final K protoKey, final T protoValue) {
    writeProto(editor, serializeProto(protoKey), protoValue);
  }

  /**
   * Write the serialized form of the proto into shared pref, corresponding to the given string key.
   */
  public static <T extends MessageLite> void writeProto(
      SharedPreferences.Editor editor, final String key, final T protoValue) {
    editor.putString(key, serializeProto(protoValue));
  }

  /** Removes whatever value corresponds the protoKey from shared prefs. */
  public static <K extends MessageLite> boolean removeProto(
      SharedPreferences prefs, String protoKey) {
    return prefs.edit().remove(protoKey).commit();
  }

  /** Removes whatever value corresponds the protoKey from shared prefs. */
  public static <K extends MessageLite> void removeProto(
      SharedPreferences.Editor editor, final K protoKey) {
    editor.remove(serializeProto(protoKey));
  }

  /** Removes whatever value corresponds the protoKey from shared prefs. */
  public static void removeProto(SharedPreferences.Editor editor, String protoKey) {
    editor.remove(protoKey);
  }

  /** Converts a MessageLite to a string that can be used as a key in shared prefs. */
  public static String serializeProto(MessageLite lite) {
    byte[] byteValue = lite.toByteArray();
    return Base64.encodeToString(byteValue, Base64.NO_PADDING | Base64.NO_WRAP);
  }

  /**
   * Parses a MessageLite from the base64 encoded string.
   *
   * @return the proto.
   */
  public static <T extends MessageLite> T parseLiteFromEncodedString(
      String base64Encoded, Parser<T> parser) throws InvalidProtocolBufferException {
    byte[] byteValue;
    try {
      byteValue = Base64.decode(base64Encoded, Base64.NO_PADDING | Base64.NO_WRAP);
    } catch (IllegalArgumentException e) {
      throw new InvalidProtocolBufferException(
          "Unable to decode to byte array", new IOException(e));
    }

    // Cannot use generated registry here, because it may cause NPE to clients.
    // For more detail, see b/140135059.
    return parser.parseFrom(byteValue, ExtensionRegistryLite.getEmptyRegistry());
  }

  /** Returns the SharedPreferences name for {@code instanceId}. */
  // TODO(b/204094591): determine whether instanceId is ever actually null.
  public static String getSharedPreferencesName(String baseName, Optional<String> instanceId) {
    return instanceId != null && instanceId.isPresent() ? baseName + instanceId.get() : baseName;
  }

  /** Return the SharedPreferences for InstanceId */
  // TODO(b/204094591): determine whether instanceId is ever actually null.
  public static SharedPreferences getSharedPreferences(
      Context context, String baseName, Optional<String> instanceId) {
    return context.getSharedPreferences(
        getSharedPreferencesName(baseName, instanceId), Context.MODE_PRIVATE);
  }
}
