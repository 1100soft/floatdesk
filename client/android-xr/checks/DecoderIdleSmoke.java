import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import android.view.Surface;
import java.lang.reflect.Proxy;
import java.net.DatagramSocket;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/** A single keyframe must display even if no following compressed frame arrives.
 * Fixture: ffmpeg red 320x240, libx264 ultrafast/zerolatency, one frame, SEI removed. */
public final class DecoderIdleSmoke {
    static Object field(Object object,String name) throws Exception {
        var field=object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var reader=ImageReader.newInstance(320,240,PixelFormat.RGBA_8888,4);
        var socket=new DatagramSocket();
        Class<?> function=Class.forName("kotlin.jvm.functions.Function0");
        Object callback=Proxy.newProxyInstance(function.getClassLoader(),new Class<?>[]{function},
            (proxy,method,values)->Class.forName("kotlin.Unit").getField("INSTANCE").get(null));
        Class<?> type=Class.forName("dev.floatdesk.client.media.MonitorDecoder");
        Object decoder=type.getConstructor(DatagramSocket.class,int.class,int.class,java.io.File.class,function)
            .newInstance(socket,320,240,null,callback);
        try {
            type.getMethod("attach",Surface.class).invoke(decoder,reader.getSurface());
            ((AtomicReference<byte[]>)field(decoder,"sps")).set(Base64.getDecoder().decode("Z0LADdoFB+wEQAAAAwBAAAAMo8UKqA=="));
            ((AtomicReference<byte[]>)field(decoder,"pps")).set(Base64.getDecoder().decode("aM4PyA=="));
            ((BlockingQueue<byte[]>)field(decoder,"pending")).put(Base64.getDecoder().decode("AAAAAWdCwA3aBQfsBEAAAAMAQAAADKPFCqgAAAABaM4PyAAAAAFliIQ6EYoAAhjxwABA9jgACHlJycnJycnJycnJycnJycnJycnJ111111111111111111111111111111111111111111111111111111111111111111111111111111114AAAAAFlAyiIQ6EYoAAhjxwABA9jgACHlJycnJycnJycnJycnJycnJycnJ111111111111111111111111111111111111111111111111111111111111111111111111111111114AAAABZQGSIhDoRigACGPHAAED2OAAIeUnJycnJycnJycnJycnJycnJycnXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXgA=="));
            type.getMethod("start").invoke(decoder);
            long deadline=SystemClock.uptimeMillis()+3000;
            while(SystemClock.uptimeMillis()<deadline) {
                try(Image image=reader.acquireLatestImage()) {
                    if(image!=null) {
                        var plane=image.getPlanes()[0];var pixels=plane.getBuffer();
                        int offset=120*plane.getRowStride()+160*plane.getPixelStride();
                        if((pixels.get(offset)&255)<200 || (pixels.get(offset+1)&255)>40)
                            throw new AssertionError("Single keyframe pixels are corrupt");
                        System.out.println("PASS: actual MediaCodec displays a lone keyframe while decoder input is idle");
                        return;
                    }
                }
                SystemClock.sleep(5);
            }
            throw new AssertionError("Completed decoder output waited for another compressed frame");
        } finally { type.getMethod("stop").invoke(decoder); socket.close(); reader.close(); }
    }
}
