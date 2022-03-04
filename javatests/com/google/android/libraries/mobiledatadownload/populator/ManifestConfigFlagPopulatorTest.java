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

import static com.google.android.libraries.mobiledatadownload.populator.ManifestConfigHelper.URL_TEMPLATE_CHECKSUM_PLACEHOLDER;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.android.libraries.mobiledatadownload.AddFileGroupRequest;
import com.google.android.libraries.mobiledatadownload.MobileDataDownload;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mobiledatadownload.DownloadConfigProto.BaseFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFile;
import com.google.mobiledatadownload.DownloadConfigProto.DataFileGroup;
import com.google.mobiledatadownload.DownloadConfigProto.DeltaFile;
import com.google.mobiledatadownload.DownloadConfigProto.DeltaFile.DiffDecoder;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig;
import com.google.mobiledatadownload.DownloadConfigProto.ManifestConfig.UrlTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

/** Tests for {@link ManifestConfigFlagPopulator}. */
@RunWith(RobolectricTestRunner.class)
public class ManifestConfigFlagPopulatorTest {
  @Rule public final MockitoRule mocks = MockitoJUnit.rule();

  @Mock MobileDataDownload mockMobileDataDownload;
  @Captor private ArgumentCaptor<AddFileGroupRequest> addFileGroupRequestCaptor;

  private static final DeltaFile DELTA_FILE =
      DeltaFile.newBuilder()
          .setDiffDecoder(DiffDecoder.VC_DIFF)
          .setUrlToDownload("https://standard.file.url")
          .setChecksum("standardDeltaFileChecksum")
          .setBaseFile(BaseFile.newBuilder().setChecksum("standardBaseFileChecksum").build())
          .build();

  private static final DataFile DATA_FILE =
      DataFile.newBuilder()
          .setFileId("standard-file")
          .setUrlToDownload("https://standard.file.url")
          .setChecksum("standardFileChecksum")
          .addDeltaFile(DELTA_FILE)
          .build();

  private static final DataFileGroup DATA_FILE_GROUP =
      DataFileGroup.newBuilder().setGroupName("groupName").setOwnerPackage("ownerPackage").build();

  @Before
  public void setUp() {
    when(mockMobileDataDownload.addFileGroup(any(AddFileGroupRequest.class)))
        .thenReturn(Futures.immediateFuture(true));
  }

  @Test
  public void refreshFromManifestConfig_absentOverrider()
      throws ExecutionException, InterruptedException {
    ManifestConfig manifestConfig = createManifestConfig();

    ManifestConfigHelper.refreshFromManifestConfig(
            mockMobileDataDownload, manifestConfig, /*overriderOptional=*/ Optional.absent())
        .get();
    verify(mockMobileDataDownload, times(2)).addFileGroup(addFileGroupRequestCaptor.capture());

    assertThat(addFileGroupRequestCaptor.getAllValues().get(0).dataFileGroup().getGroupName())
        .isEqualTo("groupNameLocaleEnUS");
    assertThat(addFileGroupRequestCaptor.getAllValues().get(1).dataFileGroup().getGroupName())
        .isEqualTo("groupNameLocaleEnUK");
  }

  @Test
  public void refreshFromManifestConfig_withUrlTemplate_absentOverrider()
      throws ExecutionException, InterruptedException {
    ManifestConfig manifestConfigWithUrlTemplate = createManifestConfigWithUrlTemplate();
    // File url_to_download populated using url templates.
    DataFileGroup dataFileGroup1 =
        DATA_FILE_GROUP.toBuilder()
            .addFile(
                DATA_FILE.toBuilder()
                    .setUrlToDownload("https://standard.file.url/standardFileChecksum")
                    .setDeltaFile(
                        0,
                        DELTA_FILE.toBuilder()
                            .setUrlToDownload(
                                "https://standard.file.url/standardDeltaFileChecksum")))
            .build();
    DataFileGroup dataFileGroup2 = DATA_FILE_GROUP.toBuilder().addFile(DATA_FILE).build();

    ManifestConfigHelper.refreshFromManifestConfig(
            mockMobileDataDownload,
            manifestConfigWithUrlTemplate,
            /*overriderOptional=*/ Optional.absent())
        .get();
    verify(mockMobileDataDownload, times(2)).addFileGroup(addFileGroupRequestCaptor.capture());

    List<AddFileGroupRequest> addFileGroupRequests = addFileGroupRequestCaptor.getAllValues();
    assertThat(Iterables.transform(addFileGroupRequests, AddFileGroupRequest::dataFileGroup))
        .containsExactly(dataFileGroup1, dataFileGroup2)
        .inOrder();
  }

  @Test
  public void refreshFromManifestConfig_noUrlTemplate_urlToDownloadEmpty_absentOverrider()
      throws ExecutionException, InterruptedException {
    // Files with no url_to_download will fail since url template is not available.
    ManifestConfig manifestConfigWithoutUrlTemplate =
        createManifestConfigWithUrlTemplate().toBuilder().clearUrlTemplate().build();

    IllegalArgumentException illegalArgumentException =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ManifestConfigHelper.refreshFromManifestConfig(
                        mockMobileDataDownload,
                        manifestConfigWithoutUrlTemplate,
                        /*overriderOptional=*/ Optional.absent())
                    .get());

