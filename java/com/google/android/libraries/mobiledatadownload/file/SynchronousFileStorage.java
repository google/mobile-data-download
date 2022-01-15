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
package com.google.android.libraries.mobiledatadownload.file;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import com.google.android.libraries.mobiledatadownload.file.common.GcParam;
import com.google.android.libraries.mobiledatadownload.file.common.UnsupportedFileStorageOperation;
import com.google.android.libraries.mobiledatadownload.file.common.internal.LiteTransformFragments;
import com.google.android.libraries.mobiledatadownload.file.spi.Backend;
import com.google.android.libraries.mobiledatadownload.file.spi.Monitor;
import com.google.android.libraries.mobiledatadownload.file.spi.Transform;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.errorprone.annotations.CheckReturnValue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

/**
 * FileStorage is an abstraction over platform File I/O that supports pluggable backends and
 * transforms. This is the synchronous variant which is useful for background processing and
 * implementing Openers.
 *
 * <p>For testing, it is recommended to use a real backend such as JavaFileBackend, rather than
 * mock.
 *
 * <p>See <internal> for details.
 */
public final class SynchronousFileStorage {

  private static final String TAG = "MobStore.FileStorage";

  private final Map<String, Backend> backends = new HashMap<>();
  private final Map<String, Transform> transforms = new HashMap<>();
  private final List<Monitor> monitors = new ArrayList<>();

  /**
   * Constructs a new SynchronousFileStorage with the specified executors, backends, transforms, and
   * monitors.
   *
   * <p>In the case of a collision, the later backend/transform replaces any earlier ones.
   *
   * <p>FileStorage is expected to be a singleton provided by dependency injection. Transforms and
   * backends should be registered once when producing that singleton.
   *
   * <p>All monitors are executed between transforms and the backend. For example, if you had a
   * compression transform, the monitor would see the compressed bytes.
   *
   * @param backends Registers these backends.
   * @param transforms Registers these transforms.
   * @param monitors Registers these monitors.
   */
  public SynchronousFileStorage(
      List<Backend> backends, List<Transform> transforms, List<Monitor> monitors) {
    registerPlugins(backends, transforms, monitors);
  }

  /** Constructs a new FileStorage with Transforms but no Monitors. */
  public SynchronousFileStorage(List<Backend> backends, List<Transform> transforms) {
    this(backends, transforms, Collections.emptyList());
  }

  /** Constructs a new FileStorage with no Transforms or Monitors. */
  public SynchronousFileStorage(List<Backend> backends) {
    this(backends, Collections.emptyList(), Collections.emptyList());
  }

  /**
   * Registers backends, transforms and monitors to SynchronousFileStorage.
   *
   * @throws IllegalArgumentException for attempts to override existing backends or transforms
   */
  private void registerPlugins(
      List<Backend> backends, List<Transform> transforms, List<Monitor> monitors) {
    for (Backend backend : backends) {
      if (TextUtils.isEmpty(backend.name())) {
        Log.w(TAG, "Cannot register backend, name empty");
        continue;
      }

      Backend oldValue = this.backends.put(backend.name(), backend);
      if (oldValue != null) {
        throw new IllegalArgumentException(
            "Cannot override Backend "
                + oldValue.getClass().getCanonicalName()
                + " with "
                + backend.getClass().getCanonicalName());
      }
    }
    for (Transform transform : transforms) {
      if (TextUtils.isEmpty(transform.name())) {
        Log.w(TAG, "Cannot register transform, name empty");
        continue;
      }
      Transform oldValue = this.transforms.put(transform.name(), transform);
      if (oldValue != null) {
        throw new IllegalArgumentException(
            "Cannot to override Transform "
                + oldValue.getClass().getCanonicalName()
                + " with "
                + transform.getClass().getCanonicalName());
      }
    }
    this.monitors.addAll(monitors);
  }

  /**
   * Returns a String listing registered backends, transforms and monitors for debugging purposes.
   */
  public String getDebugInfo() {
    String backendsDebugString =
        TextUtils.join(
            ",\n",
            Sets.newTreeSet(
                Iterables.transform(
                    backends.keySet(),
                    key ->
                        String.format(
                            "protocol: %1$s, class: %2$s",
                            key, backends.get(key).getClass().getSimpleName()))));

    String transformsDebugString =
        TextUtils.join(
            ",\n",
            Sets.newTreeSet(
                Iterables.transform(
                    transforms.values(), transform -> transform.getClass().getSimpleName())));

    String monitorsDebugString =
        TextUtils.join(
            ",\n",
            Sets.newTreeSet(
                Iterables.transform(monitors, monitor -> monitor.getClass().getSimpleName())));

    return String.format(
        "Registered Mobstore Plugins:\n\nBackends:\n%1$s\n\nTransforms:\n%2$s\n\nMonitors:\n%3$s",
        backendsDebugString, transformsDebugString, monitorsDebugString);
  }

