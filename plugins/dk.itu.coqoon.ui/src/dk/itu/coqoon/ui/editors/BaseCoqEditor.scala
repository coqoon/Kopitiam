/* (c) 2010-2011 Hannes Mehnert and David Christiansen
 * Copyright © 2014 Alexander Faithfull */

package dk.itu.coqoon.ui.editors

import dk.itu.coqoon.ui.{Activator, CoqPartitions, EventReconciler,
  MultiReconciler, CoqoonUIPreferences, ModelLabelProvider,
  ModelContentProvider, OpenDeclarationHandler}
import dk.itu.coqoon.core.utilities.TryCast
import org.eclipse.ui.editors.text.TextEditor

abstract class BaseCoqEditor extends ScalaTextEditor {
  import org.eclipse.ui.editors.text.EditorsUI
  import org.eclipse.ui.texteditor.ChainedPreferenceStore
  setPreferenceStore(new ChainedPreferenceStore(Array(
      Activator.getDefault.getPreferenceStore,
      EditorsUI.getPreferenceStore)))

  import org.eclipse.jface.text.source.{
    Annotation, ISourceViewer, IVerticalRuler}
  import org.eclipse.jface.text.Position
  import org.eclipse.jface.text.source.projection.{
    ProjectionAnnotation, ProjectionAnnotationModel}

  import dk.itu.coqoon.core.model._
  import dk.itu.coqoon.core.utilities.CacheSlot
  import org.eclipse.ui.IFileEditorInput
  private lazy val workingCopy = CacheSlot[Option[IDetachedCoqVernacFile]] {
    val file = TryCast[IFileEditorInput](getEditorInput).map(_.getFile)
    val element = file.flatMap(ICoqModel.getInstance.toCoqElement)
    element.flatMap(TryCast[ICoqVernacFile]).map(_.detach).orElse {
      Console.err.println(
          s"$this: dummy Coq vernac file created " +
          s"for $getEditorInput (file = $file, element = $element)")
      Some(IDetachedCoqVernacFile.createDummy)
    }
  }

  def getWorkingCopy() = workingCopy

  object WorkingCopyListener extends CoqElementChangeListener {
    override def coqElementChanged(ev : CoqElementEvent) =
      ev match {
        case CoqFileContentChangedEvent(f : ICoqVernacFile)
            if getWorkingCopy.get.contains(f) =>
          updateFolding
        case _ =>
      }
  }

  import org.eclipse.jface.text.source.SourceViewerConfiguration
  protected def createSourceViewerConfiguration() : SourceViewerConfiguration =
    new BaseCoqSourceViewerConfiguration(this)

  import org.eclipse.ui.editors.text.{
    TextFileDocumentProvider, ForwardingDocumentProvider}
  import org.eclipse.core.filebuffers.IDocumentSetupParticipant

  private object CoqDocumentSetupParticipant
      extends IDocumentSetupParticipant {
    import org.eclipse.jface.text.IDocument
    override def setup(doc : IDocument) =
      CoqPartitions.installPartitioner(doc, CoqPartitions.ID)
  }

  override protected def initializeEditor() = {
    setDocumentProvider(new ForwardingDocumentProvider(
      CoqPartitions.ID, CoqDocumentSetupParticipant,
      new TextFileDocumentProvider {
        override def getDefaultEncoding() = "UTF-8"
      }))
    ICoqModel.getInstance.addListener(WorkingCopyListener)
    setSourceViewerConfiguration(createSourceViewerConfiguration)
    super.initializeEditor
  }

  override protected def dispose() = {
    ICoqModel.getInstance.removeListener(WorkingCopyListener)
    super.dispose
  }

  def getViewer() = super.getSourceViewer

  protected var annotationModel : Option[ProjectionAnnotationModel] = None
  protected var oldAnnotations : Array[Annotation] = Array()
  private def updateFolding() : Unit = {
    import dk.itu.coqoon.core.utilities.Substring

    import scala.collection.JavaConversions._
    var positions : Seq[Position] = Seq()
    getWorkingCopy.get.foreach(_.accept(_ match {
      case f : ICoqScriptGroup
          if f.getChildren.size > 1 =>
        val text = f.getText
        val padding = text.takeWhile(_.isWhitespace).length
        if (Substring(text, padding).iterator.count(_ == '\n') < 3) {
          false
        } else {
          positions +:=
            new Position(f.getOffset + padding, f.getLength - padding)
          true
        }
      case f : IParent => true
      case _ => false
    }))

    val newAnnotations = Map(positions.map(
        p => (new ProjectionAnnotation -> p)) : _*)

    annotationModel.foreach(
        _.modifyAnnotations(oldAnnotations, newAnnotations, null))
    oldAnnotations = newAnnotations.map(_._1).toArray
  }

