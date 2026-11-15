import android.media.*;
public final class CodecCapacitySmoke {
 public static void main(String[] args){for(var info:new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()){
  if(info.isEncoder())continue;for(String type:info.getSupportedTypes())if(type.equals("video/avc")){
   var v=info.getCapabilitiesForType(type).getVideoCapabilities();
   System.out.println(info.getName()+" width="+v.getSupportedWidths()+" height="+v.getSupportedHeights()+" 3520x3200="+v.isSizeSupported(3520,3200)+" 4096x4096="+v.isSizeSupported(4096,4096));
  }
 }}
}
