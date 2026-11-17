import android.graphics.*;
import android.media.*;
import android.os.*;
import dev.floatdesk.client.*;
import dev.floatdesk.client.media.*;
import dev.floatdesk.client.net.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;

/** One decoder feeds two native outputs and an occlusion-aware desktop patch. */
public final class WindowFanoutSmoke {
 static void await(ImageReader reader,int kind){
  String last="no image";long end=SystemClock.uptimeMillis()+6000;
  while(SystemClock.uptimeMillis()<end){try(var image=reader.acquireLatestImage()){
   if(image!=null){var center=VideoCompositorSmoke.pixel(image,32,24);var corner=VideoCompositorSmoke.pixel(image,2,2);var inside=VideoCompositorSmoke.pixel(image,20,16);
    last="center="+java.util.Arrays.toString(center)+" corner="+java.util.Arrays.toString(corner)+" inside="+java.util.Arrays.toString(inside);boolean red=center[0]>180&&center[1]<70;
    if(kind==0&&red&&corner[0]>180&&corner[1]<70)return;
    if(kind==1&&red&&corner[0]>180&&corner[1]>90&&corner[1]<220&&corner[2]<70)return;
    if(kind==2&&center[2]>180&&center[0]<70&&corner[1]>180&&inside[0]>180&&inside[1]<70)return;
    if(kind==3&&center[2]>180&&inside[1]>180&&inside[0]<70)return;
   }
  }SystemClock.sleep(10);}throw new AssertionError("Wrong shared-window output kind="+kind+" "+last);
 }
 public static void main(String[] args)throws Exception{
  if(Looper.myLooper()==null)Looper.prepareMainLooper();
  var connection=new DeskConnection(null);
  Class.forName("androidx.compose.runtime.MutableState").getMethod("setValue",Object.class).invoke(DeskConnection.class.getMethod("getWorkspaceInfo").invoke(connection),WorkspaceInfo.Companion.parse("{\"type\":\"status\",\"available\":0,\"availableMemoryBytes\":8000000000,\"cpuThreads\":8,\"recommendedPixels\":8000000,\"targetFps\":60,\"estimate\":\"test\",\"setupRequired\":false,\"screens\":[{\"name\":\"screen\",\"width\":1024,\"height\":768,\"x\":0,\"y\":0,\"managed\":false,\"role\":0,\"primary\":true,\"modes\":[]}],\"windows\":[{\"id\":42,\"x\":256,\"y\":192,\"width\":400,\"height\":300}],\"systemWindows\":[{\"id\":43,\"x\":0,\"y\":730,\"width\":1024,\"height\":38}]}"));
  var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var done=new AtomicBoolean();var error=new AtomicReference<Throwable>();var count=new AtomicInteger();
  var serving=new Thread(()->{try(var socket=server.accept()){
   var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());
   while(!done.get()){
    int n=input.readInt();byte[] b=new byte[n];input.readFully(b);var request=new org.json.JSONObject(new String(b));
    if(request.getBoolean("rootCapture"))throw new AssertionError("Shared source must be unobscured XComposite capture");
    byte[] header=new org.json.JSONObject().put("windowId",42).put("width",512).put("height",384).put("sourceX",256).put("sourceY",192).put("sourceWidth",512).put("sourceHeight",384).put("occluders",new org.json.JSONArray("[[400,300,200,160]]")).put("bytes",WindowFrameFixture.DATA.length).toString().getBytes();
    output.writeInt(header.length);output.write(header);output.write(WindowFrameFixture.DATA);output.flush();Thread.sleep(30);
   }
  }catch(Throwable e){if(!done.get())error.set(e);}});serving.start();
  var plain=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var pinned=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var desktop=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);
  var root=new VideoCompositor(64,48);root.output(desktop.getSurface());
  var canvas=root.getInput().lockCanvas(null);canvas.drawColor(Color.GREEN);var paint=new Paint();paint.setColor(Color.BLUE);canvas.drawRect(25,18,38,29,paint);root.getInput().unlockCanvasAndPost(canvas);
  var pool=new WindowCapturePool(connection,(id,video,failed,frame)->{count.incrementAndGet();return new WindowVideo(new InetSocketAddress("127.0.0.1",server.getLocalPort()),123,id,30,video,failed,video.getInput(),frame,false);});
  var a=pool.attach(42,plain.getSurface(),()->Unit.INSTANCE,message->{error.set(new AssertionError(message));return Unit.INSTANCE;},null);
  var b=pool.attach(42,pinned.getSurface(),()->Unit.INSTANCE,message->{error.set(new AssertionError(message));return Unit.INSTANCE;},null);b.update(.1f,.1f,true,4f/3f);
  var output=root.windowDestination(()->Unit.INSTANCE);
  var c=pool.attach(42,output.getSurface(),()->Unit.INSTANCE,message->{error.set(new AssertionError(message));return Unit.INSTANCE;},output);
  try{
   await(plain,0);await(pinned,1);await(desktop,2);
   var captured=pool.source(42L);var panel=pool.source(43L);
   if(captured==null||captured.getWidth()!=512||captured.getHeight()!=384)throw new AssertionError("Popup-expanded capture bounds not retained for input mapping");
   if(panel==null||panel.getWidth()!=1024||panel.getHeight()!=38)throw new AssertionError("System panel source not parsed independently of normal windows");
   if(count.get()!=1)throw new AssertionError("Capture/decoder duplicated "+count.get());
   a.close();await(pinned,1);await(desktop,2);
   c.close();await(desktop,3);await(pinned,1);
   if(error.get()!=null)throw new AssertionError(error.get());
   System.out.println("PASS one decoder, multiple outputs, opaque exterior pin bezel, exact source patch, occluder preservation and independent lease cleanup");
  }finally{done.set(true);a.close();b.close();c.close();server.close();serving.join(1500);root.close();plain.close();pinned.close();desktop.close();}
 }
}
