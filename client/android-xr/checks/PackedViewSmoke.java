import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import dev.floatdesk.client.FocusRect;
import dev.floatdesk.client.media.VideoCompositor;
import dev.floatdesk.client.media.H264Kt;
import dev.floatdesk.client.net.ViewFrame;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Hardware HEVC and shader reconstruction of differently sampled nonoverlapping tiles. */
public final class PackedViewSmoke {
    static MediaCodec decode(VideoCompositor compositor,int width,int height,String file,float[] tiles,long pts)throws Exception{
        byte[] bytes=Files.readAllBytes(Path.of(file));var csd=new java.io.ByteArrayOutputStream();
        for(byte[] nal:H264Kt.splitAnnexB(bytes)){int type=(nal[0]>>>1)&63;if(type>=32&&type<=34){csd.write(new byte[]{0,0,0,1});csd.write(nal);}}
        var format=MediaFormat.createVideoFormat("video/hevc",width,height);format.setByteBuffer("csd-0",ByteBuffer.wrap(csd.toByteArray()));format.setInteger(MediaFormat.KEY_LOW_LATENCY,1);
        var codec=MediaCodec.createDecoderByType("video/hevc");
        try{
            System.out.println("packed: configuring "+width+"x"+height);codec.configure(format,compositor.getViewInput(),null,0);codec.start();System.out.println("packed: codec ready");
            compositor.viewFrame(pts,new ViewFrame(new FocusRect(0,0,1,1),new FocusRect(.25f,.25f,.75f,.75f),SystemClock.uptimeMillis(),.5f,.5f,width,height,tiles));
            int input=codec.dequeueInputBuffer(500000);if(input<0)throw new AssertionError("HEVC input unavailable");codec.getInputBuffer(input).put(bytes);codec.queueInputBuffer(input,0,bytes.length,pts,MediaCodec.BUFFER_FLAG_KEY_FRAME);
            var info=new MediaCodec.BufferInfo();long deadline=SystemClock.uptimeMillis()+2500;
            while(SystemClock.uptimeMillis()<deadline){int output=codec.dequeueOutputBuffer(info,10000);if(output>=0){codec.releaseOutputBuffer(output,true);System.out.println("packed: frame released");return codec;}}
            throw new AssertionError("HEVC output unavailable");
        }catch(Throwable error){codec.release();throw error;}
    }
    static void await(ImageReader reader,boolean nativeFrame){
        long until=SystemClock.uptimeMillis()+3000;
        while(SystemClock.uptimeMillis()<until){try(var image=reader.acquireLatestImage()){
            if(image!=null){int[] center=ViewStreamSmoke.pixel(image,32,24),left=ViewStreamSmoke.pixel(image,8,24),right=ViewStreamSmoke.pixel(image,56,24),top=ViewStreamSmoke.pixel(image,32,5),bottom=ViewStreamSmoke.pixel(image,32,42);
                boolean white=center[0]>220&&center[1]>220&&center[2]>220;
                if(nativeFrame?white&&left[0]>220&&left[1]>220&&right[2]>220:
                    white&&left[0]>180&&left[1]<50&&right[1]>180&&right[0]<50&&top[2]>180&&top[0]<50&&bottom[0]>180&&bottom[1]>180&&bottom[2]<50)return;
            }
        }SystemClock.sleep(5);}
        throw new AssertionError("Packed HEVC tile reconstruction/orientation failed, native="+nativeFrame);
    }
    public static void main(String[] args)throws Exception{
        System.out.println("packed: starting");
        var reader=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);var compositor=new VideoCompositor(64,48);MediaCodec codec=null;
        try{
            compositor.detailDebug(new dev.floatdesk.client.net.DetailDiagnostics(true,new FocusRect(0,0,1,1),"packed","HEVC",1f,1f,false));
            compositor.output(reader.getSurface());System.out.println("packed: compositor ready");
            float[] tiles={.25f,.25f,.75f,.75f, .25f,.25f,.75f,.75f,
                0,0,.25f,1, 0,0,.125f,.5f,
                .75f,0,1,1, .75f,0,.875f,.5f,
                .25f,0,.75f,.25f, .25f,0,.5f,.125f,
                .25f,.75f,.75f,1, .25f,.75f,.5f,.875f};
            codec=decode(compositor,1024,768,"/data/local/tmp/floatdesk-packed.hevc",tiles,1000000);await(reader,false);System.out.println("packed: five tiles verified");
            codec.stop();codec.release();codec=null;
            codec=decode(compositor,5120,3200,"/data/local/tmp/floatdesk-native.hevc",new float[]{0,0,1,1,0,0,1,1},2000000);await(reader,true);
            System.out.println("PASS hardware HEVC five-tile reconstruction, native 5120x3200 cache resizing and 100% coverage; process PSS="+android.os.Debug.getPss()/1024+"MiB");
        }catch(Throwable error){error.printStackTrace();throw error;}finally{if(codec!=null){codec.stop();codec.release();}compositor.close();reader.close();}
    }
}
