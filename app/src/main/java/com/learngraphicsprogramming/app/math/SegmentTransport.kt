package com.learngraphicsprogramming.app.math

import com.learngraphicsprogramming.app.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/** Free segment construction. Coordinates belong to geometry, never to learner controls. */
internal data class SegmentTransportPoint(val position: EuclideanPosition, val label: String)
internal data class SegmentTransportSource(val id: String, val from: SegmentTransportPoint, val to: SegmentTransportPoint) {
    val length get() = hypot(to.position.x-from.position.x,to.position.y-from.position.y)
}

internal data class SegmentTransportSpec(
    val mode: String, val sources: List<SegmentTransportSource>, val origin: SegmentTransportPoint,
    val direction: EuclideanPosition, val end: SegmentTransportPoint?, val lengths: List<Double>,
    val steps: List<String>, val pointLabels: List<String>, val initial: List<EuclideanPosition>,
    val positionTolerance: Double, val lengthTolerance: Double
) {
    val pointCount get() = pointLabels.size
    val total get() = lengths.sum()

    companion object {
        fun parse(raw: JSONObject): SegmentTransportSpec {
            require(raw.optString("type")=="segment_transport") { "A segment construction requires its own validator" }
            val mode=raw.getString("mode")
            require(mode in setOf("chain","partition")) { "Unknown segment construction mode" }
            val sourceArray=if(raw.has("sources")) raw.getJSONArray("sources") else JSONArray()
            require(sourceArray.length()<=32) { "At most 32 given source segments are supported" }
            val sources=List(sourceArray.length()) { index ->
                val value=sourceArray.getJSONObject(index)
                SegmentTransportSource(identifier(value.get("id")),namedPoint(value.getJSONObject("from")),namedPoint(value.getJSONObject("to"))).also {
                    require(it.length.isFinite()&&it.length>=MIN_LENGTH&&it.length<=LIMIT) { "Source endpoints must define a positive supported length" }
                }
            }
            require(sources.map { it.id }.distinct().size==sources.size) { "Source segment IDs must be unique" }
            val target=raw.getJSONObject("target")
            val origin=namedPoint(target.getJSONObject("origin"))
            val end: SegmentTransportPoint?
            val direction: EuclideanPosition
            val lengths: List<Double>
            val steps: List<String>
            if(mode=="chain") {
                require(sources.isNotEmpty()&&!target.has("end")&&!raw.has("partitionCount")) { "A chain requires sources and a directed ray, without a fixed final endpoint" }
                val vector=point(target.getJSONObject("direction"))
                val magnitude=hypot(vector.x,vector.y)
                require(magnitude>=MIN_LENGTH&&magnitude.isFinite()) { "The target ray needs a nonzero finite direction" }
                direction=vector*(1/magnitude);end=null
                val list=raw.getJSONArray("steps")
                require(list.length() in 1..32) { "Declare one to 32 source references in chain order" }
                steps=List(list.length()) { identifier(list.get(it)) }
                val byId=sources.associateBy { it.id }
                lengths=steps.map { id -> byId[id]?.length ?: error("A chain step refers to an unknown source segment") }
            } else {
                require(!target.has("direction")&&!raw.has("steps")) { "A partition requires its fixed total segment, without chain steps" }
                end=namedPoint(target.getJSONObject("end"))
                val vector=end.position-origin.position
                val magnitude=hypot(vector.x,vector.y)
                require(magnitude>=MIN_LENGTH&&magnitude<=LIMIT&&magnitude.isFinite()) { "A partition needs two distinct finite endpoints" }
                direction=vector*(1/magnitude)
                val count=integer(raw.get("partitionCount"),2,32)
                lengths=List(count) { magnitude/count };steps=emptyList()
            }
            require(lengths.all { it.isFinite()&&it>=MIN_LENGTH }) { "Every required construction part must be at least 1e-9 internal units" }
            val total=lengths.sum()
            require(total.isFinite()&&total<=LIMIT) { "The constructed total exceeds the supported geometry range" }
            // Reject unattainable configurations, but never publish or frame a solved endpoint.
            require(supported(origin.position+direction*total)) { "The required construction leaves the supported geometry range" }
            val count=if(mode=="chain") lengths.size else lengths.size-1
            val labels=raw.getJSONArray("pointLabels")
            require(labels.length()==count) { "Declare exactly one label per freely constructed point" }
            val pointLabels=List(count) { label(labels.get(it)) }
            require(pointLabels.distinct().size==pointLabels.size) { "Constructed point labels must be unique" }
            val givens=sources.flatMap { listOf(it.from,it.to) }+listOfNotNull(origin,end)
            require(pointLabels.none { name -> givens.any { it.label==name } }) { "A constructed point must not reuse a fixed point name" }
            givens.groupBy { it.label }.values.forEach { points ->
                require(points.map { it.position }.distinct().size==1) { "The same given name cannot identify distinct positions" }
            }
            val position=numeric(raw,"positionTolerance")
            val length=numeric(raw,"lengthTolerance")
            val quarter=lengths.minOrNull()!!/4
            require(position>0&&length>0&&position<quarter&&length<quarter) { "Declare positive operational tolerances below one quarter of every required part" }
            val initial=if(raw.has("initial")) points(raw.getJSONObject("initial").getJSONArray("points"),count) else emptyList()
            val fixed=givens.map { it.position }+initial
            val span=max(fixed.maxOf { it.x }-fixed.minOf { it.x },fixed.maxOf { it.y }-fixed.minOf { it.y })
            val smallest=min(lengths.minOrNull()!!,sources.minOfOrNull { it.length }?:Double.POSITIVE_INFINITY)
            require(span/smallest<=MAX_RELATIVE_SPAN) { "Given segments are too small relative to their separation for the supported construction camera" }
            val goalPoints=buildList {
                var accumulated=0.0
                for(part in lengths) { accumulated+=part;add(origin.position+direction*accumulated) }
            }
            val resolution=(fixed+goalPoints).maxOf { max(Math.ulp(it.x),Math.ulp(it.y)) }
            require(min(position,length)>=resolution*8) { "Operational tolerances are below the supported precision of the given positions" }
            return SegmentTransportSpec(mode,sources,origin,direction,end,lengths,steps,pointLabels,initial,position,length)
        }

        internal const val LIMIT=1e9
        internal const val MIN_LENGTH=1e-9
        internal const val MAX_RELATIVE_SPAN=1e10
        internal fun supported(value: EuclideanPosition)=value.x.isFinite()&&value.y.isFinite()&&abs(value.x)<=LIMIT&&abs(value.y)<=LIMIT
        internal fun numeric(value: JSONObject,key: String): Double {
            val raw=value.get(key)
            require(raw is Number) { "$key must be a number" }
            return raw.toDouble().also { require(it.isFinite()) { "$key must be finite" } }
        }
        internal fun integer(value: Any,minimum: Int,maximum: Int): Int {
            require(value is Number)
            val number=value.toDouble()
            require(number.isFinite()&&number==floor(number)&&number>=minimum&&number<=maximum) { "Unsupported integral construction index or count" }
            return number.toInt()
        }
        internal fun point(value: JSONObject)=EuclideanPosition(numeric(value,"x"),numeric(value,"y")).also {
            require(supported(it)) { "A construction position is outside the finite supported range" }
        }
        internal fun points(values: JSONArray,maximum: Int): List<EuclideanPosition> {
            require(values.length()<=maximum) { "Construction points must be an ordered prefix without extra endpoints" }
            return List(values.length()) { point(values.getJSONObject(it)) }
        }
        private fun namedPoint(value: JSONObject)=SegmentTransportPoint(point(value),label(value.get("label")))
        private fun identifier(value: Any): String {
            require(value is String&&value.length in 1..96&&value.all { it.isLetterOrDigit()||it in "_-" }) { "Invalid source segment ID" }
            return value
        }
        private fun label(value: Any): String {
            require(value is String&&value.isNotBlank()&&value.length<=96&&value.none(Char::isISOControl)) { "Point labels must be nonblank text" }
            return value
        }
    }
}

