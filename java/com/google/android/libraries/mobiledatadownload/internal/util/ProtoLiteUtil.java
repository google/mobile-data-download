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

import android.text.TextUtils;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.ExtensionRegistryLite;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/** Utils for moving Protobuf messages in and out of ByteBuffers. */
// LINT.IfChange
public class ProtoLiteUtil {
  public static final String TAG = "ProtoLiteUtil";

  /*
   * File format (with tail crc):
   * (
   *    int: number of bytes in message;
   *    byte[number of bytes in message]: bytes of message;
   * )...: message blocks;
   * long: CRC of the above data
   *
   * File format (without tail crc):
   * (
   *    int: number of bytes in message;
   *    byte[number of bytes in message]: bytes of message;
   *    long: the CRC of the bytes of the message above
   * )...: message blocks;
   */

  private static final byte INT_BYTE_SIZE = Integer.SIZE / Byte.SIZE;
  private static final byte LONG_BYTE_SIZE = Long.SIZE / Byte.SIZE;
  private static final byte CRC_LEN = LONG_BYTE_SIZE;

  // Used to help guess a good initial capacity for the ArrayList
  private static final int EXPECTED_MESS_SIZE = 1000;

  /**
   * @param buf MUST be a 0 based array buffer (aka, buf.arrayOffset() must return 0) and be mutable
   *     (aka, buf.isReadOnly() must return false)
   * @param messageType The type of the proto
   * @param tailCrc True if there is a single CRC at the end of the file for the whole content,
   *     false if there is a CRC after every record.
   * @return A list of proto messages read from the buffer, or null on failure.
   */
  @Nullable
  public static <T extends MessageLite> List<T> readFromBuffer(
      ByteBuffer buf, Class<T> messageType, Parser<T> messageParser, boolean tailCrc) {
    // assert buf.arrayOffset() == 0;
    // annoyingly, ByteBuffer#array() throws an exception if the ByteBuffer was readonly
    // assert !buf.isReadOnly();
    String typename = messageType.toString();

    if (tailCrc) {
      // Validate the tail CRC before reading any messages.
      int crcPos = buf.limit() - CRC_LEN;
      if (crcPos < 0) { // Equivalently, buf.limit() < CRC_LEN
        Log.e(TAG, "Protobuf data too short to be valid");
        return null;
      }
      // First off, check the crc
      long crc = buf.getLong(crcPos);
      // Position should still be at the beginning;
      // the read and write operations that take an index explicitly do not touch the
      // position
      if (!validateCRC(buf.array(), buf.arrayOffset(), crcPos, crc)) {
        Log.e(TAG, "Ignoring corrupt protobuf data");
        return null;
      }
      if (crcPos == 0) { // If the only thing in there was the CRC, then there are no messages
        return new ArrayList<T>(0);
      }
    }

    int end = tailCrc ? buf.limit() - CRC_LEN : buf.limit();

    List<T> toReturn = new ArrayList<T>((buf.limit() / EXPECTED_MESS_SIZE) + 1);
    while (buf.position() < end) {
      T dest;
      int bytesInMessage;
      try {
        bytesInMessage = buf.getInt();
      } catch (BufferUnderflowException ex) {
        handleBufferUnderflow(ex, typename);
        return null;
      }
      if (bytesInMessage < 0) {
        // This actually can happen even if the CRC check passed,
        // if the user gave the wrong MessageLite type.
        // Same goes for all of the other exceptions that can be thrown.
        Log.e(
            TAG,
            String.format(
                "Invalid message size: %d. May have given the wrong message type: %s",
                bytesInMessage, typename));
        return null;
      }

      if (!tailCrc) {
        // May have read a garbage size. Read carefully.
        if (end < buf.position() + bytesInMessage + CRC_LEN) {
          Log.e(
              TAG,
              String.format("Invalid message size: %d (buffer end is %d)", bytesInMessage, end));
          return toReturn;
        }
        long crc = buf.getLong(buf.position() + bytesInMessage);
        if (!validateCRC(buf.array(), buf.arrayOffset() + buf.position(), bytesInMessage, crc)) {
          // Return the valid messages we have read so far.
          return toReturn;
        }
      }

      // According to ByteBuffer#array()'s spec, this should not copy the backing array
      dest =
          tryCreate(
              buf.array(),
              buf.arrayOffset() + buf.position(),
              bytesInMessage,
              messageType,
              messageParser);
      if (dest == null) {
        // Something is seriously hosed at this point, return nothing.
        return null;
      }
      toReturn.add(dest);
      // Advance the buffer manually, since we read from it "raw" from the array above
      buf.position(buf.position() + bytesInMessage + (tailCrc ? 0 : CRC_LEN));
    }
    return toReturn;
  }

