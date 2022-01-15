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
package com.google.android.libraries.mobiledatadownload.file.backends;

import android.accounts.Account;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import com.google.android.libraries.mobiledatadownload.file.common.MalformedUriException;
import com.google.android.libraries.mobiledatadownload.file.common.internal.LiteTransformFragments;
import com.google.android.libraries.mobiledatadownload.file.common.internal.Preconditions;
import com.google.android.libraries.mobiledatadownload.file.transforms.TransformProtos;
import com.google.common.collect.ImmutableList;
import com.google.mobiledatadownload.TransformProto;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Helper class for "android:" URIs. */
public final class AndroidUri {

  /**
   * Returns an android: scheme URI builder for package {@code packageName}. If no setter is called
   * before {@link Builder#build}, the resultant URI will point to the common internal app storage,
   * i.e. "android://<packageName>/files/common/shared/"
   *
   * @param context The android environment.
   */
  public static Builder builder(Context context) {
    return new Builder(context);
  }

  private AndroidUri() {}

  // Module names are non-empty strings of [a-z] with interleaved underscores
  private static final Pattern MODULE_PATTERN = Pattern.compile("[a-z]+(_[a-z]+)*");

  // Name registered for the Android backend
  static final String SCHEME_NAME = "android";

  // URI path fragments with special meaning
  static final String FILES_LOCATION = "files";
  static final String MANAGED_LOCATION = "managed";
  static final String CACHE_LOCATION = "cache";
  // See https://developer.android.com/training/articles/direct-boot.html
  static final String DIRECT_BOOT_FILES_LOCATION = "directboot-files";
  static final String DIRECT_BOOT_CACHE_LOCATION = "directboot-cache";
  static final String EXTERNAL_LOCATION = "external";

  // The "managed" location maps to a subdirectory within /files/.
  static final String MANAGED_FILES_DIR_SUBDIRECTORY = "managed";

  static final String COMMON_MODULE = "common";
  static final Account SHARED_ACCOUNT = AccountSerialization.SHARED_ACCOUNT;

