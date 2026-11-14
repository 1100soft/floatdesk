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

/** Actual MediaCodec-to-OES PTS association and high/peripheral atlas sampling. */
public final class ViewStreamSmoke {
    static int[] pixel(Image image,int x,int y){var p=image.getPlanes()[0];var b=p.getBuffer();int at=y*p.getRowStride()+x*p.getPixelStride();return new int[]{b.get(at)&255,b.get(at+1)&255,b.get(at+2)&255,b.get(at+3)&255};}
    static void await(ImageReader reader,int mode) {
        long until=SystemClock.uptimeMillis()+2500;
        while(SystemClock.uptimeMillis()<until){
            try(Image image=reader.acquireLatestImage()){
                if(image!=null){
                    int[] high=pixel(image,32,16),low=pixel(image,12,10),outside=pixel(image,1,1);
                    if(mode==0 && high[0]>180 && low[2]>180 && outside[3]<10)return;
                    if(mode==1 && high[1]>180 && high[0]<40 && low[1]>180)return;
                }
            }
            SystemClock.sleep(5);
        }
        throw new AssertionError("View atlas did not match its PTS metadata, mode="+mode);
    }
    static void submit(MediaCodec codec,VideoCompositor compositor,long pts,boolean match) throws Exception {
        compositor.viewFrame(match?pts:pts+10,new ViewFrame(new FocusRect(.1f,.1f,.9f,.9f),new FocusRect(.3f,.3f,.7f,.7f),SystemClock.uptimeMillis()));
        int index=codec.dequeueInputBuffer(100000);if(index<0)throw new AssertionError("No view input buffer");
        ByteBuffer input=codec.getInputBuffer(index);input.put(ViewFrameFixture.DATA);codec.queueInputBuffer(index,0,ViewFrameFixture.DATA.length,pts,MediaCodec.BUFFER_FLAG_KEY_FRAME);
        var info=new MediaCodec.BufferInfo();long until=SystemClock.uptimeMillis()+800;
        while(SystemClock.uptimeMillis()<until){index=codec.dequeueOutputBuffer(info,5000);if(index>=0){codec.releaseOutputBuffer(index,true);return;}}
        throw new AssertionError("View decoder produced no frame");
    }
    public static void main(String[] args) throws Exception {
        var fonts=android.graphics.Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");fonts.setAccessible(true);fonts.invoke(null);
        var reader=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var compositor=new VideoCompositor(64,48);MediaCodec codec=null;
        try {
            compositor.output(reader.getSurface());Canvas canvas=compositor.getInput().lockCanvas(null);canvas.drawColor(Color.GREEN);compositor.getInput().unlockCanvasAndPost(canvas);
            compositor.detailDebug(new DetailDiagnostics(true,new FocusRect(.1f,.1f,.9f,.9f),"view test","H264",2f,2f));
            var nals=H264Kt.splitAnnexB(ViewFrameFixture.DATA);byte[] sps=null,pps=null;for(byte[] nal:nals){if(H264Kt.nalType(nal)==7)sps=nal;if(H264Kt.nalType(nal)==8)pps=nal;}
            var format=MediaFormat.createVideoFormat("video/avc",2048,1536);format.setByteBuffer("csd-0",ByteBuffer.wrap(H264Kt.annexBParameterSets(sps,pps)));format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
            codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,compositor.getViewInput(),null,0);codec.start();
            submit(codec,compositor,1000000,true);await(reader,0);
            submit(codec,compositor,2000000,false);await(reader,1);
            submit(codec,compositor,3000000,true);await(reader,0);
            compositor.clearView();await(reader,1);
            System.out.println("PASS: actual H264 atlas center/periphery sampling, out-of-view alpha, exact PTS association and baseline restoration");
        } finally {if(codec!=null){codec.stop();codec.release();}compositor.close();reader.close();}
    }
}