  @Nullable
  private static <T extends MessageLite> T tryCreate(
      byte[] arr, int pos, int len, Class<T> type, Parser<T> parser) {
    try {
      // Cannot use generated registry here, because it may cause NPE to clients.
      // For more detail, see b/140135059.
      return parser.parseFrom(arr, pos, len, ExtensionRegistryLite.getEmptyRegistry());
    } catch (InvalidProtocolBufferException ex) {
      Log.e(TAG, "Cannot deserialize message of type " + type, ex);
      return null;
    }
  }

  /**
   * Serializes the given MessageLite messages into a ByteBuffer, with either a CRC of the whole
   * content at the end of the buffer (tail CRC) or a CRC of every message at the end of the
   * message.
   *
   * @param coll The messages to write.
   * @param tailCrc true to use a tail CRC, false to put a CRC after every message.
   * @return A ByteBuffer containing the serialized messages.
   */
  @Nullable
  public static <T extends MessageLite> ByteBuffer dumpIntoBuffer(
      Iterable<T> coll, boolean tailCrc) {
    int count = 0;
    long toWriteOut = tailCrc ? CRC_LEN : 0;

    final int extraBytesPerMessage = tailCrc ? INT_BYTE_SIZE : INT_BYTE_SIZE + CRC_LEN;
    // First, get the size of how much will be written out
    // TODO find out if there is a adder util thingy I can use (could be parallel)
    for (MessageLite mess : coll) {
      toWriteOut += extraBytesPerMessage + mess.getSerializedSize();
      ++count;
    }
    if (count == 0) {
      // If there are no counters to write, don't even bother with the checksum.
      return ByteBuffer.allocate(0);
    }
    // Now we got this, make a ByteBuffer to hold all that we need to
    ByteBuffer buff = null;
    try {
      buff = ByteBuffer.allocate((int) toWriteOut);
    } catch (IllegalArgumentException ex) {
      Log.e(TAG, String.format("Too big to serialize, %s", prettyPrintBytes(toWriteOut)), ex);
      return null;
    }

    // According to ByteBuffer#array()'s spec, this should not copy the backing array
    byte[] arr = buff.array();
    // Also conveniently is where we need to write next
    int writtenSoFar = 0;
    // Now add in the serialized forms
    for (MessageLite mess : coll) {
      // As we called getSerializedSize above, this is assured to give us a non-bogus answer
      int bytesInMessage = mess.getSerializedSize();
      try {
        buff.putInt(bytesInMessage);
      } catch (BufferOverflowException ex) {
        handleBufferOverflow(ex);
        return null;
      }
      writtenSoFar += INT_BYTE_SIZE;
      // We are writing past the end of where buff is currently "looking at",
      // So reusing the backing array here should be fine.
      try {
        mess.writeTo(CodedOutputStream.newInstance(arr, writtenSoFar, bytesInMessage));
      } catch (IOException e) {
        Log.e(TAG, "Exception while writing to buffer.", e);
      }

      // Same as above, but reading past the end this time.
      try {
        buff.put(arr, writtenSoFar, bytesInMessage);
      } catch (BufferOverflowException ex) {
        handleBufferOverflow(ex);
        return null;
      }
      writtenSoFar += bytesInMessage;
      if (!tailCrc) {
        appendCRC(buff, arr, writtenSoFar - bytesInMessage, bytesInMessage);
        writtenSoFar += CRC_LEN;
      }
    }
    if (tailCrc) {
      try {
        appendCRC(buff, arr, 0, writtenSoFar);
      } catch (BufferOverflowException ex) {
        handleBufferOverflow(ex);
        return null;
      }
    }
    buff.rewind();
    return buff;
  }

