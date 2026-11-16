import dev.floatdesk.client.net.*;
import java.net.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;
import android.os.SystemClock;

/** Test real control-loop recovery with private loopback sockets; no discovery/video ports. */
public final class ControlRecoverySmoke {
    static Field field(String name)throws Exception{Field f=DeskConnection.class.getDeclaredField(name);f.setAccessible(true);return f;}
    static void send(DatagramSocket host,DatagramSocket client,byte[] bytes)throws Exception{host.send(new DatagramPacket(bytes,bytes.length,InetAddress.getLoopbackAddress(),client.getLocalPort()));}
    static boolean stats(DeskConnection connection,String value)throws Exception{
        Object state=((AtomicReference<?>)field("detailDiagnostics").get(connection)).get();
        return ((DetailDiagnostics)state).getHostResources().contains("CPU(core) "+value+"%");
    }
    public static void main(String[] args)throws Exception{
        DeskConnection connection=new DeskConnection(null);
        DatagramSocket first=new DatagramSocket();first.setBroadcast(true);
        DatagramSocket host=new DatagramSocket(0,InetAddress.getLoopbackAddress());
        field("control").set(connection,first);field("pendingRemote").set(connection,new InetSocketAddress(InetAddress.getLoopbackAddress(),host.getLocalPort()));field("running").set(connection,true);
        Method loop=DeskConnection.class.getDeclaredMethod("controlLoop");loop.setAccessible(true);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(()->{try{loop.invoke(connection);}catch(Throwable error){failure.set(error);}},"control-recovery-test");
        worker.start();
        try{
            for(int i=0;i<40&&!stats(connection,"11");i++){send(host,first,Protocol.INSTANCE.workspace("{\"type\":\"resourceStats\",\"cpuPct\":11}"));SystemClock.sleep(25);}
            if(!stats(connection,"11"))throw new AssertionError("Initial telemetry not received");
            send(host,first,new byte[]{1,2,3});
            send(host,first,Protocol.INSTANCE.workspace("not-json"));
            first.close();
            DatagramSocket replacement=null;
            for(int i=0;i<80;i++){replacement=(DatagramSocket)field("control").get(connection);if(replacement!=first&&!replacement.isClosed())break;SystemClock.sleep(25);}
            if(replacement==first||replacement==null||replacement.isClosed())throw new AssertionError("Control socket did not reopen");
            for(int i=0;i<40&&!stats(connection,"22");i++){send(host,replacement,Protocol.INSTANCE.workspace("{\"type\":\"resourceStats\",\"cpuPct\":22}"));SystemClock.sleep(25);}
            if(!worker.isAlive()||!stats(connection,"22"))throw new AssertionError("Telemetry failed after socket replacement");
            // An unchanged hello must rebuild dead workers, then retain the healthy replacement.
            Class<?> decoderType=Class.forName("dev.floatdesk.client.media.MonitorDecoder");
            Class<?> function=Class.forName("kotlin.jvm.functions.Function0"),function2=Class.forName("kotlin.jvm.functions.Function2");
            Object callback=java.lang.reflect.Proxy.newProxyInstance(function.getClassLoader(),new Class<?>[]{function},(proxy,method,values)->kotlin.Unit.INSTANCE);
            Object repair=java.lang.reflect.Proxy.newProxyInstance(function2.getClassLoader(),new Class<?>[]{function2},(proxy,method,values)->kotlin.Unit.INSTANCE);
            Object dead=decoderType.getConstructor(DatagramSocket.class,int.class,int.class,java.io.File.class,function,function,function2).newInstance(new DatagramSocket(),64,48,null,callback,callback,repair);
            java.util.Map<Integer,Object> decoders=(java.util.Map<Integer,Object>)field("decoders").get(connection);decoders.put(0,dead);
            DatagramSocket video=new DatagramSocket();field("videos").set(connection,java.util.List.of(video));
            MonitorHello monitor=new MonitorHello(0,64,48,video.getLocalPort(),123,"test");
            field("currentMonitors").set(connection,java.util.List.of(monitor));
            Method hello=DeskConnection.class.getDeclaredMethod("onHello",Packet.HostHello.class,InetSocketAddress.class);hello.setAccessible(true);
            Packet.HostHello packet=new Packet.HostHello(42,java.util.List.of(monitor));
            hello.invoke(connection,packet,new InetSocketAddress(InetAddress.getLoopbackAddress(),host.getLocalPort()));
            Object recovered=decoders.get(0);
            if(recovered==dead)throw new AssertionError("Dead decoder retained by identical-format handshake");
            for(int i=0;i<80&&!(Boolean)decoderType.getMethod("isHealthy").invoke(recovered);i++)SystemClock.sleep(25);
            if(!(Boolean)decoderType.getMethod("isHealthy").invoke(recovered))throw new AssertionError("Replacement decoder not healthy");
            hello.invoke(connection,packet,new InetSocketAddress(InetAddress.getLoopbackAddress(),host.getLocalPort()));
            if(decoders.get(0)!=recovered)throw new AssertionError("Healthy decoder unnecessarily replaced");
        }finally{for(Object decoder:((java.util.Map<?,?>)field("decoders").get(connection)).values())decoder.getClass().getMethod("stop").invoke(decoder);field("running").set(connection,false);((DatagramSocket)field("control").get(connection)).close();host.close();worker.join(1500);}
        if(worker.isAlive()||failure.get()!=null)throw new AssertionError("Control worker failed shutdown",failure.get());
        System.out.println("PASS actual control recovery, resumed telemetry, decoder reconciliation and immediate shutdown");
    }
}
