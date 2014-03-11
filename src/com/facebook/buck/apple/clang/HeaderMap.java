/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.apple.clang;

import static java.lang.Math.max;

import com.google.common.base.Ascii;
import com.google.common.base.Preconditions;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Add support for reading and generating clang header maps
 *
 * Header maps are essentially hash maps from strings to paths (coded as two strings: a prefix and
 * a suffix).
 *
 * No spec is available but we conform to the reader class here:
 *     http://clang.llvm.org/doxygen/HeaderMap_8h_source.html
 *
 * Note: currently we don't support offsets greater than MAX_SIGNED_INT
 */
public class HeaderMap {

  // Hashtable buckets
  // Note: This notion of bucket is slightly more abstract than the one on disk
  // (string offsets being already swapped/shifted)
  private static class Bucket {
    final int key;        // Offset of the key string into stringBytes.
    final int prefix;     // Offset of the value prefix into stringBytes.
    final int suffix;     // Offset of the value suffix into stringBytes.

    Bucket(int key, int prefix, int suffix) {
      this.key = key;
      this.prefix = prefix;
      this.suffix = suffix;
    }
  }

  private static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

  private int numEntries;      // Number of entries in the string table.
  private int numBuckets;      // Number of buckets (always a power of 2).
  private int maxValueLength;  // Length of longest result path (excluding nul).

  // data containers
  private Bucket[] buckets;
  private byte[] stringBytes; // actually chars to make debugging easier
  private int stringBytesActualLength;

  // map to help share strings
  private Map<String, Integer> addedStrings;

  private HeaderMap(int numBuckets, int stringBytesLength) {
    Preconditions.checkState(numBuckets > 0, "The number of buckets must be greater than 0");
    Preconditions.checkState(stringBytesLength > 0,
        "The size of the string array must be greater than 0");
    Preconditions.checkState((numBuckets & (numBuckets - 1)) == 0,
        "The number of buckets must be a power of 2");

    this.numEntries = 0;
    this.numBuckets = numBuckets;
    this.maxValueLength = 0;

    this.buckets = new Bucket[numBuckets];
    this.stringBytes = new byte[stringBytesLength];
    this.stringBytesActualLength = 0;
    this.addedStrings = new HashMap<String, Integer>();
  }

  public int getNumEntries() {
    return numEntries;
  }

  public int getNumBuckets() {
    return numBuckets;
  }

  public int getMaxValueLength() {
    return maxValueLength;
  }

  public interface HeaderMapVisitor {
    void apply(String str, String prefix, String suffix);
  }

  public void visit(HeaderMapVisitor visitor) {
    for (int i = 0; i < numBuckets; i++) {
      Bucket bucket = buckets[i];
      if (bucket != null) {
        visitor.apply(
            getString(bucket.key),
            getString(bucket.prefix),
            getString(bucket.suffix)
        );
      }
    }
  }

  @Override
  public String toString() {
    final StringBuffer buffer = new StringBuffer();

    visit(new HeaderMapVisitor() {
      @Override
      public void apply(String str, String prefix, String suffix) {
        buffer.append("'" + str + "' ==> '" + prefix + "' + '" + suffix + "'\n");
      }
    });

    return buffer.toString();
  }

  @Nullable
  public String lookup(String str) {
    int hash0 = hashKey(str) & (numBuckets - 1);

    int hash = hash0;
    while (true) {
      Bucket bucket = buckets[hash];
      if (bucket == null) {
        return null;
      }
      if (str.equalsIgnoreCase(getString(bucket.key))) {
        return getString(bucket.prefix) + getString(bucket.suffix);
      }

      hash = (hash + 1) & (numBuckets - 1);
      if (hash == hash0) {
        return null;
      }
    }
  }

  // --------- I/O methods ----------

  private static final int HEADER_MAGIC = ('h' << 24) | ('m' << 16) | ('a' << 8) | 'p';
  private static final short HEADER_VERSION = 1;
  private static final short HEADER_RESERVED = 0;
  private static final int EMPTY_BUCKET_KEY = 0;

