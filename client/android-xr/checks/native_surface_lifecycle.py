#!/usr/bin/env python3
"""Compile the production native video surface state machine whole and check its enforced order."""
from pathlib import Path
import subprocess
import sys
import tempfile
from production_sources import ROOT, native_journal

source=(ROOT/'NativeVideoSurface.kt').read_text()
assert 'import android' not in source and 'import androidx' not in source, 'NativeVideoSurface must stay platform-free'
panel=(ROOT/'WindowMagnifierPanel.kt').read_text()
for marker in ('resources.lifecycle.allocate()','resources.lifecycle.textureSettled()','resources.lifecycle.attachProducer()','resources.lifecycle.attachInput()','lifecycle.retire('):
    assert marker in panel, f'magnifier panes must use the surface lifecycle: {marker}'
main='''
class Port(val calls:MutableList<String>):NativeVideoPort{
    override fun detachProducer(){calls.add("producer")}
    override fun detachInput(){calls.add("input")}
    override fun releaseGeometry(){calls.add("geometry")}
}
fun main(){
    val lines=mutableListOf<String>()
    NativeJournal.install(1,{0L}){lines.add(it)}
    val s=NativeSurfaceLifecycle{NativeTag("pane:1",generation=1)}
    check(!s.attachProducer() && !s.attachInput() && !s.present() && !s.retire(Port(mutableListOf())))
    check(s.refusals==4 && lines.count{it.contains("op=refused")}==4) // Refusals are logged.
    check(s.allocate() && !s.allocate()) // A second allocation before retirement is refused.
    check(!s.textureSettled()) // Texture initialization begins only once creation returns.
    check(s.allocated() && s.state==NativeSurfaceState.TextureInit)
    check(!s.attachProducer() && !s.attachInput()) // No producer or collider while the texture initializes.
    check(s.textureSettled() && s.attachInput() && s.attachProducer() && s.present())
    repeat(20){s.hide();check(s.state==NativeSurfaceState.Hidden);check(s.present())} // Hide and show reuse one surface.
    s.producerDetached()
    check(!s.present()) // A detached producer cannot reveal a stale canvas.
    check(s.attachProducer() && s.present())
    val calls=mutableListOf<String>()
    check(s.retire(Port(calls)) && calls==listOf("producer","input","geometry") && s.state==NativeSurfaceState.Released)
    check(!s.retire(Port(mutableListOf()))) // Released twice is refused.
    val partial=mutableListOf<String>()
    check(s.allocate() && s.allocated() && s.retire(Port(partial)) && partial==listOf("geometry")) // Retire during texture init.
    check(s.allocate() && s.allocationFailed() && s.state==NativeSurfaceState.Released && s.allocate())
    check(lines.any{it.contains("op=retire")})
    println("Passed: native surface lifecycle refuses out-of-order allocation, attachment, presentation and retirement, and retires producer and input before geometry")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-surface-') as temp:
    code=Path(temp)/'SurfaceLifecycleCheck.kt';code.write_text(native_journal()+source.split('\n',1)[1]+main)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(code)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'SurfaceLifecycleCheckKt'],check=True)
