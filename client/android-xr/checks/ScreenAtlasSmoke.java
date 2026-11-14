import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import dev.floatdesk.client.FocusRect;
import dev.floatdesk.client.media.*;
import dev.floatdesk.client.net.*;
import java.nio.ByteBuffer;

/** Actual GPU four-screen mapping, including screen 3 alone and with visual spacing. */
public final class ScreenAtlasSmoke {
 static void await(ImageReader reader,boolean thirdOnly){
  long until=SystemClock.uptimeMillis()+2500;
  while(SystemClock.uptimeMillis()<until){
   try(Image image=reader.acquireLatestImage()){
    if(image!=null){
     int[][] points={ViewStreamSmoke.pixel(image,16,16),ViewStreamSmoke.pixel(image,48,16),ViewStreamSmoke.pixel(image,16,48),ViewStreamSmoke.pixel(image,48,48)};
     boolean yellow=points[2][0]>180 && points[2][1]>180 && points[2][2]<50;
     if(thirdOnly && yellow && points[0][3]<10 && points[1][3]<10 && points[3][3]<10)return;
     if(!thirdOnly && yellow && points[0][0]>180 && points[0][1]<50 && points[1][1]>180 && points[1][0]<50 && points[3][2]>180 && points[3][0]<50)return;
    }
   }SystemClock.sleep(5);
  }throw new AssertionError("Four-screen atlas mismatch, isolated screen 3="+thirdOnly);
 }
 static void submit(MediaCodec codec,VideoCompositor video,long pts,boolean isolated)throws Exception{
  byte[] data=isolated?ScreenFrameFixture.THIRD:ScreenFrameFixture.ALL;
  video.viewFrame(pts,new ViewFrame(isolated?new FocusRect(0f,.5f,.5f,1f):new FocusRect(0f,0f,1f,1f),new FocusRect(0f,.5f,.5f,1f),SystemClock.uptimeMillis()));
  int input=codec.dequeueInputBuffer(100000);if(input<0)throw new AssertionError("No atlas decoder input");
  codec.getInputBuffer(input).put(data);codec.queueInputBuffer(input,0,data.length,pts,MediaCodec.BUFFER_FLAG_KEY_FRAME);
  var info=new MediaCodec.BufferInfo();long until=SystemClock.uptimeMillis()+1000;
  while(SystemClock.uptimeMillis()<until){int output=codec.dequeueOutputBuffer(info,5000);if(output>=0){codec.releaseOutputBuffer(output,true);return;}}
  throw new AssertionError("No atlas decoder output");
 }
 public static void main(String[] args)throws Exception{
  var reader=ImageReader.newInstance(64,64,PixelFormat.RGBA_8888,4);var video=new VideoCompositor(64,64);MediaCodec codec=null;
  try{
   video.output(reader.getSurface());
   float[] rects={0,0,.5f,.5f, .5f,0,1,.5f, 0,.5f,.5f,1, .5f,.5f,1,1};
   video.presentation(new ScreenPresentation(rects,0f));
   var canvas=video.getInput().lockCanvas(null);var paint=new Paint();
   int[] colors={Color.RED,Color.GREEN,Color.YELLOW,Color.BLUE};
   for(int i=0;i<4;i++){paint.setColor(colors[i]);canvas.drawRect((i%2)*32,(i/2)*32,(i%2+1)*32,(i/2+1)*32,paint);}video.getInput().unlockCanvasAndPost(canvas);
   await(reader,false);
   byte[] sps=null,pps=null;for(byte[] nal:H264Kt.splitAnnexB(ScreenFrameFixture.ALL)){if(H264Kt.nalType(nal)==7)sps=nal;if(H264Kt.nalType(nal)==8)pps=nal;}
   var format=MediaFormat.createVideoFormat("video/avc",2048,1536);format.setByteBuffer("csd-0",ByteBuffer.wrap(H264Kt.annexBParameterSets(sps,pps)));format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
   codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,video.getViewInput(),null,0);codec.start();
   video.detailDebug(new DetailDiagnostics(true,new FocusRect(0f,0f,1f,1f),"screens","",1,1,false));
   submit(codec,video,1000000,false);await(reader,false);
   video.presentation(new ScreenPresentation(rects,.1f));await(reader,false);
   submit(codec,video,2000000,true);await(reader,true);
   submit(codec,video,3000000,false);await(reader,false);
   System.out.println("PASS: all four screen colors, isolated screen 3, spacing, high/peripheral sampling and return to full view");
  }catch(Throwable error){error.printStackTrace();throw error;}finally{if(codec!=null){codec.stop();codec.release();}video.close();reader.close();}
 }
}