internal object SegmentTransport {
    private fun result(code: String,correct: Boolean=false,evaluable: Boolean=true): MathValidationResult {
        val message=MathUiMessage(when(code) {
            "math_segment_invalid_answer" -> R.string.math_segment_invalid_answer
            "math_segment_complete" -> R.string.math_segment_complete
            "math_segment_review_line" -> R.string.math_segment_review_line
            "math_segment_review_direction" -> R.string.math_segment_review_direction
            "math_segment_review_order" -> R.string.math_segment_review_order
            "math_segment_review_coverage" -> R.string.math_segment_review_coverage
            "math_segment_review_length" -> R.string.math_segment_review_length
            "math_segment_review_total" -> R.string.math_segment_review_total
            "math_segment_satisfied" -> R.string.math_segment_satisfied
            else -> R.string.math_ui_validation_error
        })
        return MathValidationResult(correct,message.diagnostic,evaluable,uiMessage=message)
    }
    fun validate(raw: JSONObject,answer: MathAnswer): MathValidationResult {
        val spec=SegmentTransportSpec.parse(raw)
        val points=(answer as? MathAnswer.Graph)?.points?.map { EuclideanPosition(it.x,it.y) }
            ?: return result("math_segment_invalid_answer",evaluable=false)
        if(points.size<spec.pointCount)return result("math_segment_complete",evaluable=false)
        if(points.size!=spec.pointCount||points.any { !SegmentTransportSpec.supported(it) })return result("math_segment_invalid_answer",evaluable=false)
        fun along(point: EuclideanPosition)=dot(point-spec.origin.position,spec.direction)
        for(point in points) {
            val displacement=point-spec.origin.position
            if(abs(displacement.x*spec.direction.y-displacement.y*spec.direction.x)>spec.positionTolerance)
                return result("math_segment_review_line")
            val position=along(point)
            if(position<=0)return result("math_segment_review_direction")
            if(spec.mode=="partition"&&position>=spec.total)return result("math_segment_review_coverage")
        }
        val complete=listOf(spec.origin.position)+points+listOfNotNull(spec.end?.position)
        for(index in spec.lengths.indices) {
            if(along(complete[index+1])<=along(complete[index]))return result("math_segment_review_order")
            val delta=complete[index+1]-complete[index]
            if(abs(hypot(delta.x,delta.y)-spec.lengths[index])>spec.lengthTolerance)return result("math_segment_review_length")
        }
        val last=complete.last()-spec.origin.position
        val travelled=complete.zipWithNext().sumOf { (a,b) -> hypot(b.x-a.x,b.y-a.y) }
        if(abs(hypot(last.x,last.y)-spec.total)>spec.lengthTolerance||abs(travelled-spec.total)>spec.lengthTolerance)
            return result("math_segment_review_total")
        return result("math_segment_satisfied",correct=true)
    }

