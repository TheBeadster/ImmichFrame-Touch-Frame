package uk.co.manor.immichframe.controller;

import android.app.*;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.graphics.*;
import android.hardware.usb.*;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Streams the composer's exact three live assets to the RP2350 over USB CDC. */
public final class ControllerBridgeService extends Service {
  private static final String TAG="FrameControllerBridge";
  private static final int VID=0x2e8a, PID=0x000f, TW=84, TH=103;
  // Change this to the LAN address of the Docker host before building the APK.
  private static final String BASE="http://192.168.1.50:8081";
  private static final String MESSENGER="http://127.0.0.1:8090";
  private static final String IMMICH_FRAME="com.immichframe.immichframe";
  private static final String LEANBACK="com.rockchips.android.leanbacklauncher";
  private static final String USB_PERMISSION="uk.co.manor.immichframe.controller.BRIDGE_USB_PERMISSION";
  private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
  private final ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService powerWorker=Executors.newSingleThreadExecutor();
  private final Object usbIoLock=new Object();
  private String lastUpdate="";
  private String receiveBuffer="";
  private long lastPauseToggleAt=0;
  private long lastSendAt=0;
  private int lastSendSlot=-1;
  private String lastSendAssetId="";
  private String lastSendStatus="failed",lastSendMessageId="";
  private JSONArray lastAssets=new JSONArray();
  private final LinkedHashMap<String,JSONObject> assetCache=new LinkedHashMap<>();
  private boolean settingsSent=false;
  private UsbDeviceConnection usbConnection;
  private UsbInterface usbData,usbControl;
  private UsbEndpoint usbOut,usbIn;
  private long launcherSeenAt=0,lastFrameRelaunchAt=0;
  private long usbPermissionRequestedAt=0;
  private long lastControllerWakeAssertAt=0;
  private boolean usbPermissionPending=false;
  private WindowManager windowManager;
  private View recoveryOverlay;
  private volatile boolean standbyRequested=false;
  private volatile boolean lastFramePaused=false;
  private volatile boolean resumeAfterStandby=false;
  private MqttPowerClient mqttPower;
  private final BroadcastReceiver usbReceiver=new BroadcastReceiver(){
    @Override public void onReceive(Context context,Intent intent){
      if(USB_PERMISSION.equals(intent.getAction())){
        usbPermissionPending=false;
        if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false))Log.i(TAG,"Controller USB permission granted");
        else Log.w(TAG,"Controller USB permission denied");
      }else if(UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())){usbPermissionPending=false;closeUsb();}
    }
  };

  @Override public void onCreate(){ super.onCreate();
    standbyRequested=!getSharedPreferences("appliance",MODE_PRIVATE).getBoolean("desired_on",true);
    resumeAfterStandby=getSharedPreferences("appliance",MODE_PRIVATE).getBoolean("resume_after_standby",false);
    ApplianceAccessibility.ensureEnabled(this);
    NotificationManager manager=getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("bridge","Frame controller",NotificationManager.IMPORTANCE_LOW));
    Notification notification=new Notification.Builder(this,"bridge").setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Frame controller connected").setContentText("Synchronising displayed photographs").build();
    startForeground(71,notification);
    IntentFilter usbFilter=new IntentFilter(USB_PERMISSION);
    usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    registerReceiver(usbReceiver,usbFilter,Context.RECEIVER_EXPORTED);
    startRecoveryOverlay();
    worker.scheduleWithFixedDelay(this::syncSafely,0,100,TimeUnit.MILLISECONDS);
    watchdog.scheduleWithFixedDelay(this::watchFrameSafely,5,2,TimeUnit.SECONDS);
    mqttPower=new MqttPowerClient(new MqttPowerClient.Listener(){
      @Override public void onPowerCommand(boolean on){requestPower(on);}
      @Override public String currentPowerState(){return standbyRequested?"OFF":"ON";}
    });
    mqttPower.start();
    if(standbyRequested)powerWorker.execute(()->applyPower(false,false));
  }
  @Override public void onDestroy(){if(mqttPower!=null)mqttPower.stop();worker.shutdownNow();watchdog.shutdownNow();powerWorker.shutdownNow();stopRecoveryOverlay();synchronized(usbIoLock){closeUsb();}try{unregisterReceiver(usbReceiver);}catch(Exception ignored){}super.onDestroy();}
  @Override public IBinder onBind(Intent intent){ return null; }
  @Override public int onStartCommand(Intent intent,int flags,int id){ return START_STICKY; }

  private void syncSafely(){if(standbyRequested)return;synchronized(usbIoLock){try{
    enforcePortrait();
    // The controller can miss the one-shot wake command while Android's USB
    // host is resuming. Reassert ON periodically so the two displays converge
    // without user intervention; firmware treats this command idempotently.
    long now=android.os.SystemClock.elapsedRealtime();
    if(now-lastControllerWakeAssertAt>=5000){lastControllerWakeAssertAt=now;sendControllerDisplayPower(true);}
    sync();
  }catch(Exception error){Log.e(TAG,"Bridge cycle failed",error);closeUsb();}}}
  private void enforcePortrait(){
    if(Settings.System.canWrite(this)){
      Settings.System.putInt(getContentResolver(),Settings.System.ACCELEROMETER_ROTATION,0);
      Settings.System.putInt(getContentResolver(),Settings.System.USER_ROTATION,1);
    }
  }

  /**
   * ImmichFrame 1.0.50 is killed when the Chromium renderer exits without the
   * WebView host handling onRenderProcessGone().  On this dedicated appliance
   * that exposes the vendor launcher.  Detect that exact fallback and relaunch
   * the frame; do not interfere with Settings or other maintenance screens.
   */
  private void watchFrameSafely(){
    try{
      // This ROM and its vendor accessibility component can overwrite the
      // enabled-service list after boot. Preserve their entries and restore
      // only this appliance's exact USB approver when it disappears.
      ApplianceAccessibility.ensureEnabled(this);
      if(standbyRequested){launcherSeenAt=0;return;}
      String foreground=foregroundPackage();
      long now=android.os.SystemClock.elapsedRealtime();
      if(!LEANBACK.equals(foreground)){launcherSeenAt=0;return;}
      if(launcherSeenAt==0){launcherSeenAt=now;return;}
      if(now-launcherSeenAt<3000||now-lastFrameRelaunchAt<15000)return;
      Intent launch=getPackageManager().getLaunchIntentForPackage(IMMICH_FRAME);
      if(launch==null){Log.e(TAG,"ImmichFrame launch activity is unavailable");return;}
      launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
      startActivity(launch);
      lastFrameRelaunchAt=now;launcherSeenAt=0;
      Log.w(TAG,"Relaunched ImmichFrame after launcher fallback");
    }catch(Exception error){Log.e(TAG,"ImmichFrame watchdog failed",error);}
  }

  private void requestPower(boolean on){
    boolean alreadyOn=!standbyRequested;
    if(on==alreadyOn){
      // A repeated desired-state ON is also a recovery request. Android may
      // already be awake while the independently powered USB LCD is still off.
      if(on)powerWorker.execute(()->applyPower(true,true));
      else if(mqttPower!=null)mqttPower.publishState("OFF");
      return;
    }
    standbyRequested=!on;
    if(mqttPower!=null)mqttPower.publishState("STARTING");
    powerWorker.execute(()->applyPower(on,true));
  }

  private void applyPower(boolean on,boolean publishResult){
    try{
      if(on){
        getSharedPreferences("appliance",MODE_PRIVATE).edit().putBoolean("desired_on",true).apply();
        wakeAndroid();
        Thread.sleep(700);
        launchFrame();
        if(resumeAfterStandby){postAction("resume",null);resumeAfterStandby=false;}
        synchronized(usbIoLock){
          sendControllerDisplayPower(true);
          lastControllerWakeAssertAt=android.os.SystemClock.elapsedRealtime();
        }
        getSharedPreferences("appliance",MODE_PRIVATE).edit().putBoolean("resume_after_standby",false).apply();
        standbyRequested=false;
        Log.i(TAG,"MQTT appliance power state is ON");
      }else{
        standbyRequested=true;
        getSharedPreferences("appliance",MODE_PRIVATE).edit().putBoolean("desired_on",false).apply();
        resumeAfterStandby=!lastFramePaused;
        getSharedPreferences("appliance",MODE_PRIVATE).edit().putBoolean("resume_after_standby",resumeAfterStandby).apply();
        if(resumeAfterStandby)postAction("pause",null);
        synchronized(usbIoLock){sendControllerDisplayPower(false);}
        Thread.sleep(350);
        sleepAndroid();
        Log.i(TAG,"MQTT appliance power state is OFF");
      }
      if(publishResult&&mqttPower!=null)mqttPower.publishState(on?"ON":"OFF");
    }catch(Exception error){
      Log.e(TAG,"MQTT appliance power transition failed",error);
      if(mqttPower!=null)mqttPower.publishState("ERROR");
    }
  }

  private void sendControllerDisplayPower(boolean on)throws Exception{
    if(!ensureUsb())throw new IOException("Controller USB is unavailable");
    write(usbConnection,usbOut,new JSONObject().put("v",1).put("type","display_power").put("on",on).toString()+"\n");
  }

  private void sleepAndroid()throws Exception{
    if(!UsbPermissionAccessibilityService.sleepAppliance())throw new IOException("Accessibility sleep action is unavailable");
  }

  private void wakeAndroid()throws Exception{
    PowerManager manager=(PowerManager)getSystemService(POWER_SERVICE);
    PowerManager.WakeLock wake=manager.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK|PowerManager.ACQUIRE_CAUSES_WAKEUP,"immichframe:MQTTWake");
    wake.acquire(5000);
  }

  private void launchFrame()throws IOException{
    Intent launch=getPackageManager().getLaunchIntentForPackage(IMMICH_FRAME);
    if(launch==null)throw new IOException("ImmichFrame launch activity is unavailable");
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(launch);
  }

  private String foregroundPackage(){
    UsageStatsManager manager=(UsageStatsManager)getSystemService(USAGE_STATS_SERVICE);
    long end=System.currentTimeMillis(),begin=end-30000;
    UsageEvents events=manager.queryEvents(begin,end);
    UsageEvents.Event event=new UsageEvents.Event();
    String foreground="";
    while(events.hasNextEvent()){
      events.getNextEvent(event);
      int type=event.getEventType();
      if(type==UsageEvents.Event.ACTIVITY_RESUMED||type==UsageEvents.Event.MOVE_TO_FOREGROUND)
        foreground=event.getPackageName();
    }
    return foreground;
  }

  private void startRecoveryOverlay(){
    if(!Settings.canDrawOverlays(this)){Log.w(TAG,"Kiosk recovery overlay permission is unavailable");return;}
    windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
    recoveryOverlay=new View(this);
    WindowManager.LayoutParams layout=new WindowManager.LayoutParams(1,1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT);
    layout.alpha=0.01f;
    windowManager.addView(recoveryOverlay,layout);
    Log.i(TAG,"Kiosk recovery overlay active");
  }

  private void stopRecoveryOverlay(){
    if(windowManager!=null&&recoveryOverlay!=null){try{windowManager.removeView(recoveryOverlay);}catch(Exception ignored){}}
    recoveryOverlay=null;windowManager=null;
  }
  private void sync() throws Exception {
    if(!ensureUsb())return;
    readActions(usbConnection,usbIn);
    JSONObject state=json(BASE+"/api/controller/state");
    if(state==null||state==JSONObject.NULL)return;
    String updated=state.optString("updated_at");
    boolean changed=!updated.isEmpty()&&!updated.equals(lastUpdate);
    JSONArray assets=state.optJSONArray("assets");
    if(assets==null)return;
    lastFramePaused=state.optBoolean("paused",false);
    lastAssets=new JSONArray(assets.toString());
    for(int slot=0;slot<assets.length();slot++)remember(assets.getJSONObject(slot));
    if(!settingsSent){
      write(usbConnection,usbOut,new JSONObject().put("v",1).put("type","settings").put("admin","Home").put("provider","Reticulum / LXMF").toString()+"\n");
      settingsSent=true;
    }
    if(changed){
      write(usbConnection,usbOut,new JSONObject().put("v",1).put("type","thumbnail_batch_begin").toString()+"\n");
      for(int slot=0;slot<Math.min(3,assets.length());slot++)sendAsset(usbConnection,usbOut,slot,assets.getJSONObject(slot));
      write(usbConnection,usbOut,new JSONObject().put("v",1).put("type","thumbnail_batch_end").toString()+"\n");
      JSONObject frame=new JSONObject().put("v",1).put("type","frame_state").put("paused",state.optBoolean("paused",false));
      write(usbConnection,usbOut,frame.toString()+"\n"); lastUpdate=updated; Log.i(TAG,"Sent "+Math.min(3,assets.length())+" live photo tiles");
    }
  }

  private void remember(JSONObject asset)throws JSONException{
    String id=asset.optString("id");if(id.isEmpty())return;
    assetCache.put(id,new JSONObject(asset.toString()));
    while(assetCache.size()>30)assetCache.remove(assetCache.keySet().iterator().next());
  }

  private JSONObject exactAsset(String assetId,int slot)throws JSONException,IOException{
    if(!assetId.isEmpty()){
      JSONObject cached=assetCache.get(assetId);if(cached!=null)return new JSONObject(cached.toString());
      for(int n=0;n<lastAssets.length();n++){JSONObject candidate=lastAssets.getJSONObject(n);if(assetId.equals(candidate.optString("id")))return new JSONObject(candidate.toString());}
      throw new IOException("Selected photo snapshot is no longer available");
    }
    if(slot<0||slot>=lastAssets.length())throw new IOException("Photo slot is unavailable");
    return new JSONObject(lastAssets.getJSONObject(slot).toString());
  }

  private void sendAsset(UsbDeviceConnection connection,UsbEndpoint out,int slot,JSONObject asset)throws Exception{
    String people=asset.optString("people"); String location=asset.optString("location");
    JSONObject metadata=new JSONObject().put("v",1).put("type","photo").put("slot",slot)
        .put("asset_id",asset.optString("id"))
        .put("filename",asset.optString("filename","Photo"))
        .put("details",asset.optString("taken_at",""))
        .put("year",asset.optString("year","----"))
        .put("people",people.isEmpty()?"No people tagged":people)
        .put("location",location.isEmpty()?"No location recorded":location);
    write(connection,out,metadata.toString()+"\n");
    Bitmap tile=tile(BASE+asset.getString("image_url"));
    write(connection,out,new JSONObject().put("v",1).put("type","thumbnail_begin").put("slot",slot).put("w",TW).put("h",TH).toString()+"\n");
    write(connection,out,new JSONObject().put("v",1).put("type","thumbnail_binary").put("slot",slot).put("pixels",TW*TH).toString()+"\n");
    byte[] pixels=new byte[TW*TH*2]; int offset=0;
    for(int y=0;y<TH;y++)for(int x=0;x<TW;x++){
      int colour=tile.getPixel(x,y); int rgb565=((Color.red(colour)>>3)<<11)|((Color.green(colour)>>2)<<5)|(Color.blue(colour)>>3);
      pixels[offset++]=(byte)(rgb565>>8); pixels[offset++]=(byte)rgb565;
    }
    writeBytes(connection,out,pixels); tile.recycle(); write(connection,out,new JSONObject().put("v",1).put("type","thumbnail_end").put("slot",slot).toString()+"\n");
  }

  private UsbDevice controller(){ UsbManager manager=(UsbManager)getSystemService(USB_SERVICE); for(UsbDevice device:manager.getDeviceList().values())if(device.getVendorId()==VID&&device.getProductId()==PID)return device;return null; }
  private boolean ensureUsb(){
    if(usbConnection!=null)return true;
    UsbDevice device=controller(); if(device==null)return false;
    UsbManager manager=(UsbManager)getSystemService(USB_SERVICE);
    if(!manager.hasPermission(device)){
      long now=android.os.SystemClock.elapsedRealtime();
      // This TV ROM can remove or background its USB sheet without returning
      // a permission result. Do not leave the bridge permanently wedged in a
      // stale pending state; retry so the scoped accessibility approver can
      // handle the next exact Pico prompt.
      if(usbPermissionPending&&now-usbPermissionRequestedAt>10000)usbPermissionPending=false;
      if(!usbPermissionPending&&now-usbPermissionRequestedAt>10000){
        Intent permission=new Intent(USB_PERMISSION).setPackage(getPackageName());
        // UsbManager adds the permission result extras, so this explicit,
        // package-scoped PendingIntent must remain mutable.
        PendingIntent reply=PendingIntent.getBroadcast(this,72,permission,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
        manager.requestPermission(device,reply);usbPermissionRequestedAt=now;usbPermissionPending=true;
        Log.i(TAG,"Requested controller USB permission");
      }
      return false;
    }
    for(int n=0;n<device.getInterfaceCount();n++){
      UsbInterface candidate=device.getInterface(n);
      if(candidate.getInterfaceClass()==UsbConstants.USB_CLASS_CDC_DATA)usbData=candidate;
      else if(candidate.getInterfaceClass()==UsbConstants.USB_CLASS_COMM)usbControl=candidate;
    }
    if(usbData==null||usbControl==null)return false;
    usbConnection=manager.openDevice(device); if(usbConnection==null)return false;
    if(!usbConnection.claimInterface(usbControl,true)||!usbConnection.claimInterface(usbData,true)){closeUsb();return false;}
    for(int n=0;n<usbData.getEndpointCount();n++){UsbEndpoint endpoint=usbData.getEndpoint(n);if(endpoint.getDirection()==UsbConstants.USB_DIR_OUT)usbOut=endpoint;else usbIn=endpoint;}
    if(usbOut==null||usbIn==null){closeUsb();return false;}
    byte[] lineCoding={(byte)0x00,(byte)0xC2,(byte)0x01,(byte)0x00,0,0,8};
    usbConnection.controlTransfer(0x21,0x20,0,usbControl.getId(),lineCoding,lineCoding.length,1000);
    usbConnection.controlTransfer(0x21,0x22,3,usbControl.getId(),null,0,1000);
    Log.i(TAG,"Persistent CDC session opened"); return true;
  }
  private void closeUsb(){
    if(usbConnection!=null){try{usbConnection.controlTransfer(0x21,0x22,0,usbControl==null?0:usbControl.getId(),null,0,500);}catch(Exception ignored){}try{if(usbData!=null)usbConnection.releaseInterface(usbData);}catch(Exception ignored){}try{if(usbControl!=null)usbConnection.releaseInterface(usbControl);}catch(Exception ignored){}usbConnection.close();}
    usbConnection=null;usbData=null;usbControl=null;usbOut=null;usbIn=null;receiveBuffer="";settingsSent=false;
  }
  private void readActions(UsbDeviceConnection connection,UsbEndpoint endpoint)throws Exception{
    byte[] buffer=new byte[1024]; int count=connection.bulkTransfer(endpoint,buffer,buffer.length,25);
    if(count<=0)return;
    receiveBuffer+=new String(buffer,0,count,StandardCharsets.UTF_8);
    if(receiveBuffer.length()>4096)receiveBuffer=receiveBuffer.substring(receiveBuffer.length()-4096);
    int newline;
    while((newline=receiveBuffer.indexOf('\n'))>=0){String line=receiveBuffer.substring(0,newline).trim();receiveBuffer=receiveBuffer.substring(newline+1);if(line.isEmpty())continue;JSONObject message=new JSONObject(line);if(!"action".equals(message.optString("type")))continue;String name=message.optString("name");if(!Arrays.asList("previous","next","toggle_paused","pause","resume","show_map","map_up","map_down","map_left","map_right","map_zoom_in","map_zoom_out","close_map","block_image","block_folder","send_photo").contains(name))continue;long now=android.os.SystemClock.elapsedRealtime();if("toggle_paused".equals(name)&&now-lastPauseToggleAt<7000){Log.i(TAG,"Ignored duplicate pause touch");continue;}if("toggle_paused".equals(name))lastPauseToggleAt=now;JSONObject args=message.optJSONObject("args");Integer slot=args!=null&&args.has("slot")?args.optInt("slot") : null;String assetId=args==null?"":args.optString("asset_id");if("send_photo".equals(name)){sendPhoto(slot==null?0:slot,assetId);}else{postAction(name,slot);Log.i(TAG,"Forwarded controller action "+name+(assetId.isEmpty()?"":" for asset "+assetId));}}
  }
  private void sendPhoto(int slot,String assetId)throws Exception{
    long now=android.os.SystemClock.elapsedRealtime();
    boolean same=!assetId.isEmpty()?assetId.equals(lastSendAssetId):slot==lastSendSlot;
    if(same&&("queued".equals(lastSendStatus)||now-lastSendAt<3000)){
      JSONObject duplicate=new JSONObject().put("v",1).put("type","send_result").put("status",lastSendStatus).put("duplicate",true);
      if(!lastSendMessageId.isEmpty())duplicate.put("message_id",lastSendMessageId);
      write(usbConnection,usbOut,duplicate.toString()+"\n");
      Log.i(TAG,"Suppressed duplicate photo send"); return;
    }
    JSONObject result=new JSONObject().put("v",1).put("type","send_result");
    try{
      JSONObject asset=exactAsset(assetId,slot);
      JSONObject request=new JSONObject()
          .put("asset_id",asset.optString("id"))
          .put("filename",asset.optString("filename","Photo"))
          .put("taken_at",asset.optString("taken_at"))
          .put("people",asset.optString("people"))
          .put("location",asset.optString("location"));
      JSONObject response=postJson(MESSENGER+"/send",request);
      String messageId=response.optString("id");
      lastSendAt=now;lastSendSlot=slot;lastSendAssetId=asset.optString("id");lastSendStatus="queued";lastSendMessageId=messageId;
      result.put("status","queued");
      if(!messageId.isEmpty())result.put("message_id",messageId);
      write(usbConnection,usbOut,result.toString()+"\n");
      Log.i(TAG,"Queued photo through Reticulum");
      if(!messageId.isEmpty()){
        long deadline=android.os.SystemClock.elapsedRealtime()+60000;
        while(android.os.SystemClock.elapsedRealtime()<deadline){
          Thread.sleep(1500);
          JSONObject delivery=json(MESSENGER+"/messages/"+messageId);
          String status=delivery==null?"":delivery.optString("status");
          if("sent".equals(status)||"failed".equals(status)){
            lastSendStatus=status;lastSendAt=android.os.SystemClock.elapsedRealtime();
            JSONObject finalResult=new JSONObject().put("v",1).put("type","send_result").put("status",status).put("message_id",messageId);
            if("failed".equals(status))finalResult.put("error",delivery.optString("error","LXMF delivery failed"));
            write(usbConnection,usbOut,finalResult.toString()+"\n");
            Log.i(TAG,"Photo delivery result: "+status); return;
          }
        }
        lastSendStatus="failed";lastSendAt=android.os.SystemClock.elapsedRealtime();
        write(usbConnection,usbOut,new JSONObject().put("v",1).put("type","send_result").put("status","failed").put("message_id",messageId).put("error","Delivery confirmation timed out").toString()+"\n");
        return;
      }
      return;
    }catch(Exception error){
      lastSendAt=android.os.SystemClock.elapsedRealtime();lastSendSlot=slot;lastSendAssetId=assetId;lastSendStatus="failed";
      result.put("status","failed").put("error",safeError(error));
      Log.e(TAG,"Photo send failed",error);
    }
    write(usbConnection,usbOut,result.toString()+"\n");
  }
  private static void postAction(String name,Integer slot)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(BASE+"/api/controller/actions").openConnection();connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("content-type","application/json");JSONObject action=new JSONObject().put("name",name);if(slot!=null)action.put("slot",slot);byte[] body=action.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream output=connection.getOutputStream()){output.write(body);}if(connection.getResponseCode()!=200)throw new IOException("Action rejected");connection.disconnect();}
  private static JSONObject postJson(String address,JSONObject value)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();connection.setRequestMethod("POST");connection.setConnectTimeout(3000);connection.setReadTimeout(10000);connection.setDoOutput(true);connection.setRequestProperty("content-type","application/json");byte[] body=value.toString().getBytes(StandardCharsets.UTF_8);try(OutputStream output=connection.getOutputStream()){output.write(body);}int status=connection.getResponseCode();InputStream input=status<400?connection.getInputStream():connection.getErrorStream();String text=input==null?"{}":new String(readAll(input),StandardCharsets.UTF_8);connection.disconnect();JSONObject response=new JSONObject(text);if(status>=400)throw new IOException(response.optString("error","Messenger rejected request"));return response;}
  private static String safeError(Exception error){String value=error.getMessage();if(value==null||value.trim().isEmpty())value=error.getClass().getSimpleName();return value.length()>160?value.substring(0,160):value;}
  private static void write(UsbDeviceConnection connection,UsbEndpoint endpoint,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);if(connection.bulkTransfer(endpoint,bytes,bytes.length,5000)!=bytes.length)throw new IOException("USB write failed");}
  private static void writeBytes(UsbDeviceConnection connection,UsbEndpoint endpoint,byte[] bytes)throws IOException{int offset=0;while(offset<bytes.length){int length=Math.min(4096,bytes.length-offset);int sent=connection.bulkTransfer(endpoint,bytes,offset,length,5000);if(sent<=0)throw new IOException("USB image write failed");offset+=sent;}}
  private static JSONObject json(String address)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(10000);try(InputStream input=connection.getInputStream()){String text=new String(readAll(input),StandardCharsets.UTF_8);return text.equals("null")?null:new JSONObject(text);}finally{connection.disconnect();}}
  private static Bitmap tile(String address)throws Exception{HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(20000);Bitmap source;try(InputStream input=connection.getInputStream()){source=BitmapFactory.decodeStream(input);}finally{connection.disconnect();}if(source==null)throw new IOException("Image decode failed");float scale=Math.max((float)TW/source.getWidth(),(float)TH/source.getHeight());int width=Math.max(TW,Math.round(source.getWidth()*scale)),height=Math.max(TH,Math.round(source.getHeight()*scale));Bitmap scaled=Bitmap.createScaledBitmap(source,width,height,true);if(scaled!=source)source.recycle();Bitmap result=Bitmap.createBitmap(scaled,(width-TW)/2,(height-TH)/2,TW,TH);if(result!=scaled)scaled.recycle();return result;}
  private static byte[] readAll(InputStream input)throws IOException{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))>0;)output.write(buffer,0,count);return output.toByteArray();}
}