    assertThat(illegalArgumentException)
        .hasMessageThat()
        .isEqualTo("DataFile standard-file url_to_download is missing.");
  }

  @Test
  public void refreshFromManifestConfig_identityOverrider()
      throws ExecutionException, InterruptedException {
    ManifestConfig manifestConfig = createManifestConfig();

    // Use a ManifestConfigOverrider that does not filter/change anything.
    ManifestConfigOverrider overrider =
        new ManifestConfigOverrider() {
          @Override
          public ListenableFuture<List<DataFileGroup>> override(ManifestConfig config) {
            List<DataFileGroup> overriderResults = new ArrayList<>();
            for (ManifestConfig.Entry entry : config.getEntryList()) {
              overriderResults.add(entry.getDataFileGroup());
            }

            return Futures.immediateFuture(overriderResults);
          }
        };

    ManifestConfigHelper.refreshFromManifestConfig(
            mockMobileDataDownload, manifestConfig, Optional.of(overrider))
        .get();
    verify(mockMobileDataDownload, times(2)).addFileGroup(addFileGroupRequestCaptor.capture());

    assertThat(addFileGroupRequestCaptor.getAllValues().get(0).dataFileGroup().getGroupName())
        .isEqualTo("groupNameLocaleEnUS");
    assertThat(addFileGroupRequestCaptor.getAllValues().get(1).dataFileGroup().getGroupName())
        .isEqualTo("groupNameLocaleEnUK");
  }

  @Test
  public void refreshFromManifestConfig_en_USLocaleOverrider()
      throws ExecutionException, InterruptedException {
    ManifestConfig manifestConfig = createManifestConfig();

    // Use a ManifestConfigOverrider that only keeps entry with locale en_US.
    ManifestConfigOverrider overrider =
        new ManifestConfigOverrider() {
          @Override
          public ListenableFuture<List<DataFileGroup>> override(ManifestConfig config) {
            List<DataFileGroup> overriderResults = new ArrayList<>();
            for (ManifestConfig.Entry entry : config.getEntryList()) {
              if ("en_US".equals(entry.getModifier().getLocaleList().get(0))) {
                overriderResults.add(entry.getDataFileGroup());
              }
            }

            return Futures.immediateFuture(overriderResults);
          }
        };

    ManifestConfigHelper.refreshFromManifestConfig(
            mockMobileDataDownload, manifestConfig, Optional.of(overrider))
        .get();
    verify(mockMobileDataDownload, times(1)).addFileGroup(addFileGroupRequestCaptor.capture());

    assertThat(addFileGroupRequestCaptor.getAllValues().get(0).dataFileGroup().getGroupName())
        .isEqualTo("groupNameLocaleEnUS");
  }

  @Test
  public void refreshFileGroups_usesSupplier() throws ExecutionException, InterruptedException {
    ManifestConfig manifestConfig = createManifestConfig();

    ManifestConfigFlagPopulator populator =
        ManifestConfigFlagPopulator.builder()
            .setManifestConfigSupplier(() -> manifestConfig)
            .build();

    populator.refreshFileGroups(mockMobileDataDownload).get();
    verify(mockMobileDataDownload, times(2)).addFileGroup(addFileGroupRequestCaptor.capture());

    assertThat(addFileGroupRequestCaptor.getAllValues().get(0).dataFileGroup().getGroupName())
        .isEqualTo("groupNameLocaleEnUS");
    assertThat(addFileGroupRequestCaptor.getAllValues().get(1).dataFileGroup().getGroupName())
        .isEqualTo("groupNameLocaleEnUK");
  }

  @Test
  public void manifestConfigFlagPopulatorBuilder_rejectsNotBothPackageAndFlag() {
    assertThrows(
        IllegalArgumentException.class, () -> ManifestConfigFlagPopulator.builder().build());
  }

  private static ManifestConfig createManifestConfig() {
    // Create a ManifestConfig with 2 entries for locale en_US and en_UK.
    ManifestConfig.Entry entryUs =
        ManifestConfig.Entry.newBuilder()
            .setModifier(ManifestConfig.Entry.Modifier.newBuilder().addLocale("en_US").build())
            .setDataFileGroup(
                DataFileGroup.newBuilder()
                    .setGroupName("groupNameLocaleEnUS")
                    .setOwnerPackage("ownerPackage"))
            .build();

    ManifestConfig.Entry entryUk =
        ManifestConfig.Entry.newBuilder()
            .setModifier(ManifestConfig.Entry.Modifier.newBuilder().addLocale("en_UK").build())
            .setDataFileGroup(
                DataFileGroup.newBuilder()
                    .setGroupName("groupNameLocaleEnUK")
                    .setOwnerPackage("ownerPackage"))
            .build();

    return ManifestConfig.newBuilder().addEntry(entryUs).addEntry(entryUk).build();
  }

  private static ManifestConfig createManifestConfigWithUrlTemplate() {
    DataFileGroup dataFileGroupWithoutUrlDownload =
        DATA_FILE_GROUP.toBuilder()
            .addFile(
                DATA_FILE.toBuilder()
                    .clearUrlToDownload()
                    .setDeltaFile(0, DELTA_FILE.toBuilder().clearUrlToDownload()))
            .build();
    DataFileGroup dataFileGroup2 = DATA_FILE_GROUP.toBuilder().addFile(DATA_FILE).build();

    return ManifestConfig.newBuilder()
        .setUrlTemplate(
            UrlTemplate.newBuilder()
                .setFileUrlTemplate(
                    "https://standard.file.url/" + URL_TEMPLATE_CHECKSUM_PLACEHOLDER)
                .build())
        .addEntry(
            ManifestConfig.Entry.newBuilder().setDataFileGroup(dataFileGroupWithoutUrlDownload))
        .addEntry(ManifestConfig.Entry.newBuilder().setDataFileGroup(dataFileGroup2))
        .build();
  }
}