  override def isEditable =
    getWorkingCopy.get match {
      case Some(_) =>
        super.isEditable
      case None =>
        /* Anything without a backing ICoqElement isn't really part of Coqoon's
         * world and should be read-only */
        false
    }

  import org.eclipse.ui.texteditor.SourceViewerDecorationSupport
  import org.eclipse.jface.text.source.DefaultCharacterPairMatcher
  override def configureSourceViewerDecorationSupport(
      support : SourceViewerDecorationSupport) = {
    import CoqoonUIPreferences._
    import org.eclipse.jface.text.IDocumentExtension3
    super.configureSourceViewerDecorationSupport(support)
    support.setCharacterPairMatcher(new DefaultCharacterPairMatcher(
        Array('(', ')', '{', '}', '<', '>', '[', ']'),
        CoqPartitions.ID, true))
    support.setMatchingCharacterPainterPreferenceKeys(
        MATCHING_BRACKETS, MATCHING_BRACKETS_COLOR)
  }

  import org.eclipse.swt.widgets.Composite
  import org.eclipse.jface.text.source.projection.ProjectionViewer

  override def createPartControl(parent : Composite) = {
    import org.eclipse.jface.text.source.projection.ProjectionSupport
    super.createPartControl(parent)

    TryCast[ProjectionViewer](getSourceViewer).foreach(viewer => {
      //Create the necessary infrastructure for code folding
      val projectionSupport = new ProjectionSupport(
          viewer, getAnnotationAccess(), getSharedColors())
      import org.eclipse.jface.text.source.AnnotationPainter.NullStrategy
      projectionSupport.setAnnotationPainterDrawingStrategy(new NullStrategy)
      projectionSupport.install()

      //turn projection mode on
      viewer.enableProjection

      annotationModel = Option(viewer.getProjectionAnnotationModel)
      updateFolding()
    })
  }

  private val reconciler = new MultiReconciler
  protected def getReconciler() = reconciler

  private def reconcileEvents(events : List[EventReconciler.DecoratedEvent]) =
    getWorkingCopy.get.foreach(
        _.setContents(getViewer.getDocument.get))
  getReconciler().addHandler(reconcileEvents)

  override protected def createSourceViewer(parent : Composite,
      ruler : IVerticalRuler, styles : Int) : ISourceViewer = {
    val viewer =
      if (CoqoonUIPreferences.Folding.get) {
        val viewer = new ProjectionViewer(
            parent, ruler, getOverviewRuler(), isOverviewRulerVisible(), styles)
        getSourceViewerDecorationSupport(viewer)
        viewer
      } else super.createSourceViewer(parent, ruler, styles)
    reconciler.install(viewer)
    viewer
  }

  import org.eclipse.ui.views.contentoutline.IContentOutlinePage
  /* Support getting outline pages
   * Note: 
   *   outlinePage : Option[CoqContentOutlinePage] = None
   * is declared in ScalaTextEditor to work around a type issue
   */
  protected def createOutlinePage() : CoqContentOutlinePage = {
    val page = new CoqContentOutlinePage
    getWorkingCopy.get.foreach(page.setInput)
    page
  }

  override def initializeKeyBindingScopes =
    setKeyBindingScopes(Array("dk.itu.coqoon.ui.contexts.coq"))
}

import org.eclipse.ui.views.contentoutline.ContentOutlinePage

import dk.itu.coqoon.core.model.{
  ICoqElement, ICoqScriptGroup, ICoqScriptSentence}

class CoqContentOutlinePage extends ContentOutlinePage {
  import dk.itu.coqoon.ui.utilities.jface.Selection
  import org.eclipse.jface.viewers.{TreeViewer, SelectionChangedEvent}
  import org.eclipse.swt.widgets.Composite

  override def selectionChanged(event : SelectionChangedEvent) : Unit = {
    super.selectionChanged(event)

    event.getSelection match {
      case Selection.Structured((g : ICoqScriptGroup) +: _) =>
        OpenDeclarationHandler.highlightElement(g.getDeterminingSentence)
      case Selection.Structured((s : ICoqScriptSentence) +: _) =>
        OpenDeclarationHandler.highlightElement(s)
      case _ =>
    }
  }

  override def createControl(parent : Composite) : Unit = {
    super.createControl(parent)

    val viewer = getTreeViewer()
    viewer.setContentProvider(new ModelContentProvider)
    viewer.setLabelProvider(new ModelLabelProvider)
    viewer.setInput(input.orNull)
  }

  private var input : Option[ICoqElement] = None
  def setInput(input : ICoqElement) = {
    this.input = Option(input)
    Option(getTreeViewer).foreach(_.setInput(input))
  }
}