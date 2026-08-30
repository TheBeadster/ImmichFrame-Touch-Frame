package uk.co.manor.immichframe.controller;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import java.util.LinkedHashSet;

/** Restores this appliance's exact accessibility service after the TV ROM resets the list at boot. */
final class ApplianceAccessibility {
  private static final String TAG="FrameAccessibility";

  static boolean ensureEnabled(Context context) {
    if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
      Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; cannot restore USB approver");
      return false;
    }
    String current = Settings.Secure.getString(
        context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    LinkedHashSet<String> services = new LinkedHashSet<>();
    if (current != null && !current.isEmpty()) {
      for (String service : current.split(":")) if (!service.isEmpty()) services.add(service);
    }
    String component = new ComponentName(context, UsbPermissionAccessibilityService.class).flattenToString();
    if (!services.add(component)) return true;
    boolean written = Settings.Secure.putString(
        context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        TextUtils.join(":", services));
    if (written) {
      Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
      Log.i(TAG, "Restored exact USB accessibility approver after ROM reset");
    }
    return written;
  }

  private ApplianceAccessibility() {}
}

