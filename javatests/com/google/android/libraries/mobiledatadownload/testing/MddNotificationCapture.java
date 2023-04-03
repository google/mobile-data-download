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
package com.google.android.libraries.mobiledatadownload.testing;

import static com.google.common.truth.Truth.assertThat;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.service.notification.StatusBarNotification;
import com.google.android.apps.common.testing.util.AndroidTestUtil;
import com.google.android.libraries.mobiledatadownload.foreground.NotificationUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.truth.Correspondence;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** Testing Utility to capture notifications and make assertions. */
public interface MddNotificationCapture {
  /**
   * ADB shell command to clear notifications.
   *
   * <p>This command uses the notification service (i.e. NotificationManager) and calls the first
   * method in that services idl definition. For our service, this refers to the method <a
   * href="<internal>">cancelAllNotifications</a> in the INotificationManager.aidl.
   */
  static final String CLEAR_NOTIFICATIONS_CMD = "service call notification 1";

  /**
   * ADB shell command to dump notification content to logcat.
   *
   * <p>The {@code dumpsys} command is used to dump system diagnostics. We are interested in
   * notifications, so they are specified here. The {@code --noredact} flag is added to provided
   * unredacted info (the actual values of variables) instead of just their type definitions. This
   * flag has varying levels of support across the API levels, but should provide equivalent or
   * greater information in the log dump that can be captured.
   */
  static final String DUMP_NOTIFICATION_CMD = "dumpsys notification --noredact";

  static final ImmutableList<Integer> MDD_ICON_IDS =
      ImmutableList.of(
          android.R.drawable.stat_sys_download,
          android.R.drawable.stat_sys_download_done,
          android.R.drawable.stat_sys_warning);

  public static void clearNotifications() {
    try {
      AndroidTestUtil.executeShellCommand(CLEAR_NOTIFICATIONS_CMD);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to execute shell command", e);
    }
  }

