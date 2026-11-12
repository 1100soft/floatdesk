#!/usr/bin/env python3
"""Validate the real frozen-frame GLB writer, including embedded image and pixel orientation."""
from pathlib import Path
import json
import math
import struct
import subprocess
import sys
import tempfile
import zlib

source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/FrozenMonitor.kt').read_text()
writer = source[source.index('internal fun frozenFrameGlb'):]
mesh_source = (Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client/MonitorMesh.kt').read_text().split('\n',1)[1]
lib = Path(sys.argv[1]); stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
def chunk(kind, data):
    return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
# Two rows, four distinct corner colours, no Android or imaging dependencies.
png = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',2,2,8,6,0,0,0))
png += chunk(b'IDAT',zlib.compress(b'\0\xff\0\0\xff\0\xff\0\xff\0\0\0\xff\xff\xff\xff\0\xff'))+chunk(b'IEND',b'')
with tempfile.TemporaryDirectory(prefix='floatdesk-snapshot-') as directory:
    folder = Path(directory); (folder/'frame.png').write_bytes(png)
    harness = 'import java.nio.ByteBuffer\nimport java.nio.ByteOrder\n'+mesh_source+'\n'+writer+'\nfun main(args: Array<String>) { val png = java.io.File(args[0]).readBytes(); java.io.File(args[1]).writeBytes(frozenFrameGlb(png)); java.io.File(args[2]).writeBytes(frozenFrameGlb(png, MonitorMesh(1.8f,1.1f,floatArrayOf(.3f,-.2f,1.2f),focus=floatArrayOf(.8f,.2f)))); java.io.File(args[4]).writeBytes(frozenFrameGlb(png,transparent=true)); java.io.File(args[3]).writeBytes(frozenFrameGlb(png, MonitorMesh(1.8f,1.1f,floatArrayOf(.3f,-.2f,1.2f),focus=floatArrayOf(.8f,.2f)),true)); java.io.File(args[5]).writeBytes(interactionGridGlb(png)) }\n'
    test = folder/'FrozenFrameCheck.kt'; test.write_text(harness)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',directory,str(test)],check=True)
    subprocess.run(['java','-cp',directory+':'+str(stdlib),'FrozenFrameCheckKt',str(folder/'frame.png'),str(folder/'frame.glb'),str(folder/'curved.glb'),str(folder/'input.glb'),str(folder/'transparent.glb'),str(folder/'grid.glb')],check=True)
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

    curved = (folder/'curved.glb').read_bytes()
    size = struct.unpack_from('<I',curved,12)[0]
    mesh_doc = json.loads(curved[20:20+size]); mesh_binary = curved[28+size:]
    n = mesh_doc['accessors'][0]['count']
    xyz = struct.unpack_from('<%sf' % (n*3),mesh_binary)
    assert n > 4
    radius = math.sqrt(.3**2 + .2**2 + 1.2**2)
    for i in range(0,len(xyz),3):
        actual = (xyz[i]*1.8,xyz[i+1]*1.1,xyz[i+2]*1.8)
        assert abs(math.dist(actual,(.3,-.2,1.2))-radius) < .00001
    image = mesh_doc['bufferViews'][4]
    assert mesh_binary[image['byteOffset']:image['byteOffset']+image['byteLength']] == png
    for view in mesh_doc['bufferViews']:
        assert view['byteOffset'] % 4 == 0
        assert view['byteOffset']+view['byteLength'] <= len(mesh_binary)
    index_view = mesh_doc['bufferViews'][3]
    indices = struct.unpack_from('<%sH' % mesh_doc['accessors'][3]['count'],mesh_binary,index_view['byteOffset'])
    assert max(indices) < n
    print('Passed: curved snapshot vertices preserve spherical radius after entity scaling, PNG, aligned buffers, valid indices')

    invisible=(folder/'input.glb').read_bytes()
    size=struct.unpack_from('<I',invisible,12)[0]
    doc=json.loads(invisible[20:20+size])
    material=doc['materials'][0]
    assert material['alphaMode']=='BLEND' and material['doubleSided']
    assert material['pbrMetallicRoughness']['baseColorFactor'][3]==0
    assert invisible[28+size:] == mesh_binary
    print('Passed: invisible interaction patch retains exact spherical vertices, indices and UVs')

    transparent=(folder/'transparent.glb').read_bytes()
    size=struct.unpack_from('<I',transparent,12)[0]
    assert json.loads(transparent[20:20+size])['materials'][0]['alphaMode']=='MASK'
    print('Passed: frozen presentation preserves transparent layout holes')

    grid=(folder/'grid.glb').read_bytes()
    size=struct.unpack_from('<I',grid,12)[0];doc=json.loads(grid[20:20+size])
    assert len(doc['nodes'])==384 and len(doc['meshes'])==1 and len(doc['materials'])==1
    assert doc['scenes'][0]['nodes']==list(range(0,384,2))
    assert all(doc['nodes'][i]['children']==[i+1] and doc['nodes'][i+1]['mesh']==0 for i in range(0,384,2))
    assert struct.unpack_from('<I',grid,8)[0]==len(grid)
    assert len(grid)<150000
    print('Passed: reusable 192-triangle collider grid shares one triangle mesh/material and bounded asset memory')