  /** Return string from proto bytes when we know bytes are UTF-8. */
  public static String getDataString(byte[] data) {
    return new String(data, Charset.forName("UTF-8"));
  }

  /** Return null if input is empty (or null). */
  @Nullable
  public static String nullIfEmpty(String input) {
    return TextUtils.isEmpty(input) ? null : input;
  }

  /** Return null if input array is empty (or null). */
  @Nullable
  public static <T> T[] nullIfEmpty(T[] input) {
    return input == null || input.length == 0 ? null : input;
  }

  /** Similar to Objects.equal but available pre-kitkat. */
  public static boolean safeEqual(Object a, Object b) {
    return a == null ? b == null : a.equals(b);
  }

  /** Wraps MessageLite.toByteArray to check for null and return null if that's the case. */
  @Nullable
  public static final byte[] safeToByteArray(MessageLite msg) {
    return msg == null ? null : msg.toByteArray();
  }

  private static void handleBufferUnderflow(BufferUnderflowException ex, String typename) {
    Log.e(
        TAG,
        String.format("Buffer underflow. May have given the wrong message type: %s", typename),
        ex);
  }

  private static void handleBufferOverflow(BufferOverflowException ex) {
    Log.e(
        TAG,
        "Buffer underflow. A message may have an invalid serialized form"
            + " or has been concurrently modified.",
        ex);
  }

  /**
   * Reads the bytes given in an array and appends the CRC32 checksum to the ByteBuffer. The
   * location the CRC32 checksum will be written is the {@link ByteBuffer#position() current
   * position} in the ByteBuffer. The given ByteBuffer must have must have enough room (starting at
   * its position) to fit an additonal {@link #CRC_LEN} bytes.
   *
   * @param dest where to write the CRC32 checksum; must have enough room to fit an additonal {@link
   *     #CRC_LEN} bytes
   * @param src the array of bytes containing the data to checksum
   * @param off offset of where to start reading the array
   * @param len number of bytes to read in the array
   */
  private static void appendCRC(ByteBuffer dest, byte[] src, int off, int len) {
    CRC32 crc = new CRC32();
    crc.update(src, off, len);
    dest.putLong(crc.getValue());
  }

  private static boolean validateCRC(byte[] arr, int off, int len, long expectedCRC) {
    CRC32 crc = new CRC32();
    crc.update(arr, off, len);
    long computedCRC = crc.getValue();
    boolean matched = computedCRC == expectedCRC;
    if (!matched) {
      Log.e(
          TAG,
          String.format(
              "Corrupt protobuf data, expected CRC: %d computed CRC: %d",
              expectedCRC, computedCRC));
    }
    return matched;
  }

  private ProtoLiteUtil() {
    // No instantiation.
  }

  private static String prettyPrintBytes(long bytes) {
    if (bytes > 1024L * 1024 * 1024) {
      return String.format(Locale.US, "%.2fGB", (double) bytes / (1024L * 1024 * 1024));
    } else if (bytes > 1024 * 1024) {
      return String.format(Locale.US, "%.2fMB", (double) bytes / (1024 * 1024));
    } else if (bytes > 1024) {
      return String.format(Locale.US, "%.2fKB", (double) bytes / 1024);
    }
    return String.format(Locale.US, "%d Bytes", bytes);
  }
}
// LINT.ThenChange(<internal>)
