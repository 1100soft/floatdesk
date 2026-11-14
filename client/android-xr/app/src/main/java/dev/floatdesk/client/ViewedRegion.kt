package dev.floatdesk.client

/** Clip the actual rendered triangles to the head frustum, interpolating source UV at cuts. */
internal fun viewedRegion(width:Float,height:Float,mesh:MonitorMesh?,horizontal:Float,vertical:Float,toView:(FloatArray)->FloatArray):FocusRect? {
    val tx=kotlin.math.tan(horizontal.coerceIn(.1f,1.4f))*1.05f
    val ty=kotlin.math.tan(vertical.coerceIn(.1f,1.4f))*1.05f
    val positions=mesh?.positions ?: floatArrayOf(-width/2,height/2,0f,-width/2,-height/2,0f,width/2,height/2,0f,width/2,-height/2,0f)
    val uv=mesh?.texCoords ?: floatArrayOf(0f,0f,0f,1f,1f,0f,1f,1f)
    val indices=mesh?.indices ?: intArrayOf(0,1,2,2,1,3)
    val vertices=Array(positions.size/3){i->val p=toView(positions.copyOfRange(i*3,i*3+3));floatArrayOf(p[0],p[1],p[2],uv[i*2],uv[i*2+1])}
    fun distance(v:FloatArray,plane:Int)=when(plane){0->-v[2]-.01f;1->-v[2]*tx-v[0];2->-v[2]*tx+v[0];3->-v[2]*ty-v[1];else->-v[2]*ty+v[1]}
    var left=1f;var top=1f;var right=0f;var bottom=0f
    for(i in indices.indices step 3){
        var polygon=listOf(vertices[indices[i]],vertices[indices[i+1]],vertices[indices[i+2]])
        if(polygon.any{v->v.any{!it.isFinite()}})continue
        for(plane in 0..4){
            if(polygon.isEmpty())break
            if(polygon.all{distance(it,plane)>=0})continue
            if(polygon.all{distance(it,plane)<0}){polygon=emptyList();break}
            val clipped=ArrayList<FloatArray>(5)
            var before=polygon.last();var dBefore=distance(before,plane)
            for(after in polygon){
                val dAfter=distance(after,plane)
                if((dBefore>=0)!=(dAfter>=0)){
                    val t=(dBefore/(dBefore-dAfter)).coerceIn(0f,1f)
                    clipped.add(FloatArray(5){before[it]+t*(after[it]-before[it])})
                }
                if(dAfter>=0)clipped.add(after)
                before=after;dBefore=dAfter
            }
            polygon=clipped
        }
        for(v in polygon){left=minOf(left,v[3]);right=maxOf(right,v[3]);top=minOf(top,v[4]);bottom=maxOf(bottom,v[4])}
    }
    return if(right>left && bottom>top)FocusRect(left.coerceIn(0f,1f),top.coerceIn(0f,1f),right.coerceIn(0f,1f),bottom.coerceIn(0f,1f)) else null
}

/** Quality targeting extends beyond the desktop; bounded mouse/input hit testing is unchanged. */
internal fun extendedQualityTarget(origin:FloatArray,direction:FloatArray,width:Float,height:Float,mesh:MonitorMesh?):Pair<Float,Float>? {
    if(!origin.all{it.isFinite()} || !direction.all{it.isFinite()})return null
    if(mesh==null){
        if(kotlin.math.abs(direction[2])<1e-7f)return null
        val t=-origin[2]/direction[2];if(t<=0)return null
        return (origin[0]+t*direction[0])/width+.5f to .5f-(origin[1]+t*direction[1])/height
    }
    val offset=FloatArray(3){origin[it]-mesh.eye[it]}
    fun dot(a:FloatArray,b:FloatArray)=(0..2).sumOf{(a[it]*b[it]).toDouble()}.toFloat()
    val a=dot(direction,direction);if(a<1e-12f)return null
    val b=dot(offset,direction);val c=dot(offset,offset)-mesh.radius*mesh.radius
    val discriminant=b*b-a*c;if(discriminant<0)return null
    val root=kotlin.math.sqrt(discriminant)
    val near=(-b-root)/a;val far=(-b+root)/a
    val t=if(near>0)near else far;if(t<=0)return null
    val radial=FloatArray(3){(offset[it]+t*direction[it])/mesh.radius}
    val x=dot(radial,mesh.frameTangentX);val y=dot(radial,mesh.frameTangentY)
    val sine=kotlin.math.sqrt(x*x+y*y);val forward=-dot(radial,mesh.frameNormal)
    if(sine<1e-6f && forward<0)return null // The antipode has no unique source direction.
    val angle=kotlin.math.atan2(sine,forward)
    val span=Math.PI.toFloat()-.01f-1.5f
    val sourceAngle=if(angle<=1.5f)angle else 1.5f-span*kotlin.math.ln((1f-(angle-1.5f)/span).coerceAtLeast(1e-6f))
    val gain=if(sine<1e-6f)1f/mesh.radiansPerMeter else sourceAngle/sine/mesh.radiansPerMeter
    val focus=mesh.focus ?: mesh.center
    val u=(focus[0]+x*gain)/width+.5f;val v=.5f-(focus[1]+y*gain)/height
    return if(u.isFinite() && v.isFinite())u to v else null
}

