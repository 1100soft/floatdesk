#!/usr/bin/env python3
"""Generate tiny unlit native glTF meshes for the grab guide (no input components)."""
import json
import math
from pathlib import Path
import struct

out = Path(__file__).parents[1] / 'app/src/main/assets/grab-guides'
out.mkdir(parents=True, exist_ok=True)
def write(name, vertices, normals, indices, color, colors=None):
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
    if colors is not None:
        data += b'\0' * (-len(data) % 4)
        offset = len(data)
        channels = len(colors[0])
        packed = struct.pack('<%sf' % (len(colors) * channels), *(x for v in colors for x in v))
        data += packed
        doc['buffers'][0]['byteLength'] = len(data)
        doc['bufferViews'].append({'buffer': 0, 'byteOffset': offset, 'byteLength': len(packed), 'target': 34962})
        doc['accessors'].append({'bufferView': 3, 'componentType': 5126, 'count': len(colors), 'type': f'VEC{channels}'})
        doc['meshes'][0]['primitives'][0]['attributes']['COLOR_0'] = 3
        if channels == 4:
            doc['materials'][0]['alphaMode'] = 'BLEND'
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

# The connector uses vertex colors so a single retained node renders the gradient.
cyan = (.15, .95, 1)
violet = (.68, .34, 1)
write('layout-depth.glb', vertices, normals, indices, [1, 1, 1, 1],
      [cyan if v[0] < 0 else violet for v in vertices])

# Two perpendicular great circles on one sphere, with no per-segment scene nodes.
rings = []
ring_normals = []
ring_indices = []
for vertical in (False, True):
    start = len(rings)
    segments = 96
    sides = 4
    for step in range(segments):
        theta = math.tau * step / segments
        center = (0, math.sin(theta), -math.cos(theta)) if vertical else (math.sin(theta), 0, -math.cos(theta))
        lateral = (1, 0, 0) if vertical else (0, 1, 0)
        for side in range(sides):
            phi = math.tau * side / sides + math.pi / 4
            normal = tuple(center[i] * math.cos(phi) + lateral[i] * math.sin(phi) for i in range(3))
            rings.append(tuple(center[i] + .0015 * normal[i] for i in range(3)))
            ring_normals.append(normal)
    for step in range(segments):
        for side in range(sides):
            a = start + step * sides + side
            b = start + step * sides + (side + 1) % sides
            c = start + ((step + 1) % segments) * sides + side
            d = start + ((step + 1) % segments) * sides + (side + 1) % sides
            ring_indices.extend((a, b, c, b, d, c))
for name, color in [('cyan', cyan), ('violet', violet)]:
    write(f'layout-{name}-rings.glb', rings, ring_normals, ring_indices, [*color, 1])

# A translucent halo around a bright core, with one retained mesh for the grip.
glow_vertices = []
glow_normals = []
glow_indices = []
glow_colors = []
for radius, opacity in ((1.42, .045), (1.28, .09), (1.14, .18), (1, 1)):
    start = len(glow_vertices)
    glow_vertices.extend(tuple(radius * coord for coord in vertex) for vertex in sphere)
    glow_normals.extend(sphere)
    glow_indices.extend(start + index for index in tri)
    glow_colors.extend((*cyan, opacity) for _ in sphere)
write('layout-origin.glb', glow_vertices, glow_normals, glow_indices, [1, 1, 1, 1], glow_colors)
