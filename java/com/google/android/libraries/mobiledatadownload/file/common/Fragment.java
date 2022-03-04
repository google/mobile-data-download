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
package com.google.android.libraries.mobiledatadownload.file.common;

import static com.google.android.libraries.mobiledatadownload.file.common.internal.Charsets.UTF_8;

import android.net.Uri;
import android.text.TextUtils;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Preconditions;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A URI fragment parser and builder. Parses fragment params is similar to parsing of query params,
 * and are delimited by "&". For example, <code>#foo=bar&us=them</code>
 *
 * <p>produces two params: one "foo" with value "bar", and another "us" with value "them".
 *
 * <p>Each fragment param must have at least one value; multiple values are delimited by "+". For
 * example, <code>#foo=bar+baz</code>
 *
 * <p>produces one param, "foo", with two values "bar" and "baz".
 *
 * <p>Furthermore, fragment values can have subparams, which are additional information scoped to
 * the value. Subparams have keys and optional values, and are delimited by ",". For example, <code>
 * #foo=bar(x=1)+baz(this=that,when)</code>
 *
 * <p>produces one param, "foo", with two values "bar" and "baz" where "bar" has subparam "x" set at
 * 1, baz has subparam "this" set at "that", and "when" is unset.
 *
 * <p>While the <internal> spec requires that keys and values are [0-9a-zA-Z-_]+, this class
 * encodes/decodes keys/values, including subparams using java.net.URLEncoder/decoder. In
 * particular, this class is responsible for producing strings suitable for being appended verbatim
 * to the fragment part of an RFC3986 URI.
 */
public final class Fragment {
  public static final Fragment EMPTY_FRAGMENT = new Fragment(null);
  public static final Fragment.ParamValue EMPTY_FRAGMENT_PARAM_VALUE = new ParamValue(null, null);

  private final List<Param> params = new ArrayList<Param>();

  private Fragment(@Nullable List<Param> params) {
    if (params != null) {
      this.params.addAll(params);
    }
  }

  /** Create a new, empty builder. */
  public static Fragment.Builder builder() {
    return new Fragment.Builder(null);
  }

  /** Return a builder based on this Fragment. */
  public Fragment.Builder toBuilder() {
    Fragment.Builder builder = builder();
    for (Param param : params) {
      builder.addParam(param.toBuilder());
    }
    return builder;
  }

  /** Iterate over the params. */
  public List<Param> params() {
    return Collections.unmodifiableList(params);
  }

  /** Finds the param with the given key. Returns null if not found. */
  @Nullable
  public Param findParam(String key) {
    for (Param param : params) {
      if (param.key.equals(key)) {
        return param;
      }
    }
    return null;
  }

  @Override
  public String toString() {
    return TextUtils.join("&", params);
  }

  /** Builder for the whole URI fragment. */
  public static final class Builder {
    private final List<Param.Builder> params = new ArrayList<Param.Builder>();

    private Builder(@Nullable List<Param.Builder> params) {
      if (params == null) {
        return;
      }
      for (Param.Builder param : params) {
        addParam(param);
      }
    }

    /** Get all of the params as a list. */
    public List<Param.Builder> params() {
      return Collections.unmodifiableList(params);
    }

    /** Finds the param with the given key. Returns null if not found. */
    @Nullable
    public Param.Builder findParam(String key) {
      for (Param.Builder param : params) {
        if (param.key.equals(key)) {
          return param;
        }
      }
      return null;
    }

    /** Adds a param. If a param with same key already exists, this replaces it. */
    public Builder addParam(Param param) {
      addParam(param.toBuilder());
      return this;
    }

    /** Adds a param. If a param with the same key already exist, this replaces it. */
    public Builder addParam(Param.Builder param) {
      for (int i = 0; i < params.size(); i++) {
        if (params.get(i).key.equals(param.key)) {
          params.set(i, param);
          return this;
        }
      }
      params.add(param);
      return this;
    }

    /** Adds a simple param with no value. */
    public Builder addParam(String key) {
      return addParam(Param.builder(key));
    }

