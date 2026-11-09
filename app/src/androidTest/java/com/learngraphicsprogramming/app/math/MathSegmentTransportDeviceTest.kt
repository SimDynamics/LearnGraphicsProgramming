package com.learngraphicsprogramming.app.math

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MathSegmentTransportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun response(): MathResponseSpec {
        val block=JSONObject("""{"type":"math_segment_transport","prompt":"Transporta AB desde M.","validator":{"type":"segment_transport","mode":"chain","sources":[{"id":"ab","from":{"x":-3,"y":2,"label":"A"},"to":{"x":0,"y":2,"label":"B"}}],"target":{"origin":{"x":0,"y":0,"label":"M"},"direction":{"x":1,"y":0}},"steps":["ab"],"pointLabels":["N"],"positionTolerance":0.04,"lengthTolerance":0.04}}""")
        return MathPuzzleParser.parse(block).responses.single()
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)addAll(descendants(view.getChildAt(i)))
    }
    private fun layout(control: MathResponseControl, width: Int) {
        control.view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        control.view.layout(0,0,width,control.view.measuredHeight)
    }
    @Test fun cameraAndRigidToolDraftAreIndependentFromAnswerAndRemainAvailableAfterSuccess() {
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            var answers=0;var drafts=0
            val control=MathSegmentTransportControl(context,response(),0xff3f80cf.toInt(),{answers++},{drafts++})
            layout(control,480)
            val surface=descendants(control.view).filterIsInstance<MathSegmentTransportView>().single()
            assertFalse(control.hasDraftChanges())
            val empty=control.answer()
            surface.zoom(2.0)
            surface.gesture(JSONObject().put("type","move_tool").put("previous",JSONObject().put("x",80).put("y",80))
                .put("current",JSONObject().put("x",110).put("y",100)))
            assertEquals(empty,control.answer())
            assertEquals(0,answers);assertEquals(2,drafts)
            assertFalse("View-only navigation cannot enable answer checking",control.hasDraftChanges())
            val draft=control.saveDraft()
            val restored=MathSegmentTransportControl(context,response(),0xff3f80cf.toInt(),{},{})
            restored.restore(empty);restored.restoreDraft(draft);layout(restored,480)
            assertEquals(draft.toString(),restored.saveDraft().toString())
            val correct=MathAnswer.Graph(listOf(MathPoint(3.0,0.0)))
            control.restore(correct);control.setEditable(false)
            val before=control.answer()
            surface.gesture(JSONObject().put("type","place").put("index",0).put("current",JSONObject().put("x",40).put("y",40)))
            assertEquals("A completed answer stays frozen",before,control.answer())
            val priorDrafts=drafts;surface.zoom(1.0)
            assertEquals(before,control.answer());assertEquals(priorDrafts+1,drafts)
        }
    }
    @Test fun damagedDraftCannotReplacePointsAndSmallLayoutsKeepCompleteNamedGeometry() {
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            for(width in listOf(320,640)) {
                val control=MathSegmentTransportControl(context,response(),0xff3f80cf.toInt(),{},{})
                layout(control,width)
                val answer=MathAnswer.Graph(listOf(MathPoint(1.0,0.0)))
                control.restore(answer)
                val saved=control.saveDraft()
                val corrupt=JSONObject(saved.toString()).apply {
                    getJSONObject("interactionDraft").getJSONObject("camera").put("zoom",-1)
                }
                control.restoreDraft(corrupt)
                assertEquals(answer,control.answer())
                assertEquals(saved.toString(),control.saveDraft().toString())
                val surface=descendants(control.view).filterIsInstance<MathSegmentTransportView>().single()
                val drawing=requireNotNull(surface.drawingSnapshot())
                assertTrue(drawing.points.map { it.id }.containsAll(listOf("target:origin","point:0","source:ab:from","source:ab:to")))
                assertTrue(drawing.paths.any { it.id=="target:ray" })
                assertTrue(drawing.arrows.any { it.objectId=="target:ray" })
            }
        }
    }
}
