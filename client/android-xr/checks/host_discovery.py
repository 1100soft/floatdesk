#!/usr/bin/env python3
"""Reproduce dual-interface discovery without weakening reply-address matching."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'net/HostDiscovery.kt').read_text().split('\n',1)[1]
code=source+'''
fun main() {
 val wifi=InetSocketAddress("192.168.1.98",47000);val ethernet=InetSocketAddress("192.168.1.137",47000);val stranger=InetSocketAddress("192.168.1.2",47000)
 val discovery=HostDiscovery()
 discovery.observe(wifi,"computer",0);discovery.observe(ethernet,"computer",1);discovery.observe(stranger,"another computer",2)
 check(discovery.select(null,false,2)==wifi)
 check(discovery.select(wifi,false,2000)==wifi)
 check(discovery.select(wifi,false,2502)==ethernet) // Reply source differs from first beacon: retry the other advertised interface.
 check(discovery.select(ethernet,true,6000)==ethernet) // A healthy connection stays bound.
 check(discovery.select(ethernet,false,6001)==wifi)
 discovery.observe(ethernet,"computer",12000)
 check(discovery.select(wifi,false,12000)==ethernet) // Expired interfaces cannot trap retries.
 discovery.clear();check(discovery.select(null,false,13000)==null)
 discovery.observe(stranger,"another computer",13000)
 check(discovery.select(wifi,false,13001)==wifi) // No same-name advertisement: do not switch to another machine.
 // Actual UDP: wildcard server replies from .1 although the first advertised address is .2.
 java.net.DatagramSocket(0,java.net.InetAddress.getByName("0.0.0.0")).use { server->
  java.net.DatagramSocket(0,java.net.InetAddress.getByName("127.0.0.1")).use { client->
   server.soTimeout=1000;client.soTimeout=1000
   val preferred=InetSocketAddress("127.0.0.2",server.localPort);val replying=InetSocketAddress("127.0.0.1",server.localPort)
   val routes=HostDiscovery();routes.observe(preferred,"host",0);routes.observe(replying,"host",0)
   var selected=routes.select(null,false,0)!!
   fun exchange():Boolean {
    client.send(java.net.DatagramPacket(byteArrayOf(1),1,selected))
    val request=java.net.DatagramPacket(ByteArray(8),8);server.receive(request)
    server.send(java.net.DatagramPacket(byteArrayOf(2),1,request.socketAddress))
    val response=java.net.DatagramPacket(ByteArray(8),8);client.receive(response)
    return response.socketAddress==selected
   }
   check(!exchange())
   selected=routes.select(selected,false,2500)!!
   check(selected==replying && exchange())
  }
 }
 println("PASS real asymmetric UDP reply/retry; dual-interface retry, stable healthy peer, stale-candidate expiry, unrelated-host isolation and disconnect reset")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-discovery-') as temp:
 p=Path(temp)/'DiscoveryCheck.kt';p.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(p)],check=True)
 subprocess.run(['java','-Xmx128m','-cp',temp+':'+str(stdlib),'DiscoveryCheckKt'],check=True)
