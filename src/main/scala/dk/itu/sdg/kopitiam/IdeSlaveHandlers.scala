/* IdeSlaveActions.scala
 * Eclipse Action wrappers for coqtop functionality
 * Copyright © 2013 Alexander Faithfull
 * 
 * You may use, copy, modify and/or redistribute this code subject to the terms
 * of either the license of Kopitiam or the Apache License, version 2.0 */

package dk.itu.sdg.kopitiam

case class CoqStep(
    offset : Int,
    text : String,
    undo : Pair[Int, Option[String]]) // (rewind-steps, undo-command)

import scala.collection.mutable.Stack
trait Editor extends org.eclipse.ui.IEditorPart {
  def steps : Stack[CoqStep]
  def coqTop : CoqTopIdeSlave_v20120710
  
  def document : String
  def cursorPosition : Int
  
  def underway : Int
  def setUnderway(offset : Int)
  
  def completed : Int
  def setCompleted(offset : Int)
  
  def goals : CoqTypes.goals
  def setGoals(goals : CoqTypes.goals)
  
  var enabled_ : Boolean = true
  
  def enabled = enabled_
  def preExecuteJob = (enabled_ = false)
  def postExecuteJob = (enabled_ = true)
}

import org.eclipse.ui.ISources
import org.eclipse.core.commands.{AbstractHandler,ExecutionEvent}
import org.eclipse.core.expressions.IEvaluationContext

abstract class EditorHandler extends AbstractHandler {
  protected var editor : Editor = null
  
  override def setEnabled(evaluationContext : Object) = {
    val activeEditor = if (evaluationContext != null) {
      evaluationContext.asInstanceOf[IEvaluationContext].getVariable(
          ISources.ACTIVE_EDITOR_NAME)
    } else org.eclipse.ui.PlatformUI.getWorkbench().
        getActiveWorkbenchWindow().getActivePage().getActiveEditor()
    if (activeEditor != null && activeEditor.isInstanceOf[CoqEditor]) {
      editor = activeEditor.asInstanceOf[CoqEditor]
      setBaseEnabled(editor.enabled)
    } else setBaseEnabled(false)
  }
}

object EditorHandler {
  def makeStep(doc : String, offset : Int) : Option[CoqStep] = {
    val length = CoqTop.findNextCommand(doc.substring(offset))
    if (length != -1) {
      Some(CoqStep(offset, doc.substring(offset, offset + length), (1, None)))
    } else None
  }
  
  def makeSteps(
      doc : String, from : Int, to : Int) : List[CoqStep] = {
    val steps = List.newBuilder[CoqStep]
    var offset = from
    while (offset <= to) {
      makeStep(doc, offset) match {
        case Some(step) =>
          offset = step.offset + step.text.length()
          if (offset <= to)
            steps += step
        case _ => offset = Int.MaxValue
      }
    }
    steps.result
  }
}
    
class StepForwardHandler extends EditorHandler {
  override def execute(ev : ExecutionEvent) = {
    if (isEnabled()) {
      EditorHandler.makeStep(editor.document, editor.underway) match {
        case Some(step) =>
          // We're running in the UI thread, so always move the underway marker
          editor.setUnderway(step.offset + step.text.length())
          new StepForwardJob(editor, List(step)).schedule()
        case _ =>
      }
    }
    null
  }
}

class StepAllHandler extends EditorHandler {
  override def execute(ev : ExecutionEvent) = {
    if (isEnabled()) {
      val doc = editor.document
      val steps = EditorHandler.makeSteps(doc, editor.underway, doc.length)
      if (steps.length > 0) {
        editor.setUnderway(steps.last.offset + steps.last.text.length)
        new StepForwardJob(editor, steps).schedule()
      }
    }
    null
  }
}

class StepToCursorHandler extends EditorHandler {
  override def execute(ev : ExecutionEvent) = {
    if (isEnabled()) {
      val underwayPos = editor.underway
      val cursorPos = editor.cursorPosition
      if (cursorPos > underwayPos) { // Forwards!
        val steps = EditorHandler.makeSteps(
          editor.document, editor.underway, editor.cursorPosition)
        if (steps.length > 0) {
          editor.setUnderway(steps.last.offset + steps.last.text.length)
          new StepForwardJob(editor, steps).schedule()
        }
      } else if (cursorPos < underwayPos) { // Backwards!
        
      }
    }
    null
  }
}

class RestartCoqHandler extends EditorHandler {
  override def execute(ev : ExecutionEvent) = {
    if (isEnabled())
      new RestartCoqJob(editor).schedule()
    null
  }
}