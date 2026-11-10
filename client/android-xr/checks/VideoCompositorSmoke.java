import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import dev.floatdesk.client.media.VideoAim;
import dev.floatdesk.client.media.VideoCompositor;
import java.nio.ByteBuffer;

/** Offscreen device check: never opens an Activity or touches the user's desktop. */
public final class VideoCompositorSmoke {
    static Image frame(ImageReader reader) {
        long deadline=SystemClock.uptimeMillis()+3000;
        while(SystemClock.uptimeMillis()<deadline) {
            Image image=reader.acquireLatestImage();
            if(image!=null) return image;
            SystemClock.sleep(20);
        }
        throw new AssertionError("No compositor frame arrived");
    }
    static int[] pixel(Image image,int x,int y) {
        Image.Plane p=image.getPlanes()[0]; ByteBuffer b=p.getBuffer();
        int offset=y*p.getRowStride()+x*p.getPixelStride();
        return new int[]{b.get(offset)&255,b.get(offset+1)&255,b.get(offset+2)&255};
    }
    static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        ImageReader first=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);
        ImageReader second=ImageReader.newInstance(64,48,PixelFormat.RGBA_8888,4);
        VideoCompositor compositor=new VideoCompositor(64,48);
        try {
            compositor.output(first.getSurface());
            Canvas canvas=compositor.getInput().lockCanvas(null);
            canvas.drawColor(Color.RED);
            android.graphics.Paint paint=new android.graphics.Paint(); paint.setColor(Color.BLUE);
            canvas.drawRect(0,24,64,48,paint);
            compositor.getInput().unlockCanvasAndPost(canvas);
            try(Image image=frame(first)) {
                int[] top=pixel(image,16,8), bottom=pixel(image,16,40);
                require(top[0]>220 && top[2]<20,"Video top is flipped or corrupt");
                require(bottom[2]>220 && bottom[0]<20,"Video bottom is flipped or corrupt");
            }
            compositor.overlay(new VideoAim(new float[]{.1f,.25f,.9f,.25f},new float[]{.5f,.5f},2f,.18f));
            SystemClock.sleep(40);
            try(Image image=frame(first)) {
                int[] line=pixel(image,16,12), ring=pixel(image,37,24);
                require(line[1]>25 && line[2]>25,"Projected ray is missing from video pixels");
                require(ring[1]>25,"Contact ring is missing from video pixels");
            }
            compositor.overlay(new VideoAim(new float[0],new float[0],2f,.18f));
            SystemClock.sleep(40);
            try(Image image=frame(first)) { require(pixel(image,16,12)[1]<20,"Cleared ray left a ghost"); }
            for(int i=0;i<5;i++) {
                compositor.overlay(new VideoAim(new float[0],new float[0],2f,.18f));
                SystemClock.sleep(20);
            }
            try(Image unchanged=first.acquireLatestImage()) {
                require(unchanged==null,"Unchanged empty overlays requeued stale video frames");
            }
            compositor.output(second.getSurface());
            try(Image image=frame(second)) {
                require(pixel(image,16,8)[0]>220,"Surface handoff lost the retained video frame");
            }
            System.out.println("PASS: real headset EGL video orientation, ray/ring pixels, overlay clearing, unchanged-overlay suppression and output handoff without a new decoder frame");
        } finally { compositor.close(); first.close(); second.close(); }
    }
}
