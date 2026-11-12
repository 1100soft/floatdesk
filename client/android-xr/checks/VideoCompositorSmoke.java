import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.SystemClock;
import dev.floatdesk.client.media.VideoAim;
import dev.floatdesk.client.net.WorkspaceInfo;
import dev.floatdesk.client.media.ScreenPresentation;
import dev.floatdesk.client.media.VideoCompositor;
import java.nio.ByteBuffer;
import dev.floatdesk.client.workspace.WorkspaceStore;
import dev.floatdesk.client.workspace.Pose;

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
        return new int[]{b.get(offset)&255,b.get(offset+1)&255,b.get(offset+2)&255,b.get(offset+3)&255};
    }
    static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        java.io.File workspaceFile=new java.io.File("/data/local/tmp/floatdesk-workspace-smoke.json");
        workspaceFile.delete();
        try {
            WorkspaceStore store=new WorkspaceStore(workspaceFile);
            store.setViewerReference(new Pose(new float[]{1f,2f,3f},new float[]{0f,0f,0f,1f}));
            store.save(store.load());
            WorkspaceStore reloaded=new WorkspaceStore(workspaceFile);
            require(reloaded.load().getViewerReference()!=null && reloaded.load().getViewerReference().getPosition()[2]==3f,"Viewer reference lost on disk reload");
            require(!new java.io.File(workspaceFile+".tmp").exists(),"Workspace save did not atomically replace file");
        } finally {workspaceFile.delete();}
        StringBuilder json=new StringBuilder("{\"type\":\"status\",\"available\":12,\"modes\":[[3840,2160]],\"availableMemoryBytes\":8000000000,\"cpuThreads\":8,\"recommendedPixels\":8000000,\"targetFps\":60,\"estimate\":\"advisory\",\"setupRequired\":false,\"screens\":[");
        for(int i=0;i<9;i++) {if(i>0)json.append(',');json.append("{\"name\":\"Virtual-").append(i).append("\",\"width\":3840,\"height\":2160,\"x\":").append(i*3840).append(",\"y\":0,\"managed\":true,\"primary\":false,\"role\":1,\"refreshHz\":0.1,\"modes\":[[3840,2160]]}");}
        json.append("]}");
        WorkspaceInfo info=WorkspaceInfo.Companion.parse(json.toString());
        require(info!=null && info.getScreens().size()==9 && info.getAvailable()==12,"Workspace catalogue retained a fixed screen limit");
        require(info.getScreens().get(8).getRefreshHz()==.1,"Low refresh metadata lost precision");
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
            compositor.presentation(new ScreenPresentation(new float[]{0f,0f,.5f,.5f,.5f,.5f,1f,1f},0f));
            try(Image image=frame(first)) {
                require(pixel(image,16,8)[3]>250,"Real screen became transparent");
                require(pixel(image,48,8)[3]==0,"Non-screen gap is not transparent");
                require(pixel(image,16,40)[3]==0,"Lower gap is not transparent");
                require(pixel(image,48,40)[2]>220,"Layout mask flipped source pixels");
            }
            compositor.presentation(new ScreenPresentation(new float[]{0f,0f,1f,1f},.15f));
            try(Image image=frame(first)) {
                require(pixel(image,1,24)[3]==0,"Visual spacing did not inset screen");
                require(pixel(image,16,8)[0]>220,"Inset screen lost upright image");
            }
            Canvas black=compositor.getInput().lockCanvas(null);black.drawColor(Color.BLACK);
            compositor.getInput().unlockCanvasAndPost(black);
            try(Image image=frame(first)) {require(pixel(image,32,24)[3]>250,"Actual black desktop was made transparent");}
            compositor.presentation(new ScreenPresentation(new float[]{0f,0f,1f,1f},0f));
            try(Image image=frame(first)) {require(pixel(image,1,24)[3]>250,"Presentation change did not redraw retained frame");}
            float[] many=new float[9*4];
            for(int i=0;i<9;i++){many[i*4]=i/9f;many[i*4+1]=0;many[i*4+2]=(i+1)/9f;many[i*4+3]=1;}
            compositor.presentation(new ScreenPresentation(many,0f));
            try(Image image=frame(first)) {require(pixel(image,62,24)[3]>250,"Uniform batching dropped screen nine");require(pixel(image,1,24)[3]>250,"Later batches erased earlier screens");}
            compositor.output(second.getSurface());
            try(Image image=frame(second)) {
                require(pixel(image,16,8)[3]>250,"Surface handoff lost the retained video frame");
            }
            System.out.println("PASS: real headset EGL video orientation, ray/ring pixels, overlay clearing, unchanged-overlay suppression and output handoff without a new decoder frame");
        } finally { compositor.close(); first.close(); second.close(); }
    }
}