    /** Return an immutable Fragment. Unset params are ignored. */
    public Fragment build() {
      List<Param> params = new ArrayList<Param>();
      for (Param.Builder builder : this.params) {
        Param param = builder.build();
        if (param != null) {
          params.add(param);
        }
      }
      return new Fragment(params);
    }
  }

  /** A fragment param. */
  public static final class Param {
    private final String key;
    private final List<ParamValue> values = new ArrayList<ParamValue>();

    /**
     * @throws IllegalArgumentException if {@code values} is empty.
     */
    private Param(String key, List<ParamValue> values) {
      Preconditions.checkArgument(!values.isEmpty(), "Missing param values");
      this.key = key;
      this.values.addAll(values);
    }

    /** Gets the key for the param. */
    public String key() {
      return key;
    }

    /** Iterate over the values. */
    public List<ParamValue> values() {
      return Collections.unmodifiableList(values);
    }

    /** Find a value by name. Returns null if not found. */
    @Nullable
    public ParamValue findValue(String name) {
      for (ParamValue value : values) {
        if (value.name.equals(name)) {
          return value;
        }
      }
      return null;
    }

    /**
     * Create a new param identified with a key.
     *
     * @param key The unique key.
     * @return The param.
     */
    public static Param.Builder builder(String key) {
      return new Param.Builder(key, null);
    }

    /** Return a builder based on this Param. */
    public Param.Builder toBuilder() {
      Param.Builder builder = builder(key);
      for (ParamValue value : values) {
        builder.addValue(value.toBuilder());
      }
      return builder;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(urlEncode(key));
      builder.append("=");
      builder.append(TextUtils.join("+", values));
      return builder.toString();
    }

    /** Builder for a fragment param. */
    public static final class Builder {
      private final String key;
      private final List<ParamValue.Builder> values = new ArrayList<ParamValue.Builder>();

      private Builder(String key, @Nullable List<ParamValue.Builder> values) {
        this.key = key;
        if (values == null) {
          return;
        }
        for (ParamValue.Builder value : values) {
          addValue(value);
        }
      }

      /** Gets the key for the param. */
      public String key() {
        return key;
      }

      /** Get all of the param values as a list. */
      public List<ParamValue.Builder> values() {
        return Collections.unmodifiableList(values);
      }

      /** Find a value by name. Returns null if not found. */
      @Nullable
      public ParamValue.Builder findValue(String name) {
        for (ParamValue.Builder value : values) {
          if (value.name.equals(name)) {
            return value;
          }
        }
        return null;
      }

      /**
       * Adds a value to this param. If a value already exists with the same name, this will replace
       * it.
       */
      public Builder addValue(ParamValue value) {
        addValue(value.toBuilder());
        return this;
      }

      /**
       * Adds a value to this param. If a value already exists with the same name, this will replace
       * it.
       */
      public Builder addValue(ParamValue.Builder value) {
        for (int i = 0; i < values.size(); i++) {
          if (values.get(i).name.equals(value.name)) {
            values.set(i, value);
            return this;
          }
        }
        values.add(value);
        return this;
      }

      /** Adds a value that has no subparams. Also replaces existing value if present. */
      public Builder addValue(String name) {
        return addValue(new ParamValue.Builder(name, null));
      }

      /** Return a new immutable Param from this builder, or null if the param is unset. */
      @Nullable
      public Param build() {
        if (this.values.isEmpty()) {
          return null;
        }
        List<ParamValue> values = new ArrayList<ParamValue>();
        for (ParamValue.Builder value : this.values) {
          values.add(value.build());
        }
        return new Param(key, values);
      }
    }
  }

  /** A value of a fragment param. Each fragment param can have multiple values. */
  public static final class ParamValue {

    private final String name;
    private final List<SubParam> subparams = new ArrayList<SubParam>();

    private ParamValue(String name, @Nullable List<SubParam> subparams) {
      this.name = name;
      if (subparams != null) {
        this.subparams.addAll(subparams);
      }
    }