  private static final int HEADER_SIZE = 24;
  private static final int BUCKET_SIZE = 12;

  @Nullable
  public static HeaderMap deserialize(byte[] bytes) {
    HeaderMap hmap = new HeaderMap(1, 1);
    if (hmap.processBytes(bytes)) {
      return hmap;
    } else {
      return null;
    }
  }

  @Nullable
  public static HeaderMap deserialize(ByteBuffer buffer) {
    HeaderMap hmap = new HeaderMap(1, 1);
    if (hmap.processBuffer(buffer)) {
      return hmap;
    } else {
      return null;
    }
  }

  private boolean processBytes(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    if (buffer.getInt(0) != HEADER_MAGIC) {
      buffer = buffer.order(ByteOrder.LITTLE_ENDIAN);
    }
    return processBuffer(buffer);
  }

  private boolean processBuffer(ByteBuffer buffer) {
    int magic = buffer.getInt();
    short version = buffer.getShort();
    if (magic != HEADER_MAGIC || version != HEADER_VERSION) {
      return false;
    }

    /* reserved */ buffer.getShort();
    int stringOffset = buffer.getInt();
    numEntries = buffer.getInt();
    numBuckets = buffer.getInt();
    maxValueLength = buffer.getInt();

    // strings can be anywhere after the end of the buckets
    stringBytesActualLength = buffer.capacity() - HEADER_SIZE - numBuckets * BUCKET_SIZE;

    buckets = new Bucket[numBuckets];
    int actualOffset = HEADER_SIZE + numBuckets * BUCKET_SIZE - stringOffset;
    // actualOffset should always be positive since the index EMPTY_BUCKET_KEY=0 is reserved

    for (int i = 0; i < numBuckets; i++) {
      int keyRawOffset = buffer.getInt();
      int prefixRawOffset = buffer.getInt();
      int suffixRawOffset = buffer.getInt();

      if (keyRawOffset == EMPTY_BUCKET_KEY) {
        buckets[i] = null;
      } else {
        // we can subtract 1 value because index 0 is EMPTY_BUCKET_KEY
        buckets[i] = new Bucket(
            keyRawOffset - actualOffset,
            prefixRawOffset - actualOffset,
            suffixRawOffset - actualOffset
        );
      }
    }
    // anything else is string
    stringBytes = new byte[stringBytesActualLength];
    buffer.get(stringBytes);

    return true;
  }

  public int getRequiredBufferCapacity() {
    return HEADER_SIZE + numBuckets * BUCKET_SIZE + stringBytesActualLength;
  }

  public byte[] getBytes() {
    return getBytes(ByteOrder.BIG_ENDIAN);
  }

  public byte[] getBytes(ByteOrder bo) {
    byte[] bytes = new byte[getRequiredBufferCapacity()];
    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(bo);
    serialize(buffer);
    return bytes;
  }

  public void serialize(ByteBuffer buffer) {
    int actualOffset = 1; // must be greater than EMPTY_BUCKET_KEY

    buffer.putInt(HEADER_MAGIC);
    buffer.putShort(HEADER_VERSION);
    buffer.putShort(HEADER_RESERVED);
    buffer.putInt(HEADER_SIZE + numBuckets * BUCKET_SIZE - actualOffset);
    buffer.putInt(numEntries);
    buffer.putInt(numBuckets);
    buffer.putInt(maxValueLength);

    for (int i = 0; i < numBuckets; i++) {
      Bucket bucket = buckets[i];
      if (bucket == null) {
        buffer.putInt(EMPTY_BUCKET_KEY);
        buffer.putInt(0);
        buffer.putInt(0);
      } else {
        buffer.putInt(bucket.key + actualOffset);
        buffer.putInt(bucket.prefix + actualOffset);
        buffer.putInt(bucket.suffix + actualOffset);
      }
    }
    buffer.put(stringBytes, 0, stringBytesActualLength);
  }