    private data class Measurement(val id: String,val label: String,val from: EuclideanPosition,val to: EuclideanPosition) {
        val length get()=hypot(to.x-from.x,to.y-from.y)
    }
    private data class Camera(val x: Double,val y: Double,val zoom: Double)
    private data class Tool(val x: Double,val y: Double,val rotation: Double,val length: Double,val measurementId: String?) {
        val end get()=EuclideanPosition(x+cos(rotation)*length,y+sin(rotation)*length)
    }
    private data class Frame(val center: EuclideanPosition,val width: Double,val height: Double)
    private const val MAX_ZOOM=1e12
    // Learner points may occupy opposite corners of the supported square. A
    // captured opening must retain that real distance even when it exceeds the
    // authored construction's total. Its pivot remains an ordinary supported
    // position; rigid rotation may put the derived end beyond that square.
    private val MAX_TOOL_LENGTH=hypot(2*SegmentTransportSpec.LIMIT,2*SegmentTransportSpec.LIMIT)
    private val MAX_TOOL_ENDPOINT=SegmentTransportSpec.LIMIT+MAX_TOOL_LENGTH
    private fun pointJson(value: EuclideanPosition)=JSONObject().put("x",value.x).put("y",value.y)
    private fun distance(a: EuclideanPosition,b: EuclideanPosition)=hypot(a.x-b.x,a.y-b.y)
    private fun measurements(spec: SegmentTransportSpec,points: List<EuclideanPosition>): List<Measurement> = buildList {
        spec.sources.forEach { add(Measurement("source:${it.id}","${it.from.label}–${it.to.label}",it.from.position,it.to.position)) }
        val current=listOf(spec.origin.position)+points
        current.zipWithNext().forEachIndexed { index,(from,to) ->
            if(distance(from,to)>=SegmentTransportSpec.MIN_LENGTH)add(Measurement("part:$index","${if(index==0)spec.origin.label else spec.pointLabels[index-1]}–${spec.pointLabels[index]}",from,to))
        }
        if(spec.end!=null) {
            if(points.size==spec.pointCount&&distance(current.last(),spec.end.position)>=SegmentTransportSpec.MIN_LENGTH)
                add(Measurement("part:${spec.pointCount}","${spec.pointLabels.last()}–${spec.end.label}",current.last(),spec.end.position))
            add(Measurement("target","${spec.origin.label}–${spec.end.label}",spec.origin.position,spec.end.position))
        }
    }
    private fun capturedTool(measurement: Measurement)=Tool(measurement.from.x,measurement.from.y,
        atan2(measurement.to.y-measurement.from.y,measurement.to.x-measurement.from.x),measurement.length,measurement.id)
    private fun initialFrame(spec: SegmentTransportSpec): Frame {
        val points=spec.sources.flatMap { listOf(it.from.position,it.to.position) }+listOfNotNull(spec.origin.position,spec.end?.position)+spec.initial
        val left=points.minOf { it.x };val right=points.maxOf { it.x };val low=points.minOf { it.y };val high=points.maxOf { it.y }
        val givenLength=spec.sources.maxOfOrNull { it.length }?:distance(spec.origin.position,spec.end!!.position)
        val extent=max(givenLength,max(right-left,high-low))
        return Frame(EuclideanPosition((left+right)/2,(low+high)/2),right-left+extent*.3,high-low+extent*.3)
    }

