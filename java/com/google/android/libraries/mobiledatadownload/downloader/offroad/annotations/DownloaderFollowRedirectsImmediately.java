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
package com.google.android.libraries.mobiledatadownload.downloader.offroad.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/**
 * A Flag that controls whether the url engines registered to Downloader should follow redirects
 * immediately.
 *
 * <p>In most common cases, this flag should be true, but there are some features which require this
 * flag to be false (such as when providing Cookies on redirect requests is required).
 *
 * <p>NOTE: This flag will be calculated in MDD's {@link BaseFileDownloaderDepsModule} based on
 * other client-provided dependencies, so clients do not have to provide a binding for the flag
 * itself.
 */
@Target({ElementType.PARAMETER, ElementType.METHOD, ElementType.FIELD})
@Qualifier
public @interface DownloaderFollowRedirectsImmediately {}
