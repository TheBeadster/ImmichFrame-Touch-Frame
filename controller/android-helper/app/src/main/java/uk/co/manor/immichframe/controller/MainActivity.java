package uk.co.manor.immichframe.controller;

import android.app.*;
import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.hardware.usb.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Deliberately local-only updater: a user selects the already-built UF2. */
public final class MainActivity extends Activity {
  static final int VID=0x2e8a, NORMAL=0x000f, BOOT=0x0003, PICK=42;
  static final String PERMISSION="uk.co.manor.immichframe.controller.USB_PERMISSION";
  static final String PENDING_FILE="pending-controller.uf2",PENDING_PREFS="pending-update",PENDING_TIME="selected-at",PENDING_HASH="sha256";
  UsbManager usb; TextView status; byte[] pending; String pendingHash; boolean updateOnly;
  volatile boolean postFlash; int normalWaitAttempts;
  final ExecutorService worker=Executors.newSingleThreadExecutor();
  final Handler ui=new Handler(Looper.getMainLooper());
  final Runnable returnTask=()->{clearPending();returnToFrame();};
  final BroadcastReceiver receiver=new BroadcastReceiver(){ public void onReceive(Context c, Intent i){
    UsbDevice d=i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    if (PERMISSION.equals(i.getAction()) && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false)) ready(d);
    if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) attached(d);
  }};
  public void onCreate(Bundle b){ super.onCreate(b); updateOnly=getIntent().getBooleanExtra("update_only",false); usb=(UsbManager)getSystemService(USB_SERVICE); restorePending(); LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(32,32,32,32); status=new TextView(this); status.setTextSize(18); Button choose=new Button(this); choose.setText("Select verified controller update"); choose.setOnClickListener(v->pick()); Button developer=new Button(this); developer.setText("Open Android developer options"); developer.setOnClickListener(v->openDeveloperSettings()); l.addView(status); l.addView(choose); l.addView(developer); setContentView(l); IntentFilter f=new IntentFilter(PERMISSION); f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); registerReceiver(receiver,f,Context.RECEIVER_EXPORTED); UsbDevice boot=bootsel(),normal=normal(); if(boot!=null)attached(boot);else if(normal!=null)attached(normal);else scheduleReturn(updateOnly?30000:2000); }
  void openDeveloperSettings(){try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}}
  protected void onDestroy(){ui.removeCallbacks(returnTask);try{unregisterReceiver(receiver);}catch(Exception ignored){}worker.shutdownNow();super.onDestroy();}
  void scheduleReturn(long delay){ui.removeCallbacks(returnTask);ui.postDelayed(returnTask,delay);}
  void returnToFrame(){Intent launch=getPackageManager().getLaunchIntentForPackage("com.immichframe.immichframe");if(launch!=null){launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(launch);}finish();}
  void savePending()throws Exception{File file=new File(getFilesDir(),PENDING_FILE);try(FileOutputStream out=new FileOutputStream(file)){out.write(pending);out.getFD().sync();}getSharedPreferences(PENDING_PREFS,MODE_PRIVATE).edit().putLong(PENDING_TIME,System.currentTimeMillis()).putString(PENDING_HASH,pendingHash).commit();}
  void restorePending(){try{File file=new File(getFilesDir(),PENDING_FILE);android.content.SharedPreferences prefs=getSharedPreferences(PENDING_PREFS,MODE_PRIVATE);long age=System.currentTimeMillis()-prefs.getLong(PENDING_TIME,0);if(!file.isFile()||age<0||age>300000){clearPending();return;}try(FileInputStream in=new FileInputStream(file)){pending=read(in);}pendingHash=sha(pending);if(pending.length<512||le(pending,0)!=0x0a324655||!pendingHash.equals(prefs.getString(PENDING_HASH,"")))clearPending();}catch(Exception error){clearPending();}}
  void clearPending(){pending=null;pendingHash=null;File file=new File(getFilesDir(),PENDING_FILE);if(file.exists())file.delete();getSharedPreferences(PENDING_PREFS,MODE_PRIVATE).edit().clear().apply();}
  void show(String s){ runOnUiThread(()->status.setText(s)); }
  boolean hasMsc(UsbDevice d){ for(int n=0;n<d.getInterfaceCount();n++)if(d.getInterface(n).getInterfaceClass()==UsbConstants.USB_CLASS_MASS_STORAGE)return true; return false; }
  UsbDevice normal(){ for(UsbDevice d:usb.getDeviceList().values()) if(d.getVendorId()==VID && d.getProductId()==NORMAL && !hasMsc(d)) return d; return null; }
  UsbDevice bootsel(){ for(UsbDevice d:usb.getDeviceList().values()) if(d.getVendorId()==VID && (d.getProductId()==BOOT || hasMsc(d))) return d; return null; }
  void attached(UsbDevice d){ if(d==null||d.getVendorId()!=VID)return; if(d.getProductId()==BOOT||hasMsc(d)){ if(pending==null){show("Controller recovery mode detected. Reset the controller if needed. Returning to ImmichFrame…");scheduleReturn(12000);}else if(!usb.hasPermission(d))usb.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(PERMISSION),PendingIntent.FLAG_IMMUTABLE));else{ui.removeCallbacks(returnTask);show("ROM bootloader found — writing verified update");worker.execute(()->flash(d));} } else { show(postFlash?"Controller updated — reconnecting live photo bridge":(updateOnly?"Controller connected — select the current UF2":"Controller connected — live photo bridge active")); if(!usb.hasPermission(d))usb.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(PERMISSION),PendingIntent.FLAG_IMMUTABLE));else if(postFlash){startBridge();show("Controller updated — live photo bridge active");scheduleReturn(2000);}else if(!updateOnly){startBridge();scheduleReturn(1500);}else scheduleReturn(90000); } }
  void ready(UsbDevice d){ if(d==null)return; if((d.getProductId()==BOOT||hasMsc(d))&&pending!=null){ui.removeCallbacks(returnTask);show("ROM bootloader permission granted — writing verified update");worker.execute(()->flash(d));}else if(d.getProductId()==BOOT||hasMsc(d)){show("No verified update selected. Returning to ImmichFrame…");scheduleReturn(5000);}else if(postFlash){startBridge();show("Controller updated — live photo bridge active");scheduleReturn(2000);}else{show(updateOnly?"Controller permission granted — select the current UF2":"Controller permission granted — live photo bridge active");if(!updateOnly){startBridge();scheduleReturn(1500);}else scheduleReturn(90000);} }
  void startBridge(){ startForegroundService(new Intent(this,ControllerBridgeService.class)); }
  void pick(){ ui.removeCallbacks(returnTask);startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE),PICK); }
  protected void onActivityResult(int r,int c,Intent i){ super.onActivityResult(r,c,i); if(r!=PICK)return;if(c!=RESULT_OK){clearPending();show("Update cancelled. Returning to ImmichFrame…");scheduleReturn(2000);return;} try(InputStream in=getContentResolver().openInputStream(i.getData())){ pending=read(in); if(pending.length<512||le(pending,0)!=0x0a324655)throw new IOException("Not an RP-series UF2"); pendingHash=sha(pending);savePending();show("Verified UF2 selected. Preparing controller…"); prepare(); }catch(Exception e){clearPending();show("Update rejected: "+e.getMessage()+". Returning to frame…");scheduleReturn(8000);} }
  void prepare(){ UsbDevice boot=bootsel(); if(boot!=null){ if(!usb.hasPermission(boot)){usb.requestPermission(boot,PendingIntent.getBroadcast(this,0,new Intent(PERMISSION),PendingIntent.FLAG_IMMUTABLE));return;} show("ROM bootloader ready — writing verified update"); worker.execute(()->flash(boot)); return;} UsbDevice d=normal(); if(d==null){show("Reconnect the controller first");scheduleReturn(12000);return;} if(!usb.hasPermission(d)){usb.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(PERMISSION),PendingIntent.FLAG_IMMUTABLE));return;} UsbDeviceConnection c=usb.openDevice(d); UsbInterface data=null; for(int n=0;n<d.getInterfaceCount();n++)if(d.getInterface(n).getInterfaceClass()==UsbConstants.USB_CLASS_CDC_DATA)data=d.getInterface(n); if(data==null){show("CDC data interface unavailable");scheduleReturn(12000);return;} UsbEndpoint out=null; for(int n=0;n<data.getEndpointCount();n++){UsbEndpoint e=data.getEndpoint(n);if(e.getDirection()==UsbConstants.USB_DIR_OUT)out=e;} if(out==null){show("CDC output unavailable");scheduleReturn(12000);return;} c.claimInterface(data,true); String m="{\"v\":1,\"type\":\"firmware_update_prepare\",\"id\":\"android-update\",\"confirm\":true,\"sha256\":\""+pendingHash+"\"}\n"; c.bulkTransfer(out,m.getBytes(),m.length(),2000); c.releaseInterface(data); c.close(); show("Controller entering ROM bootloader…"); scheduleReturn(90000); }
  void flash(UsbDevice d){ try{ stopService(new Intent(this,ControllerBridgeService.class)); if(!usb.hasPermission(d)){usb.requestPermission(d,PendingIntent.getBroadcast(this,0,new Intent(PERMISSION),PendingIntent.FLAG_IMMUTABLE));return;} new Bot(usb.openDevice(d),d).write(pending);clearPending();postFlash=true;normalWaitAttempts=0;show("Update written — waiting for normal controller");ui.postDelayed(this::finishFlash,500); }catch(Exception e){show("Update failed — reset the controller with RESET, or BOOT + RESET for recovery. Returning to frame…");scheduleReturn(12000);} }
  void finishFlash(){UsbDevice d=normal();if(d==null){if(++normalWaitAttempts<=60){ui.postDelayed(this::finishFlash,250);return;}show("Update written, but controller did not reconnect. Reset it, then reopen Controller Helper.");scheduleReturn(12000);return;}attached(d);}
  static byte[] read(InputStream in)throws IOException{ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];for(int n;(n=in.read(b))>0;)o.write(b,0,n);return o.toByteArray();}
  static String sha(byte[] b)throws Exception{byte[] h=MessageDigest.getInstance("SHA-256").digest(b);StringBuilder s=new StringBuilder();for(byte x:h)s.append(String.format("%02X",x));return s.toString();}
  static int le(byte[] b,int p){return ByteBuffer.wrap(b,p,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
  static final class Bot { final UsbDeviceConnection c; final UsbEndpoint in,out; int tag=1;
    Bot(UsbDeviceConnection c,UsbDevice d)throws IOException{this.c=c;UsbInterface i=null;for(int n=0;n<d.getInterfaceCount();n++)if(d.getInterface(n).getInterfaceClass()==UsbConstants.USB_CLASS_MASS_STORAGE)i=d.getInterface(n);if(i==null)throw new IOException("Mass-storage interface absent");if(!c.claimInterface(i,true))throw new IOException("Cannot claim ROM interface");UsbEndpoint a=null,b=null;for(int n=0;n<i.getEndpointCount();n++){UsbEndpoint e=i.getEndpoint(n);if(e.getDirection()==UsbConstants.USB_DIR_IN)a=e;else b=e;}if(a==null||b==null)throw new IOException("ROM bulk endpoints absent");in=a;out=b;}
    void write(byte[] uf2)throws IOException{
      for(int p=0;p<uf2.length;p+=512)writeBlock(128+p/512,Arrays.copyOfRange(uf2,p,p+512));
      // UF2 blocks are now verified by the ROM. Ask the USB mass-storage device
      // to flush and eject so the controller starts the newly written app.
      try{noData((byte)0x35,(byte)0x00);}catch(IOException ignored){}
      try{noData((byte)0x1b,(byte)0x02);}catch(IOException ignored){}
      c.close();
    }
    void writeBlock(int lba,byte[] data)throws IOException{byte[] cdb=new byte[10];cdb[0]=0x2a;ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN).putInt(2,lba).putShort(7,(short)1);cbw(cdb,512,false);xfer(out,data);csw();}
    void noData(byte operation,byte control)throws IOException{byte[] cdb=new byte[10];cdb[0]=operation;cdb[4]=control;cbw(cdb,0,false);csw();}
    void cbw(byte[] cdb,int len,boolean read)throws IOException{ByteBuffer b=ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN);b.putInt(0x43425355).putInt(tag++).putInt(len).put((byte)(read?0x80:0)).put((byte)0).put((byte)cdb.length).put(cdb);xfer(out,b.array());}
    void csw()throws IOException{byte[] b=new byte[13];xfer(in,b);if(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt()!=0x53425355||b[12]!=0)throw new IOException("ROM write rejected");}
    void xfer(UsbEndpoint e,byte[] b)throws IOException{if(c.bulkTransfer(e,b,b.length,5000)!=b.length)throw new IOException("USB bulk transfer failed");}
  }
}

