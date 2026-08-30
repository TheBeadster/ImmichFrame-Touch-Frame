package uk.co.manor.immichframe.controller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/** Marks a boot for HomeRouterActivity. Android does not permit a receiver to
 * reliably put an activity in front of the current launcher by itself. */
public final class FrameBootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    String action = intent.getAction();
    if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action) && !Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
    ApplianceAccessibility.ensureEnabled(context);
    if (!Intent.ACTION_BOOT_COMPLETED.equals(action)) return;
    context.getSharedPreferences(HomeRouterActivity.PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean(HomeRouterActivity.BOOT_PENDING, true).apply();
    context.startForegroundService(new Intent(context, ControllerBridgeService.class));
  }
}