    /** Creates a new param value with the given name. */
    public static ParamValue.Builder builder(String name) {
      return new ParamValue.Builder(name, null);
    }

    /** Return a builder based on this ParamValue. */
    public ParamValue.Builder toBuilder() {
      ParamValue.Builder builder = builder(name);
      for (SubParam subparam : subparams) {
        builder.addSubParam(subparam);
      }
      return builder;
    }

    /** The name of the param value. */
    public String name() {
      return name;
    }

    /** Iterate over the subparams. */
    public List<SubParam> subParams() {
      return Collections.unmodifiableList(subparams);
    }

    /** Finds a subparam with the given key. If not found, returns null. */
    @Nullable
    public SubParam findSubParam(String key) {
      for (SubParam subparam : subparams) {
        if (subparam.key.equals(key)) {
          return subparam;
        }
      }
      return null;
    }

    /**
     * Finds the subparam value with the given key. If the subparam or value is null, returns null.
     *
     * @param key
     * @return The value of the subparam or null.
     */
    @Nullable
    public String findSubParamValue(String key) {
      SubParam subparam = findSubParam(key);
      return (subparam == null) ? null : subparam.value;
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append(urlEncode(name));
      if (subparams.isEmpty()) {
        return builder.toString();
      }
      builder.append("(");
      builder.append(TextUtils.join(",", subparams));
      builder.append(")");
      return builder.toString();
    }

    /** Builder for a fragment param value. */
    public static final class Builder {
      private final String name;
      private final List<SubParam> subparams = new ArrayList<SubParam>();

      private Builder(String name, @Nullable List<SubParam> subparams) {
        this.name = name;
        if (subparams == null) {
          return;
        }
        for (SubParam subparam : subparams) {
          addSubParam(subparam);
        }
      }

      /** The name of the param value. */
      public String name() {
        return name;
      }

      /** Get all of the subparams as a list. */
      public List<SubParam> subparams() {
        return Collections.unmodifiableList(subparams);
      }

      /** Finds a subparam with the given key. If not found, returns null. */
      @Nullable
      public SubParam findSubParam(String key) {
        for (SubParam subparam : subparams) {
          if (subparam.key.equals(key)) {
            return subparam;
          }
        }
        return null;
      }

      /**
       * Finds the subparam value with the given key. If the subparam or value is null, returns
       * null.
       *
       * @param key
       * @return The value of the subparam or null.
       */
      @Nullable
      public String findSubParamValue(String key) {
        SubParam subparam = findSubParam(key);
        return (subparam == null) ? null : subparam.value;
      }

      /**
       * Adds a subparam. If an existing subparam exists with the same key, this will replace it.
       *
       * @param subparam
       * @return The subparam or null if not found.
       */
      public Builder addSubParam(SubParam subparam) {
        for (int i = 0; i < subparams.size(); i++) {
          if (subparams.get(i).key.equals(subparam.key)) {
            subparams.set(i, subparam);
            return this;
          }
        }
        subparams.add(subparam);
        return this;
      }

      /**
       * Shortcut to add a subparam with this key and value. Replaces existing subparam with same
       * key if present.
       *
       * @param key The subparam key.
       * @param value The subparam value.
       */
      public Builder addSubParam(String key, String value) {
        return addSubParam(new SubParam(key, value));
      }

      /** Build an immutable ParamValue from this builder. */
      public ParamValue build() {
        return new ParamValue(name, subparams);
      }
    }
  }

  /** A fragment param value subparam. */
  public static final class SubParam {

    private final String key;
    @Nullable private final String value;

    /** Creates a new subparam with the given key and value. */
    public static SubParam build(String key, String value) {
      return new SubParam(key, value);
    }

    /** Creates a new subparam with the given key and no value. */
    public static SubParam build(String key) {
      return new SubParam(key, null);
    }

    private SubParam(String key, @Nullable String value) {
      this.key = key;
      this.value = value;
    }

    /** Returns the subparam key. */
    public String key() {
      return key;
    }

    /** Returns the subparam value, or null if not set. */
    @Nullable
    public String value() {
      return value;
    }

