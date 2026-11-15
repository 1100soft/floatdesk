#!/usr/bin/env python3
"""Generate tiny unlit native glTF meshes for the grab guide (no input components)."""
import json
import math
from pathlib import Path
import struct

out = Path(__file__).parents[1] / 'app/src/main/assets/grab-guides'
out.mkdir(parents=True, exist_ok=True)
def write(name, vertices, normals, indices, color):
    position_bytes = struct.pack('<%sf' % (len(vertices)*3), *(x for v in vertices for x in v))
    normal_bytes = struct.pack('<%sf' % (len(normals)*3), *(x for v in normals for x in v))
    index_bytes = struct.pack('<%sH' % len(indices), *indices)
    data = position_bytes + normal_bytes + index_bytes
    doc = {
        'asset': {'version':'2.0', 'generator':'Floatdesk grab guide generator'},
        'extensionsUsed':['KHR_materials_unlit'],
        'scene':0, 'scenes':[{'nodes':[0]}], 'nodes':[{'mesh':0}],
        'meshes':[{'primitives':[{'attributes':{'POSITION':0,'NORMAL':1},'indices':2,'material':0}]}],
        'materials':[{'extensions':{'KHR_materials_unlit':{}},'pbrMetallicRoughness':{'baseColorFactor':color,'metallicFactor':0,'roughnessFactor':1},'doubleSided':True}],
        'buffers':[{'byteLength':len(data)}],
        'bufferViews':[{'buffer':0,'byteOffset':0,'byteLength':len(position_bytes),'target':34962}, {'buffer':0,'byteOffset':len(position_bytes),'byteLength':len(normal_bytes),'target':34962}, {'buffer':0,'byteOffset':len(position_bytes)+len(normal_bytes),'byteLength':len(index_bytes),'target':34963}],
        'accessors':[{'bufferView':0,'componentType':5126,'count':len(vertices),'type':'VEC3','min':[min(v[i] for v in vertices) for i in range(3)],'max':[max(v[i] for v in vertices) for i in range(3)]}, {'bufferView':1,'componentType':5126,'count':len(normals),'type':'VEC3'}, {'bufferView':2,'componentType':5123,'count':len(indices),'type':'SCALAR'}],
    }
    encoded = json.dumps(doc,separators=(',',':')).encode()
    encoded += b' ' * (-len(encoded)%4)
    data += b'\0' * (-len(data)%4)
    glb = struct.pack('<III',0x46546c67,2,12+8+len(encoded)+8+len(data)) + struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(data),0x004e4942)+data
    (out/name).write_bytes(glb)

# A centered unit box; the mesh node scales it into a thin beam.
cube = [(x,y,z) for x in (-.5,.5) for y in (-.5,.5) for z in (-.5,.5)]
faces = [(0,1,3,2),(4,6,7,5),(0,4,5,1),(2,3,7,6),(0,2,6,4),(1,5,7,3)]
face_normals = [(-1,0,0),(1,0,0),(0,-1,0),(0,1,0),(0,0,-1),(0,0,1)]
vertices=[]; normals=[]; indices=[]
for face,normal in zip(faces,face_normals):
    i=len(vertices); vertices.extend(cube[x] for x in face); normals.extend([normal]*4)
    indices.extend([i,i+1,i+2,i,i+2,i+3])
for name,color in [('cyan',[.15,.95,1,1]),('violet',[.68,.34,1,1])]:
    write(name+'-beam.glb',vertices,normals,indices,color)
    sphere=[]; tri=[]
    for row in range(9):
        phi=math.pi*row/8
        for col in range(17):
            theta=math.tau*col/16
            sphere.append((math.sin(phi)*math.cos(theta),math.cos(phi),math.sin(phi)*math.sin(theta)))
    for row in range(8):
        for col in range(16):
            a=row*17+col; b=a+17; tri.extend([a,b,a+1,a+1,b,b+1])
    write(name+'-point.glb',sphere,sphere,tri,color)
