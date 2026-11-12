package dev.floatdesk.client.workspace

/** Without a restored room anchor, keep the saved arrangement relative to the startup viewer. */
internal fun WorkspaceFile.restoreViewer(viewer:Pose):WorkspaceFile {
    val prior=viewerReference ?: Pose()
    if(anchorUuid!=null)return this
    fun multiply(a:FloatArray,b:FloatArray)=floatArrayOf(a[3]*b[0]+a[0]*b[3]+a[1]*b[2]-a[2]*b[1],a[3]*b[1]-a[0]*b[2]+a[1]*b[3]+a[2]*b[0],a[3]*b[2]+a[0]*b[1]-a[1]*b[0]+a[2]*b[3],a[3]*b[3]-a[0]*b[0]-a[1]*b[1]-a[2]*b[2])
    val q=multiply(viewer.rotation,floatArrayOf(-prior.rotation[0],-prior.rotation[1],-prior.rotation[2],prior.rotation[3]))
    fun transform(p:Pose):Pose {
        val v=FloatArray(3){p.position[it]-prior.position[it]}
        val t=floatArrayOf(2*(q[1]*v[2]-q[2]*v[1]),2*(q[2]*v[0]-q[0]*v[2]),2*(q[0]*v[1]-q[1]*v[0]))
        val rotated=floatArrayOf(v[0]+q[3]*t[0]+q[1]*t[2]-q[2]*t[1],v[1]+q[3]*t[1]+q[2]*t[0]-q[0]*t[2],v[2]+q[3]*t[2]+q[0]*t[1]-q[1]*t[0])
        return Pose(FloatArray(3){viewer.position[it]+rotated[it]},multiply(q,p.rotation))
    }
    return copy(viewerReference=viewer,layouts=layouts.mapValues {(_,screens)->screens.map {it.copy(pose=transform(it.pose))}.toMutableList()}.toMutableMap(),control=control.copy(pose=transform(control.pose)))
}