  // Module names reserved for future use or that are otherwise disallowed. Note that ImmutableSet
  // is avoided in order to avoid guava dependency.
  private static final Set<String> RESERVED_MODULES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  "default", "unused", "special", "reserved", "shared", "virtual", "managed")));

  private static final Set<String> VALID_LOCATIONS =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  FILES_LOCATION,
                  CACHE_LOCATION,
                  MANAGED_LOCATION,
                  DIRECT_BOOT_FILES_LOCATION,
                  DIRECT_BOOT_CACHE_LOCATION,
                  EXTERNAL_LOCATION)));

  /**
   * Validates the {@code location} of an Android URI path; "files" and "directboot" are the only
   * valid strings.
   */
  static void validateLocation(String location) {
    Preconditions.checkArgument(
        VALID_LOCATIONS.contains(location),
        "The only supported locations are %s: %s",
        VALID_LOCATIONS,
        location);
  }
  /**
   * Validates the {@code module} of an Android URI path. Any non-empty string of [a-z] with
   * interleaved underscores that is not listed as reserved is valid.
   */
  static void validateModule(String module) {
    Preconditions.checkArgument(
        MODULE_PATTERN.matcher(module).matches(), "Module must match [a-z]+(_[a-z]+)*: %s", module);
    Preconditions.checkArgument(
        !RESERVED_MODULES.contains(module),
        "Module name is reserved and cannot be used: %s",
        module);
  }

  /**
   * Validates the {@code unusedRelativePath} of an Android URI path. At present time this is a
   * no-op.
   *
   * @param unusedRelativePath Not used.
   */
  static void validateRelativePath(String unusedRelativePath) {
    // No-op
  }

  /** Builder for Android Uris. */
  public static class Builder {

    // URI authority; required
    private final Context context;

    // URI path components; optional
    private String packageName; // TODO: should default be ""?
    private String location = AndroidUri.FILES_LOCATION;
    private String module = AndroidUri.COMMON_MODULE;
    private Account account = AndroidUri.SHARED_ACCOUNT;
    private String relativePath = "";

    private final ImmutableList.Builder<String> encodedSpecs = ImmutableList.builder();

    private Builder(Context context) {
      Preconditions.checkArgument(context != null, "Context cannot be null");
      this.context = context;
      this.packageName = context.getPackageName();
    }

    /**
     * Sets the package to use in the android uri AUTHORITY. Default is context.getPackageName().
     */
    public Builder setPackage(String packageName) {
      this.packageName = packageName;
      return this;
    }

    private Builder setLocation(String location) {
      AndroidUri.validateLocation(location);
      this.location = location;
      return this;
    }

    public Builder setManagedLocation() {
      return setLocation(MANAGED_LOCATION);
    }

    public Builder setExternalLocation() {
      return setLocation(EXTERNAL_LOCATION);
    }

    public Builder setDirectBootFilesLocation() {
      return setLocation(DIRECT_BOOT_FILES_LOCATION);
    }

    public Builder setDirectBootCacheLocation() {
      return setLocation(DIRECT_BOOT_CACHE_LOCATION);
    }

    /** Internal location, aka "files", is the default location. */
    public Builder setInternalLocation() {
      return setLocation(FILES_LOCATION);
    }

    public Builder setCacheLocation() {
      return setLocation(CACHE_LOCATION);
    }

    public Builder setModule(String module) {
      AndroidUri.validateModule(module);
      this.module = module;
      return this;
    }

    /**
     * Sets the account. AndroidUri.SHARED_ACCOUNT is the default, and it shows up as "shared" on
     * the filesystem.
     *
     * <p>This method performs some account validation. Android Account itself requires that both
     * the type and name fields be present. In addition to this requirement, this backend requires
     * that the type contain no colons (as these are the delimiter used internally for the account
     * serialization), and that neither the type nor the name include any slashes (as these are file
     * separators).
     *
     * <p>The account will be URL encoded in its URI representation (so, eg, "<internal>@gmail.com"
     * will appear as "you%40gmail.com"), but not in the file path representation used to access
     * disk.
     *
     * <p>Note the Linux filesystem accepts filenames composed of any bytes except "/" and NULL.
     *
     * @param account The account to set.
     * @return The fluent Builder.
     */
    public Builder setAccount(Account account) {
      AccountSerialization.serialize(account); // performs validation internally
      this.account = account;
      return this;
    }

    /**
     * Sets the component of the path after location, module and account. A single leading slash
     * will be trimmed if present.
     */
    public Builder setRelativePath(String relativePath) {
      if (relativePath.startsWith("/")) {
        relativePath = relativePath.substring(1);
      }
      AndroidUri.validateRelativePath(relativePath);
      this.relativePath = relativePath;
      return this;
    }

    /**
     * Updates builder with multiple fields from file param: location, module, account and relative
     * path. This method will fail on "managed" paths (see {@link fromFile(File, AccountManager)}).
     */
    public Builder fromFile(File file) {
      return fromAbsolutePath(file.getAbsolutePath(), /* accountManager= */ null);
    }

    /**
     * Updates builder with multiple fields from file param: location, module, account and relative
     * path. A non-null {@code accountManager} is required to handle "managed" paths.
     */
    public Builder fromFile(File file, @Nullable AccountManager accountManager) {
      return fromAbsolutePath(file.getAbsolutePath(), accountManager);
    }

    /**
     * Updates builder with multiple fields from absolute path param: location, module, account and
     * relative path. This method will fail on "managed" paths (see {@link fromAbsolutePath(String,
     * AccountManager)}).
     */
    public Builder fromAbsolutePath(String absolutePath) {
      return fromAbsolutePath(absolutePath, /* accountManager= */ null);
    }

    /**
     * Updates builder with multiple fields from absolute path param: location, module, account and
     * relative path. A non-null {@code accountManager} is required to handle "managed" paths.
     */
    // TODO(b/129467051): remove requirement for segments after 0th (logical location)
    public Builder fromAbsolutePath(String absolutePath, @Nullable AccountManager accountManager) {
      // Get the file's path within internal files, /module/account</relativePath>
      File filesDir = AndroidFileEnvironment.getFilesDirWithPreNWorkaround(context);
      String filesDirPath = filesDir.getAbsolutePath();
      String cacheDirPath = context.getCacheDir().getAbsolutePath();
      String managedDirPath = new File(filesDir, MANAGED_FILES_DIR_SUBDIRECTORY).getAbsolutePath();
      String externalDirPath = null;
      File externalFilesDir = context.getExternalFilesDir(null);
      if (externalFilesDir != null) {
        externalDirPath = externalFilesDir.getAbsolutePath();
      }
      String directBootFilesPath = null;
      String directBootCachePath = null;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        // TODO(b/143610872): run after checking other dirs to minimize impact of new Context()'s
        File dpsDataDir = AndroidFileEnvironment.getDeviceProtectedDataDir(context);
        directBootFilesPath = new File(dpsDataDir, "files").getAbsolutePath();
        directBootCachePath = new File(dpsDataDir, "cache").getAbsolutePath();
      }

      String internalPath;
      if (absolutePath.startsWith(managedDirPath)) {
        // managedDirPath must be checked before filesDirPath because filesDirPath is a prefix.
        setLocation(AndroidUri.MANAGED_LOCATION);
        internalPath = absolutePath.substring(managedDirPath.length());
      } else if (absolutePath.startsWith(filesDirPath)) {
        setLocation(AndroidUri.FILES_LOCATION);
        internalPath = absolutePath.substring(filesDirPath.length());
      } else if (absolutePath.startsWith(cacheDirPath)) {
        setLocation(AndroidUri.CACHE_LOCATION);
        internalPath = absolutePath.substring(cacheDirPath.length());
      } else if (externalDirPath != null && absolutePath.startsWith(externalDirPath)) {
        setLocation(AndroidUri.EXTERNAL_LOCATION);
        internalPath = absolutePath.substring(externalDirPath.length());
      } else if (directBootFilesPath != null && absolutePath.startsWith(directBootFilesPath)) {
        setLocation(AndroidUri.DIRECT_BOOT_FILES_LOCATION);
        internalPath = absolutePath.substring(directBootFilesPath.length());
      } else if (directBootCachePath != null && absolutePath.startsWith(directBootCachePath)) {
        setLocation(AndroidUri.DIRECT_BOOT_CACHE_LOCATION);
        internalPath = absolutePath.substring(directBootCachePath.length());
      } else {
        throw new IllegalArgumentException(
            "Path must be in app-private files dir or external files dir: " + absolutePath);
      }

      // Extract components according to android: file layout. The 0th element of split() will be
      // an empty string preceding the first character "/"
      List<String> pathFragments = Arrays.asList(internalPath.split(File.separator));
      Preconditions.checkArgument(
          pathFragments.size() >= 3,
          "Path must be in module and account subdirectories: %s",
          absolutePath);
      setModule(pathFragments.get(1));

      String accountStr = pathFragments.get(2);
      if (MANAGED_LOCATION.equals(location) && !AccountSerialization.isSharedAccount(accountStr)) {
        int accountId;
        try {
          accountId = Integer.parseInt(accountStr);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException(e);
        }

        // Blocks on disk IO to read account table.
        // TODO(b/115940396): surface bad account as FileNotFoundException (change API signature?)
        Preconditions.checkArgument(accountManager != null, "AccountManager cannot be null");
        try {
          setAccount(accountManager.getAccount(accountId).get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalArgumentException(new MalformedUriException(e));
        } catch (ExecutionException e) {
          throw new IllegalArgumentException(new MalformedUriException(e.getCause()));
        }
      } else {
        setAccount(AccountSerialization.deserialize(accountStr));
      }

      setRelativePath(internalPath.substring(module.length() + accountStr.length() + 2));
      return this;
    }

    public Builder withTransform(TransformProto.Transform spec) {
      encodedSpecs.add(TransformProtos.toEncodedSpec(spec));
      return this;
    }

    // TODO(b/115940396): add MalformedUriException to signature
    public Uri build() {
      String uriPath =
          "/"
              + location
              + "/"
              + module
              + "/"
              + AccountSerialization.serialize(account)
              + "/"
              + relativePath;
      String fragment = LiteTransformFragments.joinTransformSpecs(encodedSpecs.build());

      return new Uri.Builder()
          .scheme(AndroidUri.SCHEME_NAME)
          .authority(packageName)
          .path(uriPath)
          .encodedFragment(fragment)
          .build();
    }
  }
}
