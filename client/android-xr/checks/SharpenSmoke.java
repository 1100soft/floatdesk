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
  var hudLayout=dev.floatdesk.client.ViewedRegionKt.hudSurfaceLayout(.6f,.5f,.35f);
  var hud=Bitmap.createBitmap(hudLayout.getPixelWidth(),hudLayout.getPixelHeight(),Bitmap.Config.ARGB_8888);
  var hudState=new DetailDiagnostics(true,new FocusRect(.1f,.1f,.9f,.9f),"GAZE","REQ 100 / RX 99 / late 0 / errors 0 · 22ms · HEVC native tiles 1280x1024 · 20KiB",1f,1f,true,true,
    "Host CPU(core) 123% RSS 200MiB free 12000MiB GPU(proc) 30%\nHost video 60.0fps 20.0Mbps · cap 2.0ms enc 3.0ms",
    "XR CPU(core) 111% PSS 120MiB free 9000MiB gfx 20MiB GPU 25%");
  dev.floatdesk.client.DiagnosticsCanvas.INSTANCE.drawSurface(new Canvas(hud),new dev.floatdesk.client.net.DiagnosticSnapshot(hudState,null,"Image 60.0fps redraw 60.0fps · GL submit 0.20ms","Output 5120x3200 · base 3456x2160 · effective X1.48 Y1.48"),new float[]{0,0,1,1},SystemClock.uptimeMillis(),hudLayout);
  int hudLit=0;
  for(int y=0;y<hud.getHeight();y++)for(int x=0;x<hud.getWidth();x++)if(Color.alpha(hud.getPixel(x,y))>0){
    hudLit++;boolean inside=false;
    for(var region:hudLayout.getRegions())if(region.contains((float)x/hud.getWidth(),(float)y/hud.getHeight()))inside=true;
    if(!inside)throw new AssertionError("Single curved HUD draws outside its geometry");
  }
  if(hudLit<1000 || Color.alpha(hud.getPixel(hud.getWidth()/2,hud.getHeight()/2))!=0)throw new AssertionError("Single HUD is empty or obstructs the focus center");
  for(var region:hudLayout.getRegions()){
    int x0=(int)(region.getLeft()*hud.getWidth())+2,y0=(int)(region.getTop()*hud.getHeight())+2;
    int x1=(int)(region.getRight()*hud.getWidth())-3,y1=(int)(region.getBottom()*hud.getHeight())-3;
    if(Color.alpha(hud.getPixel(x0,y0))!=0 || Color.alpha(hud.getPixel(x1,y1))!=0)throw new AssertionError("HUD corners have a background");
  }
  var hostRegion=hudLayout.getRegions().get(0);
  int hx0=(int)(hostRegion.getLeft()*hud.getWidth()),hx1=(int)(hostRegion.getRight()*hud.getWidth());
  int hy0=(int)(hostRegion.getTop()*hud.getHeight()),hy1=(int)(hostRegion.getBottom()*hud.getHeight());
  boolean headerSeen=false,afterHeader=false;int gap=0;
  for(int y=hy0;y<hy1;y++){
    boolean ink=false;for(int x=hx0;x<hx1;x++)if(Color.alpha(hud.getPixel(x,y))>0){ink=true;break;}
    if(!headerSeen){if(ink)headerSeen=true;}
    else if(!afterHeader){if(!ink){afterHeader=true;gap=1;}}
    else if(ink)break;else gap++;
  }
  if(!headerSeen || gap<8)throw new AssertionError("HUD header overlaps content; gap="+gap);
  System.out.println("HUD header/body gap="+gap+"px");
  // Draws queued during spatial-panel removal must remain safe after texture disposal.
  var glyph=new dev.floatdesk.client.HudGlyphTexture();
  var targetBitmap=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888);
  var targetCanvas=new Canvas(targetBitmap);
  float[] glyphMesh={0,0,800,0,0,400,800,400};
  var diagnostic=new dev.floatdesk.client.net.DiagnosticSnapshot(hudState,null,"60fps");
  glyph.draw(targetCanvas,diagnostic,new float[]{0,0,1,1},1,0,1,1,glyphMesh);
  int glyphCount=0;for(int y=0;y<400;y++)for(int x=0;x<800;x++)if(Color.alpha(targetBitmap.getPixel(x,y))!=0)glyphCount++;
  if(glyphCount<100)throw new AssertionError("Foreground glyph draw is empty");
  glyph.close();glyph.close();targetCanvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
  glyph.draw(targetCanvas,diagnostic,new float[]{0,0,1,1},2,0,1,1,glyphMesh);
  if(glyph.getBitmap().isRecycled())throw new AssertionError("Recorded HUD bitmap was recycled during disposal");
  for(int y=0;y<400;y++)for(int x=0;x<800;x++)if(Color.alpha(targetBitmap.getPixel(x,y))!=0)throw new AssertionError("Disposed HUD rendered a queued draw");
  targetBitmap.recycle();System.out.println("PASS queued HUD draw after repeated disposal is safe and blank");
  hud.recycle();
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
