#!/usr/bin/env python3
"""Compile the production native lifecycle journal whole and check ordering, bounds and rate limits."""
from pathlib import Path
import subprocess
import sys
import tempfile

source=(Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client/NativeJournal.kt').read_text()
assert 'import android' not in source and 'import androidx' not in source, 'NativeJournal must stay platform-free'
main='''
fun main() {
    var now=1000L
    val lines=mutableListOf<String>()
    NativeJournal.install(4242,{now}){lines.add(it)}
    val tag=NativeTag("pane:7",owner=0x3a00001L,child=0x3a00042L,generation=3,curved=true)
    var seen=""
    NativeJournal.native(tag,"create","target=video"){seen=lines.last()}
    check(seen==lines.last() && seen.contains("op=create")) // Recorded before the native call runs.
    for(field in listOf("t=1000","pid=4242","thread=","pane=pane:7","owner=${0x3a00001L}","child=${0x3a00042L}","gen=3","mode=curved","target=video"))
        check(seen.contains(field)){"missing $field in $seen"}

    var calls=0
    repeat(10){NativeJournal.enabled(tag,"video",true){calls++}}
    check(calls==10 && lines.count{it.contains("op=enabled")}==1)
    NativeJournal.enabled(tag,"video",false){calls++}
    check(calls==11 && lines.count{it.contains("op=enabled")}==2)
    NativeJournal.enabled(tag.copy(generation=4),"video",false){calls++}
    check(lines.count{it.contains("op=enabled")}==3) // A new surface generation logs its first state.

    val before=lines.size
    NativeJournal.shape(tag,"mesh",960)
    check(lines.size==before+1 && lines.last().contains("op=shape") && lines.last().contains("vertices=960"))
    repeat(100){NativeJournal.shape(tag,"mesh",960)}
    check(lines.size==before+2 && lines.last().contains("op=shape-summary") && lines.last().contains("pane:7=1"))
    now+=NativeJournal.SummaryIntervalMs-1
    repeat(50){NativeJournal.shape(tag,"mesh",960)}
    check(lines.size==before+2) // At most one summary per interval.
    now+=1
    NativeJournal.shape(tag,"mesh",960)
    check(lines.size==before+3 && lines.last().contains("pane:7=150"))
    NativeJournal.shape(tag,"mesh",1200)
    check(lines.last().contains("op=shape ") && lines.last().contains("vertices=1200"))
    NativeJournal.shape(tag.copy(generation=5),"mesh",1200)
    check(lines.last().contains("gen=5") && lines.last().contains("op=shape "))

    repeat(NativeJournal.Capacity*3){NativeJournal.record(NativeTag("churn"),"create","i=$it")}
    val recent=NativeJournal.recent()
    check(recent.size==NativeJournal.Capacity && recent.last().endsWith("i=${NativeJournal.Capacity*3-1}"))
    println("Passed: journal records before native calls, carries pane/owner/child/generation/mode, logs visibility and shape changes only, summarizes repeats once per interval and keeps a bounded ring")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-journal-') as temp:
    code=Path(temp)/'NativeJournalCheck.kt';code.write_text(source.split('\n',1)[1]+main)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(code)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'NativeJournalCheckKt'],check=True)
print('Passed: native journal')