  /**
   * Open URI with an Opener. The Opener determines the return type, eg, a Stream or a Proto and is
   * responsible for implementing any additional behavior such as locking.
   *
   * @param uri The URI to open.
   * @param opener The generic opener to use.
   * @param <T> The kind of thing the opener opens.
   * @return The result of the open operation.
   */
  @CheckReturnValue
  public <T> T open(Uri uri, Opener<T> opener) throws IOException {
    OpenContext context = getContext(uri);
    return opener.open(context);
  }

  /**
   * Deletes the file denoted by {@code uri}.
   *
   * @throws IOException if the file could not be deleted for any reason
   */
  public void deleteFile(Uri uri) throws IOException {
    OpenContext context = getContext(uri);
    context.backend().deleteFile(context.encodedUri());
  }

  /**
   * Deletes the directory denoted by {@code uri}. The directory must be empty in order to be
   * deleted.
   *
   * @throws IOException if the directory could not be deleted for any reason
   */
  public void deleteDirectory(Uri uri) throws IOException {
    Backend backend = getBackend(uri.getScheme());
    backend.deleteDirectory(stripFragment(uri));
  }

  /**
   * Delete a file or directory and all its contents at a specified location.
   *
   * @param uri the location to delete
   * @return true if and only if the file or directory specified at {@code uri} was deleted.
   */
  @Deprecated // see {@link
  // com.google.android.libraries.mobiledatadownload.file.openers.RecursiveDeleteOpener}
  public boolean deleteRecursively(Uri uri) throws IOException {
    if (!exists(uri)) {
      return false;
    }
    if (!isDirectory(uri)) {
      deleteFile(uri);
      return true;
    }
    for (Uri child : children(uri)) {
      deleteRecursively(child);
    }
    deleteDirectory(uri);
    return true;
  }

  /**
   * Tells whether this file or directory exists.
   *
   * <p>The last segment of the uri path is interpreted as a file name and may be encoded by a
   * transform. Callers should consider using {@link #isDirectory}, stripping fragments, or adding a
   * trailing slash to avoid accidentally encoding a directory name.
   *
   * @param uri
   * @return the success value of the operation.
   */
  @CheckReturnValue
  public boolean exists(Uri uri) throws IOException {
    OpenContext context = getContext(uri);
    return context.backend().exists(context.encodedUri());
  }

  /**
   * Tells whether this uri refers to a directory.
   *
   * @param uri
   * @return the success value of the operation.
   */
  @CheckReturnValue
  public boolean isDirectory(Uri uri) throws IOException {
    Backend backend = getBackend(uri.getScheme());
    return backend.isDirectory(stripFragment(uri));
  }

  /**
   * Creates a new directory. Any non-existent parent directories will also be created.
   *
   * @throws IOException if the directory could not be created for any reason
   */
  public void createDirectory(Uri uri) throws IOException {
    Backend backend = getBackend(uri.getScheme());
    backend.createDirectory(stripFragment(uri));
  }

  /**
   * Gets the file size.
   *
   * <p>If the uri refers to a directory or non-existent, returns 0.
   *
   * @param uri
   * @return the size in bytes of the file.
   */
  @CheckReturnValue
  public long fileSize(Uri uri) throws IOException {
    OpenContext context = getContext(uri);
    return context.backend().fileSize(context.encodedUri());
  }

  /**
   * Renames the file or directory from one location to another. This can only be performed if the
   * schemes of the Uris map to the same backend instance.
   *
   * <p>The last segment of the uri path is interpreted as a file name and may be encoded by a
   * transform. Callers should ensure a trailing slash is included for directory names or strip
   * transforms to avoid accidentally encoding a directory name.
   *
   * @throws IOException if the file could not be renamed for any reason
   */
  public void rename(Uri from, Uri to) throws IOException {
    OpenContext fromContext = getContext(from);
    OpenContext toContext = getContext(to);
    // Even if it's the same provider, require that the backend instances be the same
    // for a rename operation. (Can make less restrictive if necessary.)
    if (fromContext.backend() != toContext.backend()) {
      throw new UnsupportedFileStorageOperation("Cannot rename file across backends");
    }
    fromContext.backend().rename(fromContext.encodedUri(), toContext.encodedUri());
  }

