#!/usr/bin/env python3
"""Validate the real frozen-frame GLB writer, including embedded image and pixel orientation."""
from pathlib import Path
import json
import struct
import subprocess
import sys
import tempfile
import zlib

source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/FrozenMonitor.kt').read_text()
writer = source[source.index('internal fun frozenFrameGlb'):]
lib = Path(sys.argv[1]); stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
def chunk(kind, data):
    return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
# Two rows, four distinct corner colours, no Android or imaging dependencies.
png = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,2,8,6,0,0,0))
png += chunk(b'IDAT',zlib.compress(b'\0\xff\0\0\xff\0\xff\0\xff\0\0\0\xff\xff\xff\xff\0\xff'))+chunk(b'IEND',b'')
with tempfile.TemporaryDirectory(prefix='floatdesk-snapshot-') as directory:
    folder = Path(directory); (folder/'frame.png').write_bytes(png)
    harness = 'import java.nio.ByteBuffer\nimport java.nio.ByteOrder\n'+writer+'\nfun main(args: Array<String>) { java.io.File(args[1]).writeBytes(frozenFrameGlb(java.io.File(args[0]).readBytes())) }\n'
    test = folder/'FrozenFrameCheck.kt'; test.write_text(harness)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',directory,str(test)],check=True)
    subprocess.run(['java','-cp',directory+':'+str(stdlib),'FrozenFrameCheckKt',str(folder/'frame.png'),str(folder/'frame.glb')],check=True)
    data = (folder/'frame.glb').read_bytes()
    assert struct.unpack_from('<III',data)==(0x46546c67,2,len(data))
    json_size,kind = struct.unpack_from('<II',data,12); assert kind==0x4e4f534a and json_size%4==0
    doc=json.loads(data[20:20+json_size]); binary_size,kind=struct.unpack_from('<II',data,20+json_size)
    assert kind==0x004e4942 and binary_size%4==0
    binary=data[28+json_size:]; assert len(binary)==binary_size
    assert binary_size-3 <= doc['buffers'][0]['byteLength'] <= binary_size
    for view in doc['bufferViews']: assert view['byteOffset']+view['byteLength'] <= doc['buffers'][0]['byteLength']
    image = doc['bufferViews'][4]; assert binary[image['byteOffset']:image['byteOffset']+image['byteLength']]==png
    positions=struct.unpack_from('<12f',binary,0); uv=struct.unpack_from('<8f',binary,96)
    assert tuple(positions[9:12])==(-.5,.5,0) and tuple(uv[6:8])==(0,0) # top-left
    assert tuple(positions[3:6])==(.5,-.5,0) and tuple(uv[2:4])==(1,1) # bottom-right
    assert struct.unpack_from('<6H',binary,128)==(0,1,2,0,2,3)
    material=doc['materials'][0]
    assert material['alphaMode']=='OPAQUE' and 'KHR_materials_unlit' in material['extensions']
    assert material['pbrMetallicRoughness']['baseColorTexture']['index']==0
    print('Passed: frozen-frame GLB container, buffer bounds/alignment, embedded PNG, upright UVs, opaque unlit material')