  // -------------- Builder class ----------------

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    static final int DEFAULT_NUM_BUCKETS = 256;
    static final int DEFAULT_STRING_BYTES_LENGTH = 256;

    HeaderMap headerMap;

    public Builder() {
      this.headerMap = new HeaderMap(DEFAULT_NUM_BUCKETS, DEFAULT_STRING_BYTES_LENGTH);
    }

    public synchronized boolean add(String key, String prefix, String suffix) {
      AddResult result = headerMap.add(key, prefix, suffix);

      while (result == AddResult.FAILURE_FULL) {
        // the table is full, let's start all over again with a doubled number of bucket
        // and (optimization) the same size of string bytes
        final HeaderMap newHeaderMap = new HeaderMap(
            headerMap.numBuckets * 2,
            headerMap.stringBytes.length
        );
        headerMap.visit(
            new HeaderMapVisitor() {
              @Override
              public void apply(String str, String prefix, String suffix) {
                AddResult copying = newHeaderMap.add(str, prefix, suffix);
                assert (copying == AddResult.OK);
              }
            });
        headerMap = newHeaderMap;
        result = headerMap.add(key, prefix, suffix);
      }

      return (result == AddResult.OK);
    }

    // TODO(user): auto split value for some clever strategy??
    public boolean add(String key, String value) {
      return add(key, "", value);
    }

    public synchronized HeaderMap build() {
      return headerMap;
    }
  }

  // ------------- Internals -----------

  private static int hashKey(String str) {
    // ASCII lowercase is part of the format
    // UTF8 is the standard filesystem charset
    return hashKey(Ascii.toLowerCase(str).getBytes(DEFAULT_CHARSET));
  }

  private static int hashKey(byte[] str) {
    int key = 0;
    for (byte c : str) {
      key += c * 13;
    }
    return key;
  }

  private enum AddResult {
    OK,
    FAILURE_FULL,
    FAILURE_ALREADY_PRESENT
  };

  @SuppressWarnings("unused")
  private AddResult add(String str, String prefix, String suffix) {
    int hash0 = hashKey(str) & (numBuckets - 1);

    int hash = hash0;
    while (true) {
      Bucket bucket = buckets[hash];
      if (bucket == null) {
        bucket = new Bucket(
            addString(str),
            addString(prefix),
            addString(suffix)
        );
        buckets[hash] = bucket;
        numEntries++;
        maxValueLength = max(maxValueLength, prefix.length() + suffix.length());
        return AddResult.OK;
      }

      if (str.equalsIgnoreCase(getString(bucket.key))) {
        return AddResult.FAILURE_ALREADY_PRESENT;
      }

      hash = (hash + 1) & (numBuckets - 1);
      if (hash == hash0) {
        return AddResult.FAILURE_FULL;
      }
    }
  }

  @Nullable
  private String getString(int offset) {
    Preconditions.checkState(offset >= 0 && offset <= stringBytesActualLength);

    StringBuffer buffer = new StringBuffer();
    byte b = 0;
    while ((offset < stringBytesActualLength) && ((b = stringBytes[offset]) != 0)) {
      buffer.append((char) b);
      offset++;
    }

    if (offset == stringBytesActualLength) {
      // we reached the end of the array without finding a 0
      return null;
    }
    return buffer.toString();
  }

  private void putStringByte(byte b) {
    if (stringBytesActualLength == stringBytes.length) {
      byte[] newBytes = new byte[stringBytes.length * 2];
      System.arraycopy(stringBytes, 0, newBytes, 0, stringBytesActualLength);
      stringBytes = newBytes;
    }
    stringBytes[stringBytesActualLength] = b;
    stringBytesActualLength++;
  }

  private int addString(String str) {
    Integer existingOffset = addedStrings.get(str);
    if (existingOffset != null) {
      return existingOffset.intValue();
    }

    int offset = stringBytesActualLength;
    for (byte b : str.getBytes(DEFAULT_CHARSET)) {
      putStringByte(b);
    }
    putStringByte((byte) 0);

    addedStrings.put(str, new Integer(offset));
    return offset;
  }
}