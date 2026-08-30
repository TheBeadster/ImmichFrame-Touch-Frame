package uk.co.manor.immichframe.controller;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** LAN-only MQTT link from Mosquitto to the dedicated frame appliance. */
final class MqttPowerClient implements MqttCallbackExtended {
  interface Listener {
    void onPowerCommand(boolean on);
    String currentPowerState();
  }

  static final String COMMAND_TOPIC="immichframe/frame/power/set";
  static final String STATE_TOPIC="immichframe/frame/power/state";
  static final String AVAILABILITY_TOPIC="immichframe/frame/availability";
  private static final String TAG="FrameMqttPower";
  // Change this to your LAN Mosquitto broker before building the APK.
  private static final String BROKER="tcp://192.168.1.10:1883";
  private final Listener listener;
  private MqttAsyncClient client;

  MqttPowerClient(Listener listener){this.listener=listener;}

  void start(){
    try{
      client=new MqttAsyncClient(BROKER,"x88-immichframe",new MemoryPersistence());
      client.setCallback(this);
      MqttConnectOptions options=new MqttConnectOptions();
      options.setAutomaticReconnect(true);
      options.setCleanSession(false);
      options.setConnectionTimeout(10);
      options.setKeepAliveInterval(30);
      options.setMaxInflight(10);
      options.setWill(AVAILABILITY_TOPIC,"offline".getBytes(StandardCharsets.UTF_8),1,true);
      client.connect(options,null,new IMqttActionListener(){
        @Override public void onSuccess(IMqttToken token){Log.i(TAG,"Connected to Mosquitto");}
        @Override public void onFailure(IMqttToken token,Throwable error){Log.e(TAG,"Initial MQTT connection failed",error);}
      });
    }catch(Exception error){Log.e(TAG,"MQTT startup failed",error);}
  }

  void stop(){
    try{
      if(client!=null&&client.isConnected()){
        publish(AVAILABILITY_TOPIC,"offline",true);
        client.disconnect(1500).waitForCompletion(2000);
      }
      if(client!=null)client.close();
    }catch(Exception error){Log.w(TAG,"MQTT shutdown was incomplete",error);}
  }

  void publishState(String state){publish(STATE_TOPIC,state,true);}

  private void publish(String topic,String value,boolean retained){
    try{
      if(client==null||!client.isConnected())return;
      MqttMessage message=new MqttMessage(value.getBytes(StandardCharsets.UTF_8));
      message.setQos(1);message.setRetained(retained);client.publish(topic,message);
    }catch(Exception error){Log.e(TAG,"MQTT publish failed for "+topic,error);}
  }

  @Override public void connectComplete(boolean reconnect,String serverURI){
    try{
      client.subscribe(COMMAND_TOPIC,1);
      publish(AVAILABILITY_TOPIC,"online",true);
      publishState(listener.currentPowerState());
      Log.i(TAG,(reconnect?"Reconnected":"Connected")+" and subscribed to "+COMMAND_TOPIC);
    }catch(Exception error){Log.e(TAG,"MQTT subscription failed",error);}
  }

  @Override public void connectionLost(Throwable cause){Log.w(TAG,"MQTT connection lost; automatic reconnect enabled",cause);}

  @Override public void messageArrived(String topic,MqttMessage message){
    if(!COMMAND_TOPIC.equals(topic))return;
    String value=new String(message.getPayload(),StandardCharsets.UTF_8).trim().toUpperCase(java.util.Locale.ROOT);
    if("ON".equals(value))listener.onPowerCommand(true);
    else if("OFF".equals(value))listener.onPowerCommand(false);
    else{
      Log.w(TAG,"Rejected unsupported power payload: "+value);
      publishState("ERROR");
    }
  }

  @Override public void deliveryComplete(IMqttDeliveryToken token){}
}

