#!/usr/bin/env python3
"""Verify the magnifier's immutable native-mesh reuse policy."""
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).parents[1] / 'app/src/main/java/dev/floatdesk/client'
panel = (root / 'WindowMagnifierPanel.kt').read_text()
assert 'entity.shape=' not in panel.replace(' ', '')
assert 'resources.curvedInput?.entity ?: resources.entity' in panel
assert 'curvedInput?.detach()' in panel
assert panel.count('.updateGeometry(') == 1
assert 'forSphericalAperture' in panel
assert 'transparentFit=sphericalFit' in panel
assert 'if(regionId==null)entity.setSurfacePixelDimensions' in panel
assert 'if(regionId!=null)connection.deferNativeMeshUpdates()' in panel
assert 'transientSurface && regionId==null' in panel
assert 'resources.authoredSize==authoredSize' in panel
assert 'val authoredSize=savedPaneSize' in panel
source = (root / 'WindowMagnification.kt').read_text()
start = source.index('internal fun sameNativePaneExtent(')
end = source.index('/** Parent-first traversal', start)
code = 'import kotlin.math.abs\n' + '''
internal data class MagnifiedWindow(val width:Float,val height:Float,val spherical:Boolean)
''' + source[start:end] + '''
fun main() {
    for(curved in listOf(false,true)){
        val pane=MagnifiedWindow(1.2f,.8f,curved)
        check(sameNativePaneExtent(pane,pane.copy()))
        check(sameNativePaneExtent(pane,pane.copy(width=1.201f)))
        check(!sameNativePaneExtent(pane,pane.copy(width=1.5f)))
        check(!sameNativePaneExtent(pane,pane.copy(height=.9f)))
        check(!sameNativePaneExtent(pane,pane.copy(spherical=!curved)))
        check(!sameNativePaneExtent(null,pane))
    }
    val original=retainedChildSlots(emptyList(),listOf(41L,42L))
    check(original==listOf(41L,42L))
    check(retainedChildSlots(original,listOf(42L,41L))==original)
    check(retainedChildSlots(original,listOf(42L))==listOf(null,42L))
    check(retainedChildSlots(original,listOf(42L,43L))==listOf(43L,42L))
    println("PASS immutable magnifier meshes, reusable extent, and flat/curved separation")
}
'''
lib = Path(sys.argv[1])
stdlib = next(lib.glob('kotlin-stdlib-*.jar'))
with tempfile.TemporaryDirectory(prefix='floatdesk-magnifier-transition-') as temp:
    path = Path(temp) / 'MagnifierTransition.kt'
    path.write_text(code)
    subprocess.run(['java','-cp',str(lib/'*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib','-no-reflect','-classpath',str(stdlib),'-d',temp,str(path)],check=True)
    subprocess.run(['java','-cp',temp+':'+str(stdlib),'MagnifierTransitionKt'],check=True)
