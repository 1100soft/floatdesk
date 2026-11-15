import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.os.SystemClock;
import dev.floatdesk.client.media.VideoCompositor;
/** Actual decoder/filter reconstruction across a packed boundary with unrelated magenta neighbors. */
public final class GutterSmoke {
 public static void main(String[] args)throws Exception{
  var reader=ImageReader.newInstance(200,80,PixelFormat.RGBA_8888,4);var video=new VideoCompositor(200,80);MediaCodec codec=null;
  try{
   video.detailDebug(new dev.floatdesk.client.net.DetailDiagnostics(true,new dev.floatdesk.client.FocusRect(0,0,1,1),"gutter","",1f,1f,false));
   video.output(reader.getSurface());video.sharpen(1.5f);
   float[] tiles={0,0,.5f,1,4f/256,4f/128,104f/256,84f/128,
       .5f,0,1,1,132f/256,4f/128,232f/256,84f/128};
   codec=PackedViewSmoke.decode(video,256,128,"/data/local/tmp/floatdesk-gutter.hevc",tiles,1000000,4);
   long until=SystemClock.uptimeMillis()+4000;
   while(SystemClock.uptimeMillis()<until){try(var image=reader.acquireLatestImage()){
    if(image!=null){
     if(ViewStreamSmoke.pixel(image,100,40)[0]<50)continue;
     for(int y:new int[]{20,40,60})for(int x=90;x<=110;x++){
      int[] pixel=ViewStreamSmoke.pixel(image,x,y);int expected=Math.round(40+x*.8f);
      if(Math.abs(pixel[0]-expected)>8 || Math.abs(pixel[0]-pixel[1])>5 || Math.abs(pixel[1]-pixel[2])>5)
       throw new AssertionError("Packed seam/tint at "+x+","+y+": "+java.util.Arrays.toString(pixel)+" expected "+expected);
     }
     System.out.println("PASS padded packed boundary preserves gradient with sharpening and no neighboring-tile tint");return;
    }
   }SystemClock.sleep(10);}throw new AssertionError("Padded packed frame unavailable");
  }finally{if(codec!=null){codec.stop();codec.release();}video.close();reader.close();}
 }
}
