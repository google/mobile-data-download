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
package com.google.android.libraries.mobiledatadownload.populator;

import android.Manifest.permission;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import androidx.core.content.ContextCompat;
import com.google.common.base.Optional;

/**
 * This common class defines a function that provides the device location to the Webref populator.
 */
final class LocationProviderImpl implements LocationProvider {
  private final Context context;
  private final LocationManager locationManager;

  LocationProviderImpl(Context context, LocationManager locationManager) {
    this.context = context;
    this.locationManager = locationManager;
  }

  /**
   * Returns the location according to network or GPS provider or returns absent if app doesn't have
   * the permission to request the location.
   */
  @Override
  public Optional<Location> get() {
    if (ContextCompat.checkSelfPermission(context, permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_DENIED
        && ContextCompat.checkSelfPermission(context, permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_DENIED) {
      return Optional.absent();
    }

    Location networkProviderLocation =
        locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
    if (networkProviderLocation != null && networkProviderLocation.hasAccuracy()) {
      return Optional.of(networkProviderLocation);
    }
    Location gpsProviderLocation =
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
    if (gpsProviderLocation != null && gpsProviderLocation.hasAccuracy()) {
      return Optional.of(gpsProviderLocation);
    }
    return Optional.absent();
  }
}