    /** Stateless request/response boundary. Invalid requests leave the caller's previous draft intact. */
    fun layout(request: JSONObject,drawingJson: (EuclideanDrawingSnapshot)->JSONObject,
               labelMeasure: ((String, Double) -> EuclideanLabelMetrics)? = null): JSONObject {
        val spec=SegmentTransportSpec.parse(request.getJSONObject("config"))
        val width=SegmentTransportSpec.numeric(request,"width");val height=SegmentTransportSpec.numeric(request,"height")
        val unit=if(request.has("unit"))SegmentTransportSpec.numeric(request,"unit")else 1.0
        val size=if(request.has("textSize"))SegmentTransportSpec.numeric(request,"textSize")else 15.0
        require(width>0&&height>0&&width<=32768&&height<=32768&&unit>0&&unit<=16&&size>0&&size<=2048) { "Invalid construction viewport" }
        val frame=initialFrame(spec)
        val original=request.optJSONObject("state")
        require(!request.has("state")||original!=null)
        var points=if(original==null)spec.initial else {
            require(original.getString("type")=="graph") { "A segment construction requires its graph answer envelope" }
            SegmentTransportSpec.points(original.getJSONArray("points"),spec.pointCount)
        }
        val draft=original?.optJSONObject("interactionDraft")?.let { JSONObject(it.toString()) }?:JSONObject()
        require(original==null||!original.has("interactionDraft")||original.optJSONObject("interactionDraft")!=null)
        val initialMeasurements=measurements(spec,spec.initial)
        val defaultTool=capturedTool(if(spec.sources.isNotEmpty())initialMeasurements.first { it.id=="source:${spec.sources.first().id}" }
            else initialMeasurements.first { it.id=="target" })
        var camera=draft.optJSONObject("camera")?.let {
            Camera(SegmentTransportSpec.numeric(it,"x"),SegmentTransportSpec.numeric(it,"y"),SegmentTransportSpec.numeric(it,"zoom"))
        }?:Camera(frame.center.x,frame.center.y,1.0)
        require(!draft.has("camera")||draft.optJSONObject("camera")!=null)
        fun validCamera(value: Camera)=SegmentTransportSpec.supported(EuclideanPosition(value.x,value.y))&&value.zoom in (1.0/64)..MAX_ZOOM
        require(validCamera(camera)) { "Invalid saved construction camera" }
        var tool=draft.optJSONObject("tool")?.let {
            Tool(SegmentTransportSpec.numeric(it,"x"),SegmentTransportSpec.numeric(it,"y"),SegmentTransportSpec.numeric(it,"rotation"),SegmentTransportSpec.numeric(it,"length"),
                if(it.has("measurementId"))it.getString("measurementId")else null)
        }?:defaultTool
        require(!draft.has("tool")||draft.optJSONObject("tool")!=null)
        fun validTool(value: Tool): Boolean {
            if(!SegmentTransportSpec.supported(EuclideanPosition(value.x,value.y))||!value.rotation.isFinite()||
                !value.length.isFinite()||value.length<SegmentTransportSpec.MIN_LENGTH||value.length>MAX_TOOL_LENGTH)return false
            val end=value.end
            return end.x.isFinite()&&end.y.isFinite()&&abs(end.x)<=MAX_TOOL_ENDPOINT&&abs(end.y)<=MAX_TOOL_ENDPOINT
        }
        require(validTool(tool)) { "Invalid saved rigid measurement tool" }
        tool.measurementId?.let { id ->
            require(spec.sources.any { id=="source:${it.id}" }||(id=="target"&&spec.end!=null)||
                (id.startsWith("part:")&&id.removePrefix("part:").toIntOrNull()?.let { it in spec.lengths.indices }==true)) {
                "A captured opening refers to an unknown geometric span"
            }
        }
        val mode=if(draft.has("mode"))draft.getString("mode")else "place"
        require(mode in setOf("place","move_tool","rotate_tool","view")) { "Unknown construction interaction mode" }
        var active=if(draft.has("activePoint"))SegmentTransportSpec.integer(draft.get("activePoint"),0,min(points.size,spec.pointCount-1))else min(points.size,spec.pointCount-1)
        val padding=min(max(30*unit,size*1.8),min(width,height)*.24)
        val baseFactor=min((width-2*padding)/(frame.width*1.8),(height-2*padding)/(frame.height*1.8))
        var gestureLimited=false
        fun boundedCamera(candidate: Camera): Camera {
            if(!candidate.x.isFinite()||!candidate.y.isFinite()) { gestureLimited=true;return camera }
            val bounded=candidate.copy(x=candidate.x.coerceIn(-SegmentTransportSpec.LIMIT,SegmentTransportSpec.LIMIT),
                y=candidate.y.coerceIn(-SegmentTransportSpec.LIMIT,SegmentTransportSpec.LIMIT))
            if(bounded!=candidate)gestureLimited=true
            return bounded
        }
        fun factor()=baseFactor*camera.zoom
        fun project(point: EuclideanPosition)=EuclideanPosition(width/2+(point.x-camera.x)*factor(),height/2-(point.y-camera.y)*factor())
        fun unproject(point: EuclideanPosition)=EuclideanPosition(camera.x+(point.x-width/2)/factor(),camera.y-(point.y-height/2)/factor())
        fun pixel(value: JSONObject)=EuclideanPosition(SegmentTransportSpec.numeric(value,"x"),SegmentTransportSpec.numeric(value,"y")).also {
            require(abs(it.x)<=1e7&&abs(it.y)<=1e7) { "Invalid pointer coordinate" }
        }
        val gesture=request.optJSONObject("gesture")
        require(!request.has("gesture")||gesture!=null)
        if(gesture!=null) {
            fun pointer(key: String)=pixel(gesture.getJSONObject(key))
            when(gesture.getString("type")) {
                "place","move_point"->{
                    val editing=gesture.getString("type")=="move_point"
                    val index=SegmentTransportSpec.integer(gesture.get("index"),0,if(editing)points.size-1 else min(points.size,spec.pointCount-1))
                    val next=unproject(pointer("current"))
                    if(SegmentTransportSpec.supported(next)) {
                        points=points.toMutableList().also { if(index==it.size)it.add(next)else it[index]=next }
                        active=index
                    }else gestureLimited=true
                }
                "move_tool"->{
                    val before=unproject(pointer("previous"));val after=unproject(pointer("current"))
                    val candidate=tool.copy(x=tool.x+after.x-before.x,y=tool.y+after.y-before.y)
                    if(validTool(candidate))tool=candidate else gestureLimited=true
                }
                "rotate_tool"->{
                    val center=EuclideanPosition(tool.x,tool.y);val before=unproject(pointer("previous"))-center;val after=unproject(pointer("current"))-center
                    if(hypot(before.x,before.y)*factor()>4*unit&&hypot(after.x,after.y)*factor()>4*unit) {
                        val candidate=tool.copy(rotation=tool.rotation+atan2(before.x*after.y-before.y*after.x,before.x*after.x+before.y*after.y))
                        if(validTool(candidate))tool=candidate else gestureLimited=true
                    }
                }
                "view"->{val before=pointer("previous");val after=pointer("current");camera=boundedCamera(camera.copy(x=camera.x-(after.x-before.x)/factor(),y=camera.y+(after.y-before.y)/factor()))}
                "zoom"->{
                    val at=pointer("current");val anchor=unproject(at);val delta=SegmentTransportSpec.numeric(gesture,"delta")
                    val requested=camera.zoom*1.2.pow(delta.coerceIn(-1024.0,1024.0))
                    val zoom=requested.coerceIn(1.0/64,MAX_ZOOM)
                    if(zoom!=requested)gestureLimited=true
                    camera=boundedCamera(Camera(anchor.x-(at.x-width/2)/(baseFactor*zoom),anchor.y+(at.y-height/2)/(baseFactor*zoom),zoom))
                }
                "measure"->{
                    val id=gesture.getString("id")
                    val captured=capturedTool(measurements(spec,points).singleOrNull { it.id==id }?:error("That geometric span is not currently constructed"))
                    check(validTool(captured)) { "A measured geometric span produced an unsupported rigid tool" }
                    tool=captured
                }
                "reset_view"->camera=Camera(frame.center.x,frame.center.y,1.0)
                "reset_tool"->tool=defaultTool
                else->error("Unknown construction gesture")
            }
        }
        draft.put("mode",mode).put("activePoint",active)
            .put("camera",JSONObject().put("x",camera.x).put("y",camera.y).put("zoom",camera.zoom))
            .put("tool",JSONObject().put("x",tool.x).put("y",tool.y).put("rotation",tool.rotation).put("length",tool.length).apply { tool.measurementId?.let { put("measurementId",it) } })
        val state=JSONObject().put("type","graph").put("points",JSONArray(points.map(::pointJson))).put("interactionDraft",draft)
        val diagramPoints=mutableListOf<EuclideanPoint>();val objects=mutableListOf<EuclideanObject>()
        fun point(id: String,position: EuclideanPosition,label: String?=null) { diagramPoints+=EuclideanPoint(id,position,if(label==null)emptyList()else listOf(label)) }
        fun segment(id: String,from: EuclideanPosition,to: EuclideanPosition) {
            if(distance(from,to)>=SegmentTransportSpec.MIN_LENGTH)objects+=EuclideanObject(id,EuclideanObjectKind.SEGMENT,from,to-from,emptyList(),emptySet(),null)
        }
        spec.sources.forEach { source -> point("source:${source.id}:from",source.from.position,source.from.label);point("source:${source.id}:to",source.to.position,source.to.label);segment("source:${source.id}",source.from.position,source.to.position) }
        point("target:origin",spec.origin.position,spec.origin.label)
        if(spec.end!=null) {point("target:end",spec.end.position,spec.end.label);segment("target:segment",spec.origin.position,spec.end.position)}
        else objects+=EuclideanObject("target:ray",EuclideanObjectKind.RAY,spec.origin.position,spec.direction,listOf("target:origin"),setOf("target:origin"),null)
        points.forEachIndexed { index,position -> point("point:$index",position,spec.pointLabels[index]);segment("part:$index",if(index==0)spec.origin.position else points[index-1],position) }
        if(spec.end!=null&&points.size==spec.pointCount)segment("part:${spec.pointCount}",points.last(),spec.end.position)
        val toolStart=EuclideanPosition(tool.x,tool.y);val toolEnd=tool.end
        point("tool:start",toolStart);point("tool:end",toolEnd);segment("tool:segment",toolStart,toolEnd)
        // Uniform camera transform changes the view only. Fixed bounds never depend on answers or a calculated solution.
        // The original diagram layout has a one-unit minimum span. Normalize
        // only its presentation coordinates so uniformly scaled tiny figures
        // retain exactly the same framing and operational geometry.
        val normalizer=min(frame.width,frame.height)
        fun transformed(point: EuclideanPosition)=EuclideanPosition((point.x-camera.x)*camera.zoom/normalizer,(point.y-camera.y)*camera.zoom/normalizer)
        val named=diagramPoints.groupBy { it.position }.values.map { group ->
            val first=group.first();val pixel=project(first.position)
            first.copy(position=transformed(first.position),labels=if(pixel.x in 0.0..width&&pixel.y in 0.0..height)group.flatMap { it.labels }.distinct()else emptyList())
        }
        val transformedObjects=objects.map { it.copy(origin=transformed(it.origin),direction=it.direction*(camera.zoom/normalizer)) }
        val diagram=EuclideanDiagram(named,transformedObjects)
        val bounds=EuclideanRect(-frame.width/(2*normalizer),-frame.height/(2*normalizer),frame.width/(2*normalizer),frame.height/(2*normalizer))
        val supplied=request.optJSONObject("labelMetrics")
        require(!request.has("labelMetrics")||supplied!=null)
        val snapshot=EuclideanDiagramLayout.layoutInBounds(diagram,width,height,unit,size,bounds) { text,maximum ->
            val metric=supplied?.optJSONObject(text)
            if(labelMeasure != null) labelMeasure(text,maximum) else if(metric!=null) {
                val lines=metric.getJSONArray("lines")
                EuclideanLabelMetrics(List(lines.length()) { lines.getString(it) },metric.getDouble("width"),metric.getDouble("lineHeight"),metric.getDouble("ascent"))
            } else {
                val count=max(1,floor(maximum/(size*.6)).toInt());val lines=text.chunked(count)
                EuclideanLabelMetrics(lines,min(maximum,(lines.maxOfOrNull { it.length }?:0)*size*.6),size*1.25,size)
            }
        }
        // Never send offscreen world positions to Canvas pixel conversions. Semantic
        // points remain untouched and become visible again after pan/zoom.
        val margin=32*unit
        fun visiblePixel(point: EuclideanPosition)=point.x.isFinite()&&point.y.isFinite()&&point.x>=-margin&&point.x<=width+margin&&point.y>=-margin&&point.y<=height+margin
        val visiblePaths=snapshot.paths.filter { visiblePixel(it.start)&&visiblePixel(it.end) }
        val pathIds=visiblePaths.map { it.id }.toSet()
        val safe=snapshot.copy(points=snapshot.points.filter { visiblePixel(it.center) },paths=visiblePaths,
            arrows=snapshot.arrows.filter { it.objectId in pathIds&&visiblePixel(it.tip)&&visiblePixel(it.wingA)&&visiblePixel(it.wingB) })
        // Coincident names share a layout obstacle; expose all visible aliases afterwards.
        val cleared=RigidAngleLabelLayout.clear(safe,unit).copy(points=diagramPoints.map { EuclideanDrawnPoint(it.id,project(it.position)) }.filter { visiblePixel(it.center) })
        val result=drawingJson(cleared).put("state",state).put("pointLabels",JSONArray(spec.pointLabels))
            .put("gestureLimited",gestureLimited)
            .put("measurements",JSONArray(measurements(spec,points).map { JSONObject().put("id",it.id).put("label",it.label) }))
            .put("constructedPoints",JSONArray(points.mapIndexedNotNull { index,position -> project(position).takeIf(::visiblePixel)?.let { JSONObject().put("index",index).put("center",pointJson(it)) } }))
        if(visiblePixel(project(toolStart))&&visiblePixel(project(toolEnd)))result.put("tool",JSONObject().put("start",pointJson(project(toolStart))).put("end",pointJson(project(toolEnd))).put("center",pointJson(project(toolStart))))
        return result
    }
}
