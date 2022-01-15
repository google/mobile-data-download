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
package com.google.android.libraries.mobiledatadownload.delta;

import android.net.Uri;
import com.google.mobiledatadownload.internal.MetadataProto.DeltaFile.DiffDecoder;
import java.io.IOException;

/**
 * Delta decoder Interface.
 *
 * <p>A delta decoder is to generate full content with a much smaller delta content providing a base
 * content.
 */
public interface DeltaDecoder {

  /** Throws when delta decode fails. */
  class DeltaDecodeException extends IOException {
    public DeltaDecodeException(Throwable cause) {
      super(cause);
    }

    public DeltaDecodeException(String msg) {
      super(msg);
    }
  }

  /**
   * Decode file from base file and delta file and writes to the target uri.
   *
   * @param baseUri The input base file URI
   * @param deltaUri The input delta file URI
   * @param targetUri The target decoded output file URI
   * @throws DeltaDecodeException
   */
  void decode(Uri baseUri, Uri deltaUri, Uri targetUri) throws DeltaDecodeException;

  /** Get the supported delta decoder name. */
  DiffDecoder getDecoderName();
}
