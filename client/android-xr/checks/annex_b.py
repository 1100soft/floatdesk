#!/usr/bin/env python3
"""Compare zero-copy AVC keyframe classification with the full NAL splitter."""
from pathlib import Path
import subprocess,sys,tempfile
root=Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client'
source=(root/'media/AnnexB.kt').read_text().split('\n',1)[1]
code=source+r'''
fun main(){
 val random=kotlin.random.Random(142)
 val cases=mutableListOf(byteArrayOf(),byteArrayOf(0,0,1),byteArrayOf(0,0,0,1),byteArrayOf(0,0,1,0x65),byteArrayOf(0,0,0,1,0x65),byteArrayOf(0,0,1,0x67,0,0,1,0x68,0,0,1,0x61))
 repeat(5000){
  val data=ByteArray(random.nextInt(256)){random.nextInt(256).toByte()}
  if(data.size>8 && it%2==0){val p=random.nextInt(data.size-5);data[p]=0;data[p+1]=0;data[p+2]=1;data[p+3]=if(it%4==0)0x65 else 0x61}
  cases.add(data)
 }
 for(data in cases){
  check(isKeyframe(data)==splitAnnexB(data).any{nalType(it)==5})
  val ranges=mutableListOf<ByteArray>()
  visitAnnexB(data){start,end->check(start in 0..end && end<=data.size);ranges.add(data.copyOfRange(start,end))}
  check(ranges.size==splitAnnexB(data).size)
  ranges.zip(splitAnnexB(data)).forEach{(a,b)->check(a.contentEquals(b))}
 }
 val retained=mutableListOf<ByteArray>()
 val payload=byteArrayOf(0,0,1,0x67,1,0,0,1,0x68,2,0,0,1,0x65,3)
 visitAnnexB(payload){start,end->if(start<end && (payload[start].toInt() and 31) in 7..8)retained.add(payload.copyOfRange(start,end))}
 check(retained.size==2 && retained[0].contentEquals(byteArrayOf(0x67,1)) && retained[1].contentEquals(byteArrayOf(0x68,2)))
 val key=ByteArray(1024*1024){0x44};key[0]=0;key[1]=0;key[2]=1;key[3]=0x65
 repeat(100){check(isKeyframe(key))}
 println("PASS zero-copy AVC IDR detection, 3/4-byte prefixes, empty/truncated headers and seeded splitter equivalence")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-annex-') as tmp:
 file=Path(tmp)/'AnnexCheck.kt';file.write_text(code)
 subprocess.run(['java','-Xmx512m','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',tmp,str(file)],check=True)
 subprocess.run(['java','-cp',tmp+':'+str(stdlib),'AnnexCheckKt'],check=True)