    /** Returns true if the subparam has a value set. */
    public boolean hasValue() {
      return value != null;
    }

    @Override
    public String toString() {
      if (hasValue()) {
        return urlEncode(key) + "=" + urlEncode(value);
      } else {
        return urlEncode(key);
      }
    }
  }

  /** Parses a fragment from the uri as described in {@link Fragment}. */
  public static Fragment parse(Uri uri) {
    return parse(uri.getEncodedFragment());
  }

  /** Parses a fragment from an encoded string as described in {@link Fragment}. */
  public static Fragment parse(@Nullable String encodedFragment) {
    if (TextUtils.isEmpty(encodedFragment)) {
      return EMPTY_FRAGMENT;
    }
    List<Param.Builder> params = new ArrayList<Param.Builder>();
    for (String kvPair : encodedFragment.split("&")) {
      String[] kv = kvPair.split("=", 2);
      List<ParamValue.Builder> values = new ArrayList<ParamValue.Builder>();
      String key = kv[0];
      Preconditions.checkArgument(!TextUtils.isEmpty(key), "malformed key: %s", encodedFragment);
      Preconditions.checkArgument(
          kv.length == 2 && !TextUtils.isEmpty(kv[1]), "missing param value: %s", encodedFragment);
      String rawValues = kv[1];
      String[] splitValues = rawValues.split("\\+");
      for (int i = 0; i < splitValues.length; i++) {
        String value = splitValues[i];
        if (value.isEmpty()) {
          continue;
        }
        List<SubParam> subparams = null;
        int lparen = value.indexOf("(");
        if (lparen != -1) {
          String rawSubparams = value.substring(lparen);
          Preconditions.checkArgument(
              rawSubparams.charAt(0) == '('
                  && rawSubparams.charAt(rawSubparams.length() - 1) == ')',
              "malformed fragment subparams: %s",
              encodedFragment);
          subparams = parseSubParams(rawSubparams.substring(1, rawSubparams.length() - 1));
          value = value.substring(0, lparen);
        } else {
          Preconditions.checkArgument(
              !value.contains(")"), "malformed fragment subparams: %s", encodedFragment);
        }
        values.add(new ParamValue.Builder(urlDecode(value), subparams));
      }
      params.add(new Param.Builder(urlDecode(key), values));
    }
    return new Fragment.Builder(params).build();
  }

  // TODO: This method probably should be elsewhere, perhaps in a lite fragment helper class.
  @Nullable
  public static String getTransformSubParam(Uri uri, String transformName, String subParamKey) {
    Fragment.ParamValue value = getTransformParamValue(uri, transformName);
    if (value == null) {
      return null;
    }
    String result = value.findSubParamValue(subParamKey);
    return !TextUtils.isEmpty(result) ? result : null;
  }

  @Nullable
  public static Fragment.ParamValue getTransformParamValue(Uri uri, String transformName) {
    Fragment.Param param = Fragment.parse(uri).findParam("transform");
    if (param == null) {
      return null;
    }
    return param.findValue(transformName);
  }

  private static List<SubParam> parseSubParams(String rawSubparams) {
    List<SubParam> subparams = new ArrayList<SubParam>();
    String[] pairs = rawSubparams.split(",");
    for (int i = 0; i < pairs.length; i++) {
      String[] kv = pairs[i].split("=", 2);
      String key = kv[0];
      Preconditions.checkArgument(
          !TextUtils.isEmpty(key), "missing fragment subparam key: %s", rawSubparams);
      if (kv.length == 2 && !TextUtils.isEmpty(kv[1])) {
        subparams.add(new SubParam(urlDecode(key), urlDecode(kv[1])));
      } else {
        subparams.add(new SubParam(urlDecode(key), null));
      }
    }
    return subparams;
  }

  private static final String urlEncode(String str) {
    try {
      return URLEncoder.encode(str, UTF_8.displayName());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(); // Not really
    }
  }

  private static final String urlDecode(String str) {
    try {
      return URLDecoder.decode(str, UTF_8.displayName());
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException(); // Not really
    }
  }
}
