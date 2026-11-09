package com.learngraphicsprogramming.app.math

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SegmentTransportTest {
    private fun config() = JSONObject("""{"type":"segment_transport","mode":"chain","sources":[{"id":"ab","from":{"x":-4,"y":3,"label":"A"},"to":{"x":-1,"y":3,"label":"B"}},{"id":"cd","from":{"x":2,"y":3,"label":"C"},"to":{"x":4,"y":3,"label":"D"}}],"target":{"origin":{"x":0,"y":0,"label":"M"},"direction":{"x":1,"y":0}},"steps":["ab","cd"],"pointLabels":["N","P"],"positionTolerance":0.04,"lengthTolerance":0.04}""")
    private fun points(vararg values: Pair<Double, Double>) = MathAnswer.Graph(values.map { MathPoint(it.first, it.second) })
    private fun request(config: JSONObject, state: JSONObject? = null, gesture: JSONObject? = null): JSONObject =
        JSONObject().put("config",config).put("width",480).put("height",320).put("unit",1.0).put("textSize",15.0).apply {
            state?.let { put("state",it) }; gesture?.let { put("gesture",it) }
        }
    private fun layout(request: JSONObject): JSONObject = SegmentTransport.layout(request, { snapshot ->
        assertTrue(snapshot.points.all { it.center.x.isFinite() && it.center.y.isFinite() })
        assertTrue(snapshot.paths.all { it.start.x.isFinite() && it.end.y.isFinite() })
        JSONObject()
    })

    @Test fun chainChecksEachPartOrderAndDirectionWithoutChargingIncompleteStates() {
        val raw=config()
        assertTrue(MathValidators.validate(raw,points(3.0 to 0.0,5.0 to 0.0)).correct)
        assertFalse(MathValidators.validate(raw,points(2.5 to 0.0,5.0 to 0.0)).correct)
        assertFalse(MathValidators.validate(raw,points(3.0 to .2,5.0 to 0.0)).correct)
        assertFalse(MathValidators.validate(raw,points(-3.0 to 0.0,-5.0 to 0.0)).correct)
        assertFalse(MathValidators.validate(raw,points(5.0 to 0.0,3.0 to 0.0)).correct)
        assertFalse(MathValidators.validate(raw,points(3.0 to 0.0)).evaluable)
        assertFalse(MathValidators.validate(raw,MathAnswer.Text("3, 5")).evaluable)
        assertFalse(MathValidators.validate(raw,points(3.0 to 0.0,5.0 to 0.0,6.0 to 0.0)).evaluable)
    }

    @Test fun partitionRequiresCoverageAndEqualInteriorParts() {
        val raw=JSONObject("""{"type":"segment_transport","mode":"partition","target":{"origin":{"x":0,"y":0,"label":"A"},"end":{"x":6,"y":0,"label":"B"}},"partitionCount":3,"pointLabels":["N","P"],"positionTolerance":0.04,"lengthTolerance":0.04}""")
        assertTrue(MathValidators.validate(raw,points(2.0 to 0.0,4.0 to 0.0)).correct)
        for(answer in listOf(points(1.0 to 0.0,5.0 to 0.0),points(2.0 to 1.0,4.0 to 1.0),
            points(0.0 to 0.0,4.0 to 0.0),points(2.0 to 0.0,6.0 to 0.0),points(4.0 to 0.0,2.0 to 0.0))) {
            assertFalse(MathValidators.validate(raw,answer).correct)
        }
    }

    @Test fun cameraAndToolGesturesPreserveAnswerAndCapturedLengthAcrossReopening() {
        val raw=config();val answer=points(3.0 to 0.0,5.0 to 0.0)
        var state=layout(request(raw,MathAnswerJson.encode(answer))).getJSONObject("state")
        fun apply(gesture: JSONObject) { state=layout(request(raw,state,gesture)).getJSONObject("state") }
        apply(JSONObject().put("type","measure").put("id","part:1"))
        assertEquals(2.0,state.getJSONObject("interactionDraft").getJSONObject("tool").getDouble("length"),0.0)
        for(type in listOf("move_tool","rotate_tool","view")) apply(JSONObject().put("type",type)
            .put("previous",JSONObject().put("x",210).put("y",130)).put("current",JSONObject().put("x",250).put("y",190)))
        apply(JSONObject().put("type","zoom").put("delta",4).put("current",JSONObject().put("x",250).put("y",190)))
        assertEquals(answer,MathAnswerJson.decode(state))
        assertEquals(2.0,state.getJSONObject("interactionDraft").getJSONObject("tool").getDouble("length"),0.0)
        val reopened=layout(request(raw,JSONObject(state.toString()))).getJSONObject("state")
        assertEquals(state.toString(),reopened.toString())
        assertTrue(MathValidators.validate(raw,requireNotNull(MathAnswerJson.decode(reopened))).correct)
        // Editing a constructed span cannot stretch an opening captured before the edit.
        apply(JSONObject().put("type","move_point").put("index",0).put("current",JSONObject().put("x",100).put("y",100)))
        assertEquals(2.0,state.getJSONObject("interactionDraft").getJSONObject("tool").getDouble("length"),0.0)
    }

    @Test fun blankConstructionNeverOffersTheSolutionAndFreePlacementSurvivesItsCodec() {
        val raw=config();val blank=layout(request(raw))
        assertEquals(0,blank.getJSONObject("state").getJSONArray("points").length())
        assertEquals(0,blank.getJSONArray("constructedPoints").length())
        assertEquals(2,blank.getJSONArray("pointLabels").length())
        val placed=layout(request(raw,blank.getJSONObject("state"),JSONObject().put("type","place").put("index",0)
            .put("current",JSONObject().put("x",200).put("y",180))))
        val response=requireNotNull(MathAnswerJson.decode(placed.getJSONObject("state")))
        assertEquals(response,MathAnswerJson.decode(MathAnswerJson.encode(response)))
        assertFalse(MathValidators.validate(raw,response).evaluable)
    }

    @Test fun malformedAndIncompatibleContractsAreRejectedBeforeEvaluation() {
        val zero=config().apply { getJSONObject("target").getJSONObject("direction").put("x",0) }
        val duplicates=config().put("pointLabels",JSONArray(listOf("N","N")))
        val tolerance=config().put("lengthTolerance",1)
        for(raw in listOf(zero,duplicates,tolerance)) {
            assertThrows(IllegalArgumentException::class.java) { SegmentTransportSpec.parse(raw) }
            assertFalse(MathValidators.validate(raw,points(3.0 to 0.0,5.0 to 0.0)).evaluable)
        }
        val block=JSONObject().put("type","math_segment_transport").put("prompt","Construye.")
            .put("validator",JSONObject().put("type","coordinates").put("expected",JSONArray()))
        assertThrows(IllegalArgumentException::class.java) { MathPuzzleParser.parse(block) }
    }

    @Test fun allPublishedConstructionsParseWithAuthenticAndroidStateLayouts() {
        val assets=listOf(File("src/main/assets"),File("app/src/main/assets")).first { it.isDirectory }
        val base=File(assets,"content/es/math_physics/math_geometria_plana")
        val names=setOf("lesson_comparar_segmentos_y_elegir_una_unidad.json","lesson_componer_y_recuperar_longitudes.json",
            "lesson_repetir_longitudes_y_repartir_en_partes_iguales.json","lesson_partir_y_comparar_angulos.json")
        var segments=0;var angles=0
        for(file in base.walkTopDown().filter { it.name in names }) {
            val lesson=JSONObject(file.readText())
            assertNotNull(com.learngraphicsprogramming.app.LessonStateLayoutJson.read(lesson))
            val pages=lesson.getJSONArray("pages")
            for(p in 0 until pages.length()) {
                val blocks=pages.getJSONObject(p).getJSONArray("blocks")
                for(b in 0 until blocks.length()) {
                    val block=blocks.getJSONObject(b)
                    if(block.optString("type")!="math_puzzle")continue
                    val puzzle=MathPuzzleParser.parse(block)
                    for(response in puzzle.responses) when(response.type) {
                        "math_segment_transport" -> {
                            segments++;val raw=response.validator
                            val spec=SegmentTransportSpec.parse(raw)
                            var distance=0.0
                            val answer=MathAnswer.Graph(spec.lengths.take(spec.pointCount).map { length ->
                                distance+=length;MathPoint(spec.origin.position.x+spec.direction.x*distance,spec.origin.position.y+spec.direction.y*distance)
                            })
                            assertTrue("${file.name}/${block.optString("id")}",MathValidators.validate(raw,answer).correct)
                            for(width in listOf(320,640))for(size in listOf(15,24))layout(request(raw).put("width",width).put("textSize",size))
                        }
                        "math_rigid_angle_copy" -> { angles++;RigidAngleCopySpec.parse(response.validator) }
                    }
                }
            }
        }
        assertEquals(8,segments);assertEquals(4,angles)
    }
}
