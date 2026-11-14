import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import dev.floatdesk.client.FocusRect;
import dev.floatdesk.client.media.VideoCompositor;
import dev.floatdesk.client.media.H264Kt;
import dev.floatdesk.client.net.DetailDiagnostics;
import dev.floatdesk.client.net.ViewFrame;
import java.nio.ByteBuffer;

/** A native-size final buffer must preserve source-frequency detail lost in a reduced intermediate. */
public final class OutputResolutionSmoke {
    static int contrast(ImageReader reader) {
        long until=SystemClock.uptimeMillis()+1500;
        while(SystemClock.uptimeMillis()<until){
            try(Image image=reader.acquireLatestImage()){
                if(image!=null){
                    var p=image.getPlanes()[0];var buffer=p.getBuffer();int y=(int)(image.getHeight()*.4);int low=255,high=0;boolean gray=true;
                    for(int x=image.getWidth()/2;x<image.getWidth()/2+100;x++){
                        int at=y*p.getRowStride()+x*p.getPixelStride();int r=buffer.get(at)&255,g=buffer.get(at+1)&255,b=buffer.get(at+2)&255;
                        if(Math.abs(r-g)>15 || Math.abs(r-b)>15)gray=false;
                        low=Math.min(low,r);high=Math.max(high,r);
                    }
                    if(gray)return high-low;
                }
            }
            SystemClock.sleep(5);
        }
        throw new AssertionError("No decoded detail pattern arrived");
    }
    public static void main(String[] args) throws Exception {
        var low=ImageReader.newInstance(2048,768,PixelFormat.RGBA_8888,2);
        var full=ImageReader.newInstance(4096,1536,PixelFormat.RGBA_8888,2);
        var compositor=new VideoCompositor(2048,768);MediaCodec codec=null;
        try {
            compositor.output(low.getSurface());Canvas canvas=compositor.getInput().lockCanvas(null);canvas.drawColor(Color.GREEN);compositor.getInput().unlockCanvasAndPost(canvas);
            compositor.detailDebug(new DetailDiagnostics(true,new FocusRect(0f,0f,1f,1f),"pattern","H264",2f,2f,false));
            byte[] data=java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/data/local/tmp/floatdesk-detail-pattern.h264"));
            byte[] sps=null,pps=null;for(byte[] nal:H264Kt.splitAnnexB(data)){if(H264Kt.nalType(nal)==7)sps=nal;if(H264Kt.nalType(nal)==8)pps=nal;}
            var format=MediaFormat.createVideoFormat("video/avc",2048,1536);format.setByteBuffer("csd-0",ByteBuffer.wrap(H264Kt.annexBParameterSets(sps,pps)));format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8*1024*1024);
            codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,compositor.getViewInput(),null,0);codec.start();
            long pts=1000000;
            compositor.viewFrame(pts,new ViewFrame(new FocusRect(0f,0f,1f,1f),new FocusRect(.25f,.25f,.75f,.75f),SystemClock.uptimeMillis()));
            int index=codec.dequeueInputBuffer(100000);if(index<0)throw new AssertionError("No pattern decoder buffer");
            codec.getInputBuffer(index).put(data);codec.queueInputBuffer(index,0,data.length,pts,MediaCodec.BUFFER_FLAG_KEY_FRAME);
            var info=new MediaCodec.BufferInfo();long until=SystemClock.uptimeMillis()+800;
            while(SystemClock.uptimeMillis()<until){index=codec.dequeueOutputBuffer(info,5000);if(index>=0){codec.releaseOutputBuffer(index,true);break;}}
            int reduced=contrast(low);
            compositor.output(full.getSurface());int nativeContrast=contrast(full);
            if(nativeContrast<100 || nativeContrast<reduced+50)throw new AssertionError("Native output failed to recover detail: reduced="+reduced+" native="+nativeContrast);
            System.out.println("PASS: actual decoded 1-pixel text-like detail contrast: reduced output="+reduced+", native output="+nativeContrast);
        } finally {if(codec!=null){codec.stop();codec.release();}compositor.close();low.close();full.close();}
    }
}