  /**
   * Lists children of a parent directory Uri.
   *
   * @param parentUri The parent directory to list.
   * @return the list of children.
   */
  @CheckReturnValue
  public Iterable<Uri> children(Uri parentUri) throws IOException {
    Backend backend = getBackend(parentUri.getScheme());
    List<Transform> enabledTransforms = getEnabledTransforms(parentUri);
    List<Uri> result = new ArrayList<Uri>();
    String encodedFragment = parentUri.getEncodedFragment();
    for (Uri child : backend.children(stripFragment(parentUri))) {
      Uri decodedChild =
          decodeFilename(
              enabledTransforms, child.buildUpon().encodedFragment(encodedFragment).build());
      result.add(decodedChild);
    }
    return result;
  }

  /** Retrieves the {@link GcParam} associated with the given URI. */
  public GcParam getGcParam(Uri uri) throws IOException {
    OpenContext context = getContext(uri);
    return context.backend().getGcParam(context.encodedUri());
  }

  /** Sets the {@link GcParam} associated with the given URI. */
  public void setGcParam(Uri uri, GcParam param) throws IOException {
    OpenContext context = getContext(uri);
    context.backend().setGcParam(context.encodedUri(), param);
  }

  private OpenContext getContext(Uri uri) throws IOException {
    List<Transform> enabledTransforms = getEnabledTransforms(uri);
    return OpenContext.builder()
        .setStorage(this)
        .setBackend(getBackend(uri.getScheme()))
        .setMonitors(monitors)
        .setTransforms(enabledTransforms)
        .setOriginalUri(uri)
        .setEncodedUri(encodeFilename(enabledTransforms, uri))
        .build();
  }

  private Backend getBackend(String scheme) throws IOException {
    Backend backend = backends.get(scheme);
    if (backend == null) {
      throw new UnsupportedFileStorageOperation(
          String.format("Cannot open, unregistered backend: %s", scheme));
    }
    return backend;
  }

  private ImmutableList<Transform> getEnabledTransforms(Uri uri)
      throws UnsupportedFileStorageOperation {
    ImmutableList.Builder<Transform> builder = ImmutableList.builder();
    for (String name : LiteTransformFragments.parseTransformNames(uri)) {
      Transform transform = transforms.get(name);
      if (transform == null) {
        throw new UnsupportedFileStorageOperation("No such transform: " + name + ": " + uri);
      }
      builder.add(transform);
    }
    return builder.build().reverse();
  }

  private static final Uri stripFragment(Uri uri) {
    return uri.buildUpon().fragment(null).build();
  }

  /**
   * Give transforms the opportunity to encode the file part (last segment for file operations) of
   * the uri. Also strips fragment.
   */
  private static final Uri encodeFilename(List<Transform> transforms, Uri uri) {
    if (transforms.isEmpty()) {
      return uri;
    }
    List<String> segments = new ArrayList<String>(uri.getPathSegments());
    // This Uri implementation's getPathSegments() ignores trailing "/".
    if (segments.isEmpty() || uri.getPath().endsWith("/")) {
      return uri;
    }
    String filename = segments.get(segments.size() - 1);
    // Reverse transforms, restoring their original order. (In all other places the reverse order
    // is more convenient.)
    for (ListIterator<Transform> iter = transforms.listIterator(transforms.size());
        iter.hasPrevious(); ) {
      Transform transform = iter.previous();
      filename = transform.encode(uri, filename);
    }
    segments.set(segments.size() - 1, filename);
    return uri.buildUpon().path(TextUtils.join("/", segments)).encodedFragment(null).build();
  }

  /**
   * Give transforms the opportunity to decode the file part (last segment for file operations) of
   * the uri. Reverses encodeFilename().
   */
  private static final Uri decodeFilename(List<Transform> transforms, Uri uri) {
    if (transforms.isEmpty()) {
      return uri;
    }
    List<String> segments = new ArrayList<String>(uri.getPathSegments());
    // This Uri implementation's getPathSegments() ignores trailing "/".
    if (segments.isEmpty() || uri.getPath().endsWith("/")) {
      return uri;
    }
    String filename = Iterables.getLast(segments);
    for (Transform transform : transforms) {
      filename = transform.decode(uri, filename);
    }
    segments.set(segments.size() - 1, filename);
    return uri.buildUpon().path(TextUtils.join("/", segments)).build();
  }
}
