package uk.co.manor.immichframe.controller;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible HOME activity for the dedicated appliance. Boot and ordinary HOME
 * presses both return to ImmichFrame; maintenance apps can still be opened
 * explicitly through ADB or Android Settings.
 */
public final class HomeRouterActivity extends Activity {
  static final String PREFS = "home-router";
  static final String BOOT_PENDING = "boot-pending";
  private static final String IMMICH_FRAME = "com.immichframe.immichframe";
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    ApplianceAccessibility.ensureEnabled(this);
    startForegroundService(new Intent(this, ControllerBridgeService.class));
    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(BOOT_PENDING).apply();
    Intent launch = getPackageManager().getLaunchIntentForPackage(IMMICH_FRAME);
    if (launch != null) {
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
      startActivity(launch);
    }
    finish();
  }
}

