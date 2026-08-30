package uk.co.manor.immichframe.controller;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

/** Approves only this appliance's exact SystemUI USB permission dialog. */
public final class UsbPermissionAccessibilityService extends AccessibilityService {
  private static final String TAG="FrameUsbApproval";
  private static final String ACCESS_BODY="Allow Frame Controller Helper to access Pico 2?";
  private static final String OPEN_BODY="Open Frame Controller Helper to handle Pico 2?";
  private static final String BOOT_BODY="Allow Frame Controller Helper to access RP2350 Boot?";
  private final Handler handler=new Handler(Looper.getMainLooper());
  private static volatile UsbPermissionAccessibilityService instance;

  @Override protected void onServiceConnected(){
    super.onServiceConnected();
    instance=this;
    // The TV ROM can display the USB sheet before accessibility services bind.
    // Inspect the existing window briefly as well as reacting to future events.
    retryExistingPrompt(0);
  }

  static boolean sleepAppliance(){
    UsbPermissionAccessibilityService service=instance;
    return service!=null&&service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
  }

  @Override public void onAccessibilityEvent(AccessibilityEvent event){
    if(event==null||event.getPackageName()==null||!"com.android.systemui".contentEquals(event.getPackageName()))return;
    approveExactPicoPrompt();
  }

  private void retryExistingPrompt(int attempt){
    if(approveExactPicoPrompt()||attempt>=40)return;
    handler.postDelayed(()->retryExistingPrompt(attempt+1),250);
  }

  private boolean approveExactPicoPrompt(){
    AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return false;
    if(root.getPackageName()==null||!"com.android.systemui".contentEquals(root.getPackageName()))return false;
    List<AccessibilityNodeInfo> body=root.findAccessibilityNodeInfosByText(ACCESS_BODY);
    if(body==null||body.isEmpty())body=root.findAccessibilityNodeInfosByText(OPEN_BODY);
    if(body==null||body.isEmpty())body=root.findAccessibilityNodeInfosByText(BOOT_BODY);
    if(body==null||body.isEmpty())return false;
    List<AccessibilityNodeInfo> buttons=root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/bottom_sheet_positive_button");
    if(buttons==null)return false;
    for(AccessibilityNodeInfo button:buttons){
      if(button!=null&&button.isEnabled()&&button.isClickable()&&button.performAction(AccessibilityNodeInfo.ACTION_CLICK)){
        Log.i(TAG,"Approved dedicated Pico 2 USB permission");return true;
      }
    }
    return false;
  }
  @Override public void onDestroy(){if(instance==this)instance=null;handler.removeCallbacksAndMessages(null);super.onDestroy();}
  @Override public void onInterrupt(){}
}

