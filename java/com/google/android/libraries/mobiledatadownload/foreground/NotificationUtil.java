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
package com.google.android.libraries.mobiledatadownload.foreground;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationCompat.BigTextStyle;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

/** Utilities for creating and managing notifications. */
// TODO(b/148401016): Add UI test for NotificationUtil.
public final class NotificationUtil {
  public static final String CANCEL_ACTION_EXTRA = "cancel-action";
  public static final String KEY_EXTRA = "key";
  public static final String STOP_SERVICE_EXTRA = "stop-service";

  private NotificationUtil() {}

  public static final String NOTIFICATION_CHANNEL_ID = "download-notification-channel-id";

  /** Create the NotificationBuilder for the Foreground Download Service */
  public static NotificationCompat.Builder createForegroundServiceNotificationBuilder(
      Context context) {
    return getNotificationBuilder(context)
        .setContentTitle(
                "Downloading")
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim);
  }

  /** Create a Notification.Builder. */
  public static NotificationCompat.Builder createNotificationBuilder(
      Context context, int size, String contentTitle, String contentText) {
    return getNotificationBuilder(context)
        .setContentTitle(contentTitle)
        .setContentText(contentText)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setProgress(size, 0, false)
        .setStyle(new BigTextStyle().bigText(contentText));
  }

  private static NotificationCompat.Builder getNotificationBuilder(Context context) {
    return new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true);
  }

  /**
   * Create a Notification for a key.
   *
   * @param key Key to identify the download this notification is created for.
   */
  public static void cancelNotificationForKey(Context context, String key) {
    NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
    notificationManager.cancel(notificationKeyForKey(key));
  }

  /** Create the Cancel Menu Action which will be attach to the download notification. */
  // FLAG_IMMUTABLE is only for api >= 23, however framework still recommends to use this:
  // <internal>
  @SuppressLint("InlinedApi")
  public static void createCancelAction(
      Context context,
      Class<?> foregroundDownloadServiceClass,
      String key,
      NotificationCompat.Builder notification,
      int notificationKey) {
    SaferIntentUtils intentUtils = new SaferIntentUtils() {};

    Intent cancelIntent = new Intent(context, foregroundDownloadServiceClass);
    cancelIntent.setPackage(context.getPackageName());
    cancelIntent.putExtra(CANCEL_ACTION_EXTRA, notificationKey);
    cancelIntent.putExtra(KEY_EXTRA, key);

    // It should be safe since we are using SaferPendingIntent, setting Package and Component, and
    // use PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE.
    PendingIntent pendingCancelIntent;
    if (VERSION.SDK_INT >= VERSION_CODES.O) {
      pendingCancelIntent =
          intentUtils.getForegroundService(
              context,
              notificationKey,
              cancelIntent,
              PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    } else {
      pendingCancelIntent =
          intentUtils.getService(
              context,
              notificationKey,
              cancelIntent,
              PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    }
    NotificationCompat.Action action =
        new NotificationCompat.Action.Builder(
                android.R.drawable.stat_sys_warning,
                "Cancel",
                Preconditions.checkNotNull(pendingCancelIntent))
            .build();
    notification.addAction(action);
  }

  /** Generate the Notification Key for the Key */
  public static int notificationKeyForKey(String key) {
    // Consider if we could have collision.
    // Heavier alternative is Hashing.goodFastHash(32).hashUnencodedChars(key).asInt();
    return key.hashCode();
  }

  /** Send intent to start the DownloadService in foreground. */
  public static void startForegroundDownloadService(
      Context context, Class<?> foregroundDownloadService, String key) {
    Intent intent = new Intent(context, foregroundDownloadService);
    intent.putExtra(KEY_EXTRA, key);

    // Start ForegroundDownloadService to download in the foreground.
    ContextCompat.startForegroundService(context, intent);
  }

  /** Sending the intent to stop the foreground download service */
  public static void stopForegroundDownloadService(
      Context context, Class<?> foregroundDownloadService) {
    Intent intent = new Intent(context, foregroundDownloadService);
    intent.putExtra(STOP_SERVICE_EXTRA, true);

    // This will send the intent to stop the service.
    ContextCompat.startForegroundService(context, intent);
  }

  /**
   * Return the String message to display in Notification when the download is paused to wait for
   * network connection.
   */
  public static String getDownloadPausedMessage(Context context) {
    return "Waiting for network connection";
  }

  /** Return the String message to display in Notification when the download is failed. */
  public static String getDownloadFailedMessage(Context context) {
    return "Download failed";
  }

  /** Return the String message to display in Notification when the download is success. */
  public static String getDownloadSuccessMessage(Context context) {
    return "Downloaded";
  }

  /** Create the Notification Channel for Downloading. */
  public static void createNotificationChannel(Context context) {
    if (VERSION.SDK_INT >= VERSION_CODES.O) {
      NotificationChannel notificationChannel =
          new NotificationChannel(
              NOTIFICATION_CHANNEL_ID,
                  "Data Download Notification Channel",
              android.app.NotificationManager.IMPORTANCE_DEFAULT);

      android.app.NotificationManager manager =
          context.getSystemService(android.app.NotificationManager.class);
      manager.createNotificationChannel(notificationChannel);
    }
  }

  /** Utilities for safely accessing PendingIntent APIs. */
  private interface SaferIntentUtils {
    @Nullable
    @RequiresApi(VERSION_CODES.O) // to match PendingIntent.getForegroundService()
    default PendingIntent getForegroundService(
        Context context, int requestCode, Intent intent, int flags) {
      return PendingIntent.getForegroundService(context, requestCode, intent, flags);
    }

    @Nullable
    default PendingIntent getService(Context context, int requestCode, Intent intent, int flags) {
      return PendingIntent.getService(context, requestCode, intent, flags);
    }
  }
}
