import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import dev.floatdesk.client.media.*;

/** Atlas regions share one source while owner/menu outputs retain independent UVs. */
public final class VideoCropSmoke {
 public static void main(String[] args)throws Exception{
  var reader=ImageReader.newInstance(64,64,PixelFormat.RGBA_8888,4);
  var video=new VideoCompositor(256,128);
  try{
   video.setSourceAspect(2f);
   var canvas=video.getInput().lockCanvas(null);canvas.drawColor(Color.RED);
   var paint=new Paint();paint.setColor(Color.GREEN);canvas.drawRect(128,0,256,128,paint);video.getInput().unlockCanvasAndPost(canvas);
   for(boolean right:new boolean[]{true,false}){
    video.mirrors(java.util.List.of(new VideoMirror(reader.getSurface(),0f,0f,false,1f,null,null,()->new VideoCrop(right?.5f:0f,0f,right?1f:.5f,1f,1f))));
    boolean matched=false;long until=SystemClock.uptimeMillis()+5000;
    while(SystemClock.uptimeMillis()<until){try(var image=reader.acquireLatestImage()){
     if(image!=null){var p=VideoCompositorSmoke.pixel(image,32,32);if(right?p[1]>180&&p[0]<60:p[0]>180&&p[1]<60){matched=true;break;}}
    }SystemClock.sleep(10);}
    if(!matched)throw new AssertionError("Wrong atlas crop: right="+right);
   }
   video.mirrors(java.util.List.of(new VideoMirror(reader.getSurface(),0f,0f,false,1f,null,null,()->new VideoCrop(0f,0f,0f,0f,1f))));
   video.flushOutputs();try(var stale=reader.acquireLatestImage()){}SystemClock.sleep(150);
   try(var missing=reader.acquireLatestImage()){if(missing!=null)throw new AssertionError("Missing menu region rendered the full atlas");}
   System.out.println("PASS independent owner/menu UV crops share one GPU source; missing regions never render the atlas");
  }finally{video.close();reader.close();}
 }
}
