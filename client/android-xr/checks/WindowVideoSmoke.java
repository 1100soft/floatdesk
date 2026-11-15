import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import dev.floatdesk.client.media.*;
import dev.floatdesk.client.net.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.*;
import kotlin.Unit;

/** Real authenticated window worker, AVC decoder and first-presentation callback. */
public final class WindowVideoSmoke {
 public static void main(String[] args)throws Exception{
  var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));var done=new AtomicBoolean();var error=new AtomicReference<Throwable>();var presented=new AtomicInteger();
  var serving=new Thread(()->{try(var socket=server.accept()){
   var input=new DataInputStream(socket.getInputStream());var output=new DataOutputStream(socket.getOutputStream());
   while(!done.get()){
    int n=input.readInt();byte[] b=new byte[n];input.readFully(b);var request=new org.json.JSONObject(new String(b));
    if(request.getLong("sessionId")!=123 || request.getLong("windowId")!=42)throw new AssertionError("Window/session identity lost");
    byte[] header=new org.json.JSONObject().put("type","window-h264-v1").put("windowId",42).put("width",512).put("height",384).put("bytes",WindowFrameFixture.DATA.length).toString().getBytes();
    output.writeInt(header.length);output.write(header);output.write(WindowFrameFixture.DATA);output.flush();Thread.sleep(30);
   }
  }catch(Throwable e){if(!done.get()){e.printStackTrace();error.set(e);}}});serving.start();
  var reader=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var video=new VideoCompositor(64,48);
  video.onOutputPresented(()->{presented.incrementAndGet();return Unit.INSTANCE;});video.output(reader.getSurface());
  var worker=new WindowVideo(new InetSocketAddress("127.0.0.1",server.getLocalPort()),123,42,30,video,message->{System.out.println("Window worker error: "+message);error.set(new AssertionError(message));return Unit.INSTANCE;});
  try{
   try(var image=VideoCompositorSmoke.frame(reader)){var p=VideoCompositorSmoke.pixel(image,32,24);VideoCompositorSmoke.require(p[0]>200 && p[1]<40,"Window decoded wrong pixel");}
   VideoCompositorSmoke.require(presented.get()>0,"Missing first-presentation callback");
   if(error.get()!=null)throw new AssertionError(error.get());
   System.out.println("PASS window stream identity, AVC decoding, native output callback and bounded close");
  }finally{done.set(true);worker.close();server.close();serving.join(1500);video.close();reader.close();}
 }
}
