#!/usr/bin/env python3
"""Check lifecycle and transform ownership of independent curved resize affordances."""
from pathlib import Path
import subprocess
import sys
import tempfile
source=(Path(__file__).parents[1]/'app/src/main/java/dev/floatdesk/client/CurvedResizeHandles.kt').read_text()
source='\n'.join(line for line in source.splitlines() if not line.startswith(('package ','import ')))
stubs='''
internal data class FloatSize3d(val width:Float,val height:Float,val depth:Float)
internal data class Pose(val x:Float=0f)
internal class Session { val entities=mutableListOf<Entity>() }
internal class ResizeEvent(val marker:Int)
internal open class Entity {
    var parent:Entity?=null
    var pose=Pose(); var scale=1f; var enabled=true
    var resize:ResizableComponent?=null
    fun getPose()=pose
    fun setPose(p:Pose) { pose=p }
    fun getScale()=scale
    fun setScale(s:Float) { scale=s }
    fun setEnabled(e:Boolean) { enabled=e }
    fun addComponent(c:ResizableComponent):Boolean { resize=c; return true }
    fun removeComponent(c:ResizableComponent) { if(resize===c) resize=null }
    companion object {
        fun create(session:Session,name:String,pose:Pose,parent:Entity?)=Entity().also { it.pose=pose; it.parent=parent; session.entities.add(it) }
    }
}
internal class ResizableComponent(val listener:java.util.function.Consumer<ResizeEvent>) {
    var isFixedAspectRatioEnabled=false
    var isAutoHideContentWhileResizingEnabled=true
    var affordanceSize=FloatSize3d(1f,1f,1f)
    companion object {
        fun create(session:Session,minimumSize:FloatSize3d,maximumSize:FloatSize3d,resizeEventListener:java.util.function.Consumer<ResizeEvent>)=ResizableComponent(resizeEventListener)
    }
}
'''
# Kotlin properties in substitutes must not generate the same JVM signatures as the SDK methods.
stubs=stubs.replace('    var pose=','    @JvmField var pose=').replace('; var scale=','; @JvmField var scale=').replace('; var enabled=','; @JvmField var enabled=')
checks='''
fun main() {
    val session=Session(); val parent=Entity(); val video=Entity().also { it.parent=parent; it.pose=Pose(.2f); it.scale=.8f }
    val handles=CurvedResizeHandles(); val events=mutableListOf<Int>()
    handles.update(session,video,.8f,.5f,true) { events.add(it.marker) }
    val root=session.entities.single(); val component=root.resize!!
    check(root!==video && root.parent===parent && root.pose==video.pose && root.scale==.8f)
    check(component.isFixedAspectRatioEnabled && !component.isAutoHideContentWhileResizingEnabled)
    check(component.affordanceSize==FloatSize3d(.8f,.5f,.001f))
    component.listener.accept(ResizeEvent(1)); check(events==listOf(1))
    video.pose=Pose(.4f); video.scale=1.2f
    handles.update(session,video,1f,.625f,false) { events.add(it.marker*10) }
    check(session.entities.size==1 && root.pose==video.pose && root.scale==1.2f && !root.enabled)
    component.listener.accept(ResizeEvent(2)); check(events==listOf(1,20))
    handles.update(session,null,1f,.625f,true) { error("stale resize") }
    check(root.parent==null && root.resize==null)
    component.listener.accept(ResizeEvent(3)); check(events.size==2)
    handles.update(session,video,1f,.625f,true) { events.add(it.marker) }
    check(session.entities.size==2 && session.entities.last()!==root)
    component.listener.accept(ResizeEvent(4)); check(events.size==2)
    handles.close(); check(session.entities.last().parent==null)
    println("Passed: resize-only sibling entity, aspect/size, transform updates, grab suppression, fresh callback, mode-switch cleanup and recreation")
}
'''
lib=Path(sys.argv[1]);stdlib=next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-resize-') as temp:
    file=Path(temp)/'CurvedResizeCheck.kt';file.write_text(stubs+source+checks)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(file)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'CurvedResizeCheckKt'],check=True)
