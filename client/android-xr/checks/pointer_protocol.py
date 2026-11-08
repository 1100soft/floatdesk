#!/usr/bin/env python3
"""Check the production Android pointer packet against the Rust protocol's wire fixture."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile

folder = Path(__file__).parents[1]
source = (folder/'app/src/main/java/dev/floatdesk/client/net/Protocol.kt').read_text().split('\n', 1)[1]
lib = Path(sys.argv[1])
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
code = source + '''
fun main(args: Array<String>) {
    java.io.File(args[0]).writeBytes(Protocol.pointerMove(2,.25f,1f,-1))
    java.io.File(args[1]).writeBytes(Protocol.pointerClick(2,.25f,1f,3,-1))
    java.io.File(args[2]).writeBytes(Protocol.touchpad(4,.25f,1f,-1))
    check(runCatching { Protocol.touchpad(1,.5f,.5f,0) }.isFailure)
    for (bad in listOf(Float.NaN,Float.POSITIVE_INFINITY,-.001f,1.001f)) {
        check(runCatching { Protocol.pointerMove(0,bad,0f,0) }.isFailure)
        check(runCatching { Protocol.pointerMove(0,0f,bad,0) }.isFailure)
    }
}
'''
with tempfile.TemporaryDirectory(prefix='floatdesk-pointer-') as temp:
    path = Path(temp)
    (path/'PointerProtocolCheck.kt').write_text(code)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,
                    str(path/'PointerProtocolCheck.kt')],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'PointerProtocolCheckKt',str(path/'packet.bin'),str(path/'click.bin'),str(path/'pad.bin')],check=True)
    expected = b'FDC1'+struct.pack('<BBHII',1,8,0,14,0)+struct.pack('<BffIB',2,.25,1,0xffffffff,0)
    assert (path/'packet.bin').read_bytes() == expected
    assert (path/'click.bin').read_bytes() == b'FDC1'+struct.pack('<BBHII',1,9,0,14,0)+struct.pack('<BffBI',2,.25,1,3,0xffffffff)
    assert (path/'pad.bin').read_bytes() == b'FDC1'+struct.pack('<BBHII',1,10,0,13,0)+struct.pack('<BffI',4,.25,1,0xffffffff)
print('Passed: Android/Rust pointer wire fixture, unsigned sequence, finite normalized coordinates')
