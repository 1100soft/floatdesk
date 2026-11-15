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

/** Window capture replaces only its source rectangle in the existing desktop compositor. */
public final class InPlaceWindowSmoke {
 static void await(ImageReader reader,boolean active){
  long end=SystemClock.uptimeMillis()+5000;
  while(SystemClock.uptimeMillis()<end){try(var image=reader.acquireLatestImage()){
   if(image!=null){var center=VideoCompositorSmoke.pixel(image,32,24);var outside=VideoCompositorSmoke.pixel(image,4,4);
    if(outside[1]>180 && (active?center[0]>180&&center[1]<50:center[1]>180&&center[0]<50))return;
   }
  }SystemClock.sleep(10);}throw new AssertionError("Wrong in-place pixels active="+active);
 }
 public static void main(String[] args)throws Exception{
  var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var done=new AtomicBoolean();var error=new AtomicReference<Throwable>();var ready=new AtomicInteger();
  var serving=new Thread(()->{try(var socket=server.accept()){
   var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());
   while(!done.get()){
    int n=input.readInt();byte[] b=new byte[n];input.readFully(b);var request=new org.json.JSONObject(new String(b));
    if(!request.getBoolean("rootCapture"))throw new AssertionError("In-place mode must capture popups/occluders from root");
    byte[] header=new org.json.JSONObject().put("windowId",42).put("width",512).put("height",384).put("bytes",WindowFrameFixture.DATA.length).toString().getBytes();
    output.writeInt(header.length);output.write(header);output.write(WindowFrameFixture.DATA);output.flush();Thread.sleep(30);
   }
  }catch(Throwable e){if(!done.get())error.set(e);}});serving.start();
  var reader=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var video=new VideoCompositor(64,48);video.output(reader.getSurface());
  var canvas=video.getInput().lockCanvas(null);canvas.drawColor(Color.GREEN);video.getInput().unlockCanvasAndPost(canvas);
  var destination=video.windowDestination(()->{ready.incrementAndGet();return Unit.INSTANCE;});
  var worker=new WindowVideo(new InetSocketAddress("127.0.0.1",server.getLocalPort()),123,42,30,video,message->{error.set(new AssertionError(message));return Unit.INSTANCE;},destination.getSurface(),(pts,meta)->{destination.getFrame().invoke(pts,new FocusRect(.25f,.25f,.75f,.75f));return Unit.INSTANCE;},true);
  try{
   await(reader,true);if(ready.get()!=1)throw new AssertionError("First decoded frame callback must be once");
   done.set(true);worker.close();destination.getClear().invoke();await(reader,false);
   if(error.get()!=null)throw new AssertionError(error.get());
   System.out.println("PASS captured window sharp patch, unchanged desktop outside, timestamp mapping and clean restoration");
  }finally{done.set(true);worker.close();server.close();serving.join(1500);video.close();reader.close();}
 }
}
