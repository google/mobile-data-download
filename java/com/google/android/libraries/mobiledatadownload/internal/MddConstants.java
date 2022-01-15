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
package com.google.android.libraries.mobiledatadownload.internal;

import android.content.Context;

/** Common MDD Constants */
public class MddConstants {

  // The gms app package name.
  public static final String GMS_PACKAGE = "com.google.android.gms";

  // MDD Phenotype base package name.
  private static final String BASE_CONFIG_PACKAGE_NAME = "com.google.android.gms.icing.mdd";

  // The mdd package name. This is also the mdd phenotype package name.
  // TODO: Replace usage with getPhenotypeConfigPackageName.
  public static final String CONFIG_PACKAGE_NAME = "com.google.android.gms.icing.mdd";

  /** Returns the ph package name that the host app should register with on behalf of MDD. */
  public static String getPhenotypeConfigPackageName(Context context) {
    if (context.getPackageName().equals(GMS_PACKAGE)) {
      return BASE_CONFIG_PACKAGE_NAME;
    } else {
      return BASE_CONFIG_PACKAGE_NAME + "#" + context.getPackageName();
    }
  }

  /** Icing-specific constants. Source of truth is the corresponding Icing files. * */
  // The Icing log source name from here:
  // <internal>
  // LINT.IfChange
  public static final String ICING_LOG_SOURCE_NAME = "ICING";
  // LINT.ThenChange(<internal>)

  public static final String MDD_GCM_TASK_SERVICE_PROXY_CLASS_NAME =
      "com.google.android.gms.mdi.download.service.MddGcmTaskService";

  public static final String SPLIT_CHAR = "|";

  /** The custom URL scheme used by MDD to identify inline files. */
  public static final String INLINE_FILE_URL_SCHEME = "inlinefile";

  /** URL schemes used for sideloaded files. */
  public static final String SIDELOAD_FILE_URL_SCHEME = "file";

  public static final String EMBEDDED_ASSET_URL_SCHEME = "asset";
}
