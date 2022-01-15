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
package com.google.android.libraries.mobiledatadownload.internal.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.inject.Qualifier;

/** Annotations for MDD internals. */
// TODO(b/168081073): Add AccountManager and SequentialControlExecutor to this file.
public final class Annotations {
  /** Qualifier for the PDS migration diagnostic metadata. */
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface PdsMigrationDiagnostic {}

  /** Qualifier for the PDS migration destination metadata. */
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface PdsMigrationDestination {}

  /** Qualifier for the PDS migration file groups destination uri. */
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface FileGroupsDestinationUri {}

  /** Qualifier for the PDS migration file groups diagnostic uri. */
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface FileGroupsDiagnosticUri {}

  /** Qualifier for the PDS migration shared files destination uri. */
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface SharedFilesDestinationUri {}

  /** Qualifier for the PDS migration shared files diagnostic uri. */
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface SharedFilesDiagnosticUri {}

  /** Qualifier for the PDS for logging state. */
  @Qualifier
  @Retention(RetentionPolicy.RUNTIME)
  public @interface LoggingStateStore {}

  private Annotations() {}
}