  public static MddNotificationCapture snapshot(Context context) {
    // Capturing active notifications is only available for API level 23 and above. Check to see if
    // the current version supports it, otherwise fall back to using adb to capture notification
    // content.
    if (VERSION.SDK_INT >= VERSION_CODES.M) {
      NotificationManager manager =
          (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
      StatusBarNotification[] activeNotifications = manager.getActiveNotifications();
      List<Notification> notifications = new ArrayList<>();
      for (StatusBarNotification notification : activeNotifications) {
        // TODO(b/148401016): Add some test to ensure only MDD notifications are included in this
        // list.
        notifications.add(notification.getNotification());
      }
      return new MPlusNotificationCapture(context, notifications);
    }
    try {
      // NotificationManager.getActivitNotifications() is unavailable, fallback to using adb
      String result = AndroidTestUtil.executeShellCommand(DUMP_NOTIFICATION_CMD);
      return new PreMNotificationCapture(context, result);
    } catch (IOException e) {
      throw new IllegalStateException("Unable to execute shell command", e);
    }
  }

  void assertStartNotificationCaptured(String title, String text);

  void assertSuccessNotificationCaptured(String title);

  void assertFailedNotificationCaptured(String title);

  void assertPausedNotificationCaptured(String title, boolean wifiOnly);

  void assertNoNotificationsCaptured();

  /**
   * Implementation of Notification Capture for API levels below 23 (Android M).
   *
   * <p>This implementation relies on content captured from adb about notifictions. The primary
   * method of finding results is using Regexes to search for relevant pieces of information about
   * captured notifications.
   */
  public static class PreMNotificationCapture implements MddNotificationCapture {
    private final Context context;
    private final String notificationOutput;

    private static final Pattern CONTENT_TITLE_PATTERN =
        Pattern.compile("android\\.title=String\\s\\((.+)\\)");
    private static final Pattern CONTENT_TEXT_PATTERN =
        Pattern.compile("android\\.text=String\\s\\((.+)\\)");
    private static final Pattern ICON_PATTERN = Pattern.compile("icon=0x([a-fA-F0-9]+)");

    private PreMNotificationCapture(Context context, String notificationOutput) {
      this.context = context;
      this.notificationOutput = notificationOutput;
    }

    @Override
    public void assertStartNotificationCaptured(String title, String text) {
      assertNotificationCapturedMatches(title, text, android.R.drawable.stat_sys_download);
    }

    @Override
    public void assertSuccessNotificationCaptured(String title) {
      assertNotificationCapturedMatches(
          title,
          NotificationUtil.getDownloadSuccessMessage(context),
          android.R.drawable.stat_sys_download_done);
    }

    @Override
    public void assertFailedNotificationCaptured(String title) {
      assertNotificationCapturedMatches(
          title,
          NotificationUtil.getDownloadFailedMessage(context),
          android.R.drawable.stat_sys_warning);
    }

    @Override
    public void assertPausedNotificationCaptured(String title, boolean wifiOnly) {
      assertNotificationCapturedMatches(
          title,
          wifiOnly
              ? NotificationUtil.getDownloadPausedWifiMessage(context)
              : NotificationUtil.getDownloadPausedMessage(context),
          android.R.drawable.stat_sys_download);
    }

    @Override
    public void assertNoNotificationsCaptured() {
      List<String> titleMatches = getMatching(CONTENT_TITLE_PATTERN);
      List<String> textMatches = getMatching(CONTENT_TEXT_PATTERN);
      List<String> iconMatches = getMatching(ICON_PATTERN);

      assertThat(titleMatches).isEmpty();
      assertThat(textMatches).isEmpty();

      // Capturing through adb includes inactive notifications too, so just make sure none of the
      // MDD notifications were capturesd.
      assertThat(iconMatches)
          .comparingElementsUsing(
              Correspondence.<String, Integer>transforming(
                  match ->
                      // Our regex should capture only valid hexadecimal values
                      Integer.parseInt(match, 16),
                  "convert to resource id"))
          .containsNoneIn(MDD_ICON_IDS);
    }

    // TODO(b/148401016): Remove "unused" when title/text can be matched.
    private void assertNotificationCapturedMatches(
        String unusedTitle, String unusedText, int icon) {
      /* List<String> titleMatches = getMatching(CONTENT_TITLE_PATTERN);
       * List<String> textMatches = getMatching(CONTENT_TEXT_PATTERN); */
      List<String> iconMatches = getMatching(ICON_PATTERN);

      // TODO(b/148401016): Figure out how to access unredacted title and text content to match.
      /* assertThat(titleMatches)
       *     .comparingElementsUsing(
       *         Correspondence.<String, Boolean>transforming(
       *             match -> match.contains(title), "is a title match"))
       *     .contains(true);
       * assertThat(textMatches)
       *     .comparingElementsUsing(
       *         Correspondence.<String, Boolean>transforming(
       *             match -> match.contains(text), "is a text match"))
       *     .contains(true); */
      assertThat(iconMatches)
          .comparingElementsUsing(
              Correspondence.<String, Boolean>transforming(
                  match -> {
                    // Our regex should capture only valid hexadecimal values
                    int iconResId = Integer.parseInt(match, 16);
                    return iconResId == icon;
                  },
                  "is an icon match"))
          .contains(true);
    }

    private List<String> getMatching(Pattern pattern) {
      List<String> matches = new ArrayList<>();
      Matcher matcher = pattern.matcher(notificationOutput);
      while (matcher.find()) {
        matches.add(matcher.group(1).trim());
      }
      return matches;
    }
  }

  /**
   * Implementation of Notification Capture for API level 23 (Android M) and above.
   *
   * <p>This implementation relies on capturing Notifications using {@link
   * NotificationManager#getActiveNotifications}. Available parts of the {@link Notification} are
   * used to check for matching notifications.
   */
  public static class MPlusNotificationCapture implements MddNotificationCapture {
    private final Context context;
    private final List<Notification> notifications;

    private MPlusNotificationCapture(Context context, List<Notification> notifications) {
      Preconditions.checkState(
          VERSION.SDK_INT >= VERSION_CODES.M, "This implementation only works for M+ devices");
      this.context = context;
      this.notifications = notifications;
    }

    @Override
    public void assertStartNotificationCaptured(String title, String text) {
      assertThat(notifications)
          .comparingElementsUsing(
              createMatcherForNotification(
                  title, text, android.R.drawable.stat_sys_download, "is a start notification"))
          .contains(true);
    }

    @Override
    public void assertSuccessNotificationCaptured(String title) {
      assertThat(notifications)
          .comparingElementsUsing(
              createMatcherForNotification(
                  title,
                  NotificationUtil.getDownloadSuccessMessage(context),
                  android.R.drawable.stat_sys_download_done,
                  "is a success notification"))
          .contains(true);
    }

    @Override
    public void assertFailedNotificationCaptured(String title) {
      assertThat(notifications)
          .comparingElementsUsing(
              createMatcherForNotification(
                  title,
                  NotificationUtil.getDownloadFailedMessage(context),
                  android.R.drawable.stat_sys_warning,
                  "is a failed notification"))
          .contains(true);
    }

    @Override
    public void assertPausedNotificationCaptured(String title, boolean wifiOnly) {
      assertThat(notifications)
          .comparingElementsUsing(
              createMatcherForNotification(
                  title,
                  wifiOnly
                      ? NotificationUtil.getDownloadPausedWifiMessage(context)
                      : NotificationUtil.getDownloadPausedMessage(context),
                  android.R.drawable.stat_sys_download,
                  "is a paused notification"))
          .contains(true);
    }

    @Override
    public void assertNoNotificationsCaptured() {
      assertThat(notifications).isEmpty();
    }

    private static Correspondence<Notification, Boolean> createMatcherForNotification(
        String title, String text, int icon, String tag) {
      return Correspondence.transforming(
          (@Nullable Notification actual) -> {
            if (actual == null) {
              return false;
            }

            boolean matches =
                String.valueOf(actual.extras.getCharSequence("android.title")).equals(title)
                    && String.valueOf(actual.extras.getCharSequence("android.text")).equals(text)
                    && (actual.getSmallIcon().getResId() == icon);
            return matches;
          },
          tag);
    }
  }
}
