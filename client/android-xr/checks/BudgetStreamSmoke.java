import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import dev.floatdesk.client.*;
import dev.floatdesk.client.media.*;
import dev.floatdesk.client.net.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;

/** Exercise the real worker's baseline/native-AVC/baseline protocol and GPU handoff. */
public final class BudgetStreamSmoke {
 static void await(ImageReader reader,boolean nativeFrame){
  long deadline=SystemClock.uptimeMillis()+4000;
  while(SystemClock.uptimeMillis()<deadline){try(var image=reader.acquireLatestImage()){
   if(image!=null){int[] top=ViewStreamSmoke.pixel(image,32,12),bottom=ViewStreamSmoke.pixel(image,32,42);
    if(nativeFrame?top[0]>180&&top[1]<50&&bottom[2]>180&&bottom[1]<50:top[1]>180&&top[0]<50&&bottom[1]>180)return;
   }
  }SystemClock.sleep(10);}throw new AssertionError("Budget protocol handoff failed native="+nativeFrame);
 }
 public static void main(String[] args)throws Exception{
  var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var phase=new AtomicInteger();var done=new AtomicBoolean();var failure=new AtomicReference<Throwable>();var fallbacks=new AtomicInteger();
  var reader=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var video=new VideoCompositor(64,48);
  video.detailDebug(new DetailDiagnostics(true,new FocusRect(0,0,1,1),"budget","",1f,1f,false));video.output(reader.getSurface());
  var serving=new Thread(()->{
   try(var socket=server.accept()){
    var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());long frame=0;
    while(!done.get()){
     int size=input.readInt();byte[] bytes=new byte[size];input.readFully(bytes);
     var request=new org.json.JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
     if(!request.optBoolean("frameBudget"))throw new AssertionError("Client failed to negotiate normal frame budget");
     var header=new org.json.JSONObject().put("frame",++frame);boolean nativeFrame=phase.get()==1;
     if(nativeFrame){
      header.put("type","view-tiles-v2").put("codec","video/avc").put("width",2048).put("height",1536).put("bytes",ViewFrameFixture.DATA.length)
       .put("rect",new org.json.JSONArray("[0,0,1,1]")).put("inner",new org.json.JSONArray("[0,0,1,1]"))
       .put("tiles",new org.json.JSONArray("[[[0,0,1,1],[0,0,1,1]]]")).put("desktop",new org.json.JSONArray("[0,0,5120,3200]"));
     }else header.put("type","view-baseline-v1").put("bytes",0).put("reason","Normal density: zoom in for native detail");
     byte[] json=header.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);output.writeInt(json.length);output.write(json);if(nativeFrame)output.write(ViewFrameFixture.DATA);output.flush();Thread.sleep(20);
    }
   }catch(Throwable error){if(!done.get())failure.set(error);}
  },"fake-budget-host");serving.setDaemon(true);serving.start();
  var worker=new ViewedDetail(()->new ViewDestination(video.getViewInput(),(pts,frame)->{video.viewFrame(pts,frame);return Unit.INSTANCE;},()->{video.clearView();return Unit.INSTANCE;}),
   message->{System.out.println("budget worker: "+message);return Unit.INSTANCE;},()->{fallbacks.incrementAndGet();if(!done.get()){Canvas c=video.getInput().lockCanvas(null);c.drawColor(Color.GREEN);video.getInput().unlockCanvasAndPost(c);}return Unit.INSTANCE;});
  try{
   worker.update(new DetailRequest(new InetSocketAddress("127.0.0.1",server.getLocalPort()),123,new FocusRect(0,0,1,1),java.util.List.of(0,0,5120,3200),new kotlin.Pair<Float,Float>(.5f,.5f),0,0f,1f));
   await(reader,false);phase.set(1);await(reader,true);phase.set(2);await(reader,false);
   if(failure.get()!=null)throw new AssertionError(failure.get());
   if(fallbacks.get()<2)throw new AssertionError("Baseline transitions were not announced");
   System.out.println("PASS actual budget negotiation, baseline without decoder, native AVC tile decoding and fresh baseline restoration");
  }catch(Throwable error){error.printStackTrace();throw error;}finally{done.set(true);worker.close();server.close();serving.join(1500);video.close();reader.close();}
 }
}
