import android.graphics.*;
import android.media.*;
import android.os.SystemClock;
import dev.floatdesk.client.media.VideoCompositor;
import dev.floatdesk.client.FocusRect;
import dev.floatdesk.client.net.DetailDiagnostics;

/** Retained-frame sharpening, bounded edges, and diagnostics isolated from desktop pixels. */
public final class SharpenSmoke {
 static Image frame(ImageReader reader){long until=SystemClock.uptimeMillis()+2000;while(SystemClock.uptimeMillis()<until){Image image=reader.acquireLatestImage();if(image!=null)return image;SystemClock.sleep(5);}throw new AssertionError("No sharpen frame");}
 static int pixel(Image image,int x,int y){var p=image.getPlanes()[0];return p.getBuffer().get(y*p.getRowStride()+x*p.getPixelStride())&255;}
 public static void main(String[] args)throws Exception{
  var fonts=Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");fonts.setAccessible(true);fonts.invoke(null);
  var overlay=Bitmap.createBitmap(1280,480,Bitmap.Config.ARGB_8888);
  var overlayState=new DetailDiagnostics(false,null,"","",0f,0f,false,true,"Host CPU(core) 120%","XR CPU(core) 130%");
  dev.floatdesk.client.DiagnosticsCanvas.INSTANCE.draw(new Canvas(overlay),new dev.floatdesk.client.net.DiagnosticSnapshot(overlayState,null,"60fps"),new float[]{0,0,1,1},SystemClock.uptimeMillis());
  if(Color.alpha(overlay.getPixel(0,0))!=0 || Color.alpha(overlay.getPixel(1279,479))!=0)throw new AssertionError("Diagnostics background is opaque");
  int lit=0;for(int y=0;y<480;y++)for(int x=0;x<1280;x++)if(Color.alpha(overlay.getPixel(x,y))>0)lit++;
  for(int y=120;y<360;y++)for(int x=384;x<896;x++)if(Color.alpha(overlay.getPixel(x,y))!=0)throw new AssertionError("HUD obstructed the central focus area");
  if(lit<100 || lit>1280*480/8)throw new AssertionError("Diagnostics are not isolated floating glyphs");overlay.recycle();
  var reader=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var video=new VideoCompositor(64,48);
  try{
   video.output(reader.getSurface());Canvas canvas=video.getInput().lockCanvas(null);canvas.drawColor(Color.BLACK);Paint p=new Paint();
   for(int x=0;x<64;x++){int value=x<31?0:x==31?64:x==32?192:255;p.setColor(Color.rgb(value,value,value));canvas.drawRect(x,0,x+1,48,p);}video.getInput().unlockCanvasAndPost(canvas);
   System.out.println("sharpen: input ready");int original;try(Image image=frame(reader)){original=pixel(image,32,12)-pixel(image,31,12);}
   System.out.println("sharpen: baseline ready="+original);video.sharpen(1f);try(Image image=frame(reader)){int enhanced=pixel(image,32,12)-pixel(image,31,12);if(enhanced<original+15 || pixel(image,10,12)>5 || pixel(image,50,12)<250)throw new AssertionError("Sharpening did not increase edge contrast safely: "+original+" -> "+enhanced);}
   System.out.println("sharpen: effect verified");video.sharpen(0f);try(Image image=frame(reader)){if(Math.abs(pixel(image,32,12)-pixel(image,31,12)-original)>4)throw new AssertionError("Sharpen off did not restore original");}
   video.detailDebug(new DetailDiagnostics(false,new FocusRect(0f,0f,1f,1f),"normal","",0f,0f,false,true,"Host CPU 10% RSS 50MiB\nHost video 60fps 12Mbps","XR CPU 5% PSS 70MiB"));
   video.output(reader.getSurface(),true);
   try(Image image=frame(reader)){if(pixel(image,50,27)<250)throw new AssertionError("Resources altered desktop pixels");}
   if(!video.diagnosticSnapshot().getState().getShowResources())throw new AssertionError("Separate overlay lost diagnostic state");
   video.detailDebug(new DetailDiagnostics(false,null,"normal","",0f,0f,false,false,"",""));
   video.output(reader.getSurface(),true);
   try(Image image=frame(reader)){if(pixel(image,50,27)<250)throw new AssertionError("Diagnostics toggle altered desktop pixels");}
   System.out.println("PASS: adjustable luma sharpening improves edges without flat-area halos, and diagnostics never paint the desktop");
  }catch(Throwable error){error.printStackTrace();System.out.flush();System.err.flush();throw error;}finally{video.close();reader.close();}
 }
}
