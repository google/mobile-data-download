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

import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest.permission;
import android.app.Application;
import android.location.Location;
import android.location.LocationManager;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link LocationProviderImpl}. */
@RunWith(RobolectricTestRunner.class)
public class LocationProviderImplTest {

  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock private LocationManager locationManager;
  private LocationProvider locationProvider;
  private Location locationWithAccuracy;
  private Location locationWithoutAccuracy;

  @Before
  public void setUp() {
    locationProvider = new LocationProviderImpl(getApplicationContext(), locationManager);
    locationWithAccuracy = new Location("provider");
    locationWithAccuracy.setLatitude(40.7);
    locationWithAccuracy.setLongitude(-74.0);
    locationWithoutAccuracy = new Location(locationWithAccuracy);
    locationWithAccuracy.setAccuracy(10);
  }

  @Test
  public void getLocation_whenPermissionsDenied_returnsAbsentLocation() {
    shadowOf((Application) getApplicationContext())
        .denyPermissions(permission.ACCESS_FINE_LOCATION, permission.ACCESS_COARSE_LOCATION);
    assertThat(locationProvider.get()).isAbsent();
  }

  @Test
  public void getLocation_whenEitherAccessLocationPermissionGranted_returnsLocation() {
    when(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
        .thenReturn(locationWithAccuracy);
    shadowOf((Application) getApplicationContext())
        .denyPermissions(permission.ACCESS_COARSE_LOCATION);
    shadowOf((Application) getApplicationContext())
        .grantPermissions(permission.ACCESS_FINE_LOCATION);

    assertThat(locationProvider.get()).hasValue(locationWithAccuracy);

    shadowOf((Application) getApplicationContext())
        .denyPermissions(permission.ACCESS_FINE_LOCATION);
    shadowOf((Application) getApplicationContext())
        .grantPermissions(permission.ACCESS_COARSE_LOCATION);

    assertThat(locationProvider.get()).hasValue(locationWithAccuracy);

    shadowOf((Application) getApplicationContext())
        .grantPermissions(permission.ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION);
    assertThat(locationProvider.get()).hasValue(locationWithAccuracy);
  }

  @Test
  public void getLocation_whenNetworkLocationHasNoAccuracy_returnsGpsLocation() {
    shadowOf((Application) getApplicationContext())
        .grantPermissions(permission.ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION);

    when(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
        .thenReturn(locationWithoutAccuracy);
    when(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
        .thenReturn(locationWithAccuracy);
    assertThat(locationProvider.get()).hasValue(locationWithAccuracy);
  }

  @Test
  public void getLocation_whenBothLocationsHaveNoAccuracy_returnsAbsentLocation() {
    shadowOf((Application) getApplicationContext())
        .grantPermissions(permission.ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION);

    when(locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
        .thenReturn(locationWithoutAccuracy);
    when(locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER))
        .thenReturn(locationWithoutAccuracy);
    assertThat(locationProvider.get()).isAbsent();
  }
}