/** Independent quality filter; never falls back to the desktop center after a valid gaze. */
internal class QualityGazeTracker {
    private val filter=GazeStabilizer()
    var center:Pair<Float,Float>?=null
        private set
    fun update(raw:Pair<Float,Float>?,now:Long):Pair<Float,Float>? {
        filter.update(raw?.let{PointerTarget(0,it.first,it.second,floatArrayOf())},now)?.let{center=it.u to it.v}
        return center
    }
}

/** Preserve source texels in the final XR buffer, bounded to 16 MP and 8192 pixels/axis. */
internal fun qualityOutputSize(width:Int,height:Int,maxDimension:Int=8192):Pair<Int,Int> {
    if(width<=0 || height<=0)return 2 to 2
    val limit=maxDimension.coerceIn(2,8192).toDouble()
    val scale=minOf(1.0,limit/width,limit/height,kotlin.math.sqrt(16_777_216.0/(width.toDouble()*height)))
    return maxOf(2,(width*scale).toInt()/2*2) to maxOf(2,(height*scale).toInt()/2*2)
}

/** Honor coverage independently of the atlas pixel budget. */
internal fun qualityDenseFraction(requested:Float,rect:FocusRect,outputWidth:Int,outputHeight:Int):Float {
    return requested.coerceIn(.2f,1f)
}

/** Panels border the maximum Focus rectangle, rather than the outer headset field of view. */
internal data class HudPanelBox(val width:Float,val height:Float,val x:Float,val y:Float)
internal fun peripheralHudPanels(horizontal:Float,vertical:Float,distance:Float):List<HudPanelBox> {
    val h=horizontal.takeIf{it.isFinite() && it>.1f} ?: .6f
    val v=vertical.takeIf{it.isFinite() && it>.1f} ?: .5f
    val focused=focusViewHalfAngles(h,v)
    val innerX=kotlin.math.tan(focused.first)*distance
    val innerY=kotlin.math.tan(focused.second)*distance
    val margin=kotlin.math.tan(Math.toRadians(.6).toFloat())*distance
    val available=(kotlin.math.tan(v*.95f)*distance-innerY-margin).coerceAtLeast(distance*.01f)
    val height=minOf(innerX*.46f,available)
    val width=height*2f
    return listOf(-1f to 1f,1f to 1f,-1f to -1f,1f to -1f).map{(x,y)->
        HudPanelBox(width,height,x*(width*.5f+margin*.5f),y*(innerY+margin+height*.5f))
    }
}

/** Fixed layout of one HUD texture; source rectangles are reused by drawing and spherical mesh clipping. */
internal data class HudSurfaceLayout(val width:Float,val height:Float,val pixelWidth:Int,val pixelHeight:Int,val regions:List<FocusRect>)
internal fun hudSurfaceLayout(horizontal:Float,vertical:Float,distance:Float):HudSurfaceLayout {
    // FOV noise cannot resize or re-anchor the canvas on every text refresh.
    fun stable(angle:Float)=if(angle.isFinite())kotlin.math.round(angle*100f)/100f else angle
    val boxes=peripheralHudPanels(stable(horizontal),stable(vertical),distance)
    val width=boxes.maxOf{kotlin.math.abs(it.x)+it.width*.5f}*2f
    val height=boxes.maxOf{kotlin.math.abs(it.y)+it.height*.5f}*2f
    val scale=2048f/maxOf(width,height)
    val pixelWidth=maxOf(2,(width*scale).toInt()/2*2)
    val pixelHeight=maxOf(2,(height*scale).toInt()/2*2)
    return HudSurfaceLayout(width,height,pixelWidth,pixelHeight,boxes.map{
        FocusRect(.5f+(it.x-it.width*.5f)/width,.5f-(it.y+it.height*.5f)/height,
                  .5f+(it.x+it.width*.5f)/width,.5f-(it.y-it.height*.5f)/height)
    })
}
