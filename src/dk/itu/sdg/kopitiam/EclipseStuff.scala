/* (c) 2010-2011 Hannes Mehnert and David Christiansen */

package dk.itu.sdg.kopitiam

import org.eclipse.ui.editors.text.TextEditor
import org.eclipse.ui.editors.text.FileDocumentProvider

//this should go away soon (rather use JDT editor)
class SimpleJavaEditor extends TextEditor {
  override def initializeEditor () : Unit = {
    setDocumentProvider(SimpleJavaDocumentProvider)
    super.initializeEditor()
  }
}

object SimpleJavaDocumentProvider extends FileDocumentProvider {
  import dk.itu.sdg.javaparser.JavaOutput
  import org.eclipse.ui.part.FileEditorInput
  import org.eclipse.jface.text.IDocument
  import scala.collection.mutable.HashMap

  val docs : HashMap[String,IDocument] = new HashMap[String,IDocument]()

  override def getDefaultEncoding () : String = "UTF-8"

  override def getDocument (ele : Object) : IDocument = {
    assert(ele.isInstanceOf[FileEditorInput])
    val elem = ele.asInstanceOf[FileEditorInput]
    val nam = elem.getName
    if (docs.contains(nam))
      docs(nam)
    else {
      val document = super.getDocument(ele)
      //Console.println("getdocument received " + document.get)
      val newt = JavaOutput.parseandoutput(document.get)
      Console.println("SimpleJava getDocument called, translated")
      document.set(newt)
      docs += nam -> document
      document
    }
  }
}


trait EclipseUtils {
  //Handy implicits and functions that make dealing with Eclipse less verbose
  import org.eclipse.jface.text.Position
  import org.eclipse.swt.graphics.Color
  import org.eclipse.swt.widgets.Display
  import dk.itu.sdg.parsing._

  private def display = Display getCurrent
  def color (r : Int, g : Int, b : Int) = new Color(display, r, g, b)

  implicit def pos2eclipsePos (pos : LengthPosition) : Position =
    pos match {
      case NoLengthPosition => new Position(0)
      case RegionPosition(off, len) => new Position(off, len)
    }

  implicit def tuple2Color (vals : (Int, Int, Int)) : Color = color(vals._1, vals._2, vals._3)
}

class CoqEditor extends TextEditor with EclipseUtils {
  import dk.itu.sdg.coqparser.VernacularRegion
  import org.eclipse.jface.text.source.{Annotation, IAnnotationModel, ISourceViewer, IVerticalRuler}
  import org.eclipse.jface.text.Position
  import org.eclipse.swt.widgets.Composite
  import org.eclipse.ui.IEditorInput
  import org.eclipse.ui.views.contentoutline.{ContentOutlinePage, IContentOutlinePage}
  import org.eclipse.jface.text.source.projection.{ProjectionAnnotation, ProjectionAnnotationModel}

  var annotationModel : ProjectionAnnotationModel = null

  override protected def initializeEditor () : Unit = {
    Console.println("initializeEditor was called")
    setDocumentProvider(CoqJavaDocumentProvider)
    Console.println(" - document provider set")
    setSourceViewerConfiguration(new CoqSourceViewerConfiguration(this))
    Console.println(" - source viewer configuration set")
    super.initializeEditor()
    Console.println(" - initializeEditor called super")
  }

  override def createPartControl (parent : Composite) : Unit = {
    import org.eclipse.jface.text.source.projection.{ProjectionSupport, ProjectionViewer}
    super.createPartControl(parent)

    //Create the necessary infrastructure for code folding
    val projViewer : ProjectionViewer = getSourceViewer.asInstanceOf[ProjectionViewer]
    val projectionSupport = new ProjectionSupport(projViewer, getAnnotationAccess(), getSharedColors())
    projectionSupport.install()

    //turn projection mode on
    projViewer.doOperation(ProjectionViewer.TOGGLE)

    annotationModel = projViewer.getProjectionAnnotationModel()
  }

  //Create the source viewer as one that supports folding
  override def createSourceViewer (parent : Composite, ruler : IVerticalRuler, styles : Int) : ISourceViewer = {
    import org.eclipse.jface.text.source.projection.ProjectionViewer
    val viewer : ISourceViewer = new ProjectionViewer(parent, ruler, getOverviewRuler(), isOverviewRulerVisible(), styles)
    getSourceViewerDecorationSupport(viewer)
    viewer
  }

  def getSource () : ISourceViewer = {
    getSourceViewer()
  }

  override def initializeKeyBindingScopes () : Unit = {
    setKeyBindingScopes(List("Kopitiam.context").toArray)
  }

  // Support getting outline pages
  var outlinePage : Option[CoqContentOutlinePage] = None
  override def getAdapter (required : java.lang.Class[_]) : AnyRef = {
    //Console.println("Getting adapter for " + required + " on CoqEditor")
    if (required.isInterface && required.getName.endsWith("IContentOutlinePage")) {
      outlinePage = outlinePage match {
        case p@Some(page) => p
        case None => {
          val page = new CoqContentOutlinePage(getDocumentProvider(), this)
          if (getEditorInput() != null)
            page.setInput(getEditorInput())
          Some(page)
        }
      }
      outlinePage.get
    }
    else super.getAdapter(required)
  }

  override def doSetInput (input : IEditorInput) : Unit = {
    super.doSetInput(input)
    if (input != null && outlinePage != null) {
      outlinePage foreach { _.setInput(input) }
    }
  }

  override def editorSaved () : Unit = {
    if (outlinePage != null) outlinePage foreach { _.update() }
    super.editorSaved()
  }

  //Necessary for Eclipse API cruft
  var oldAnnotations : Array[Annotation] = Array.empty

  def updateFolding (root : VernacularRegion) : Unit = {
    val annotations = foldingAnnotations(root)
    var newAnnotations = new java.util.HashMap[Any, Any]
    for ((pos, annot) <- annotations) newAnnotations.put(annot, pos)
    annotationModel.modifyAnnotations(oldAnnotations, newAnnotations, null)
    oldAnnotations = annotations.map(_._2).toArray
    Console.println("Updated folding " + annotations.toList)
  }

  private def foldingAnnotations(region : VernacularRegion) : Stream[(Position, ProjectionAnnotation)] = {
    println("Collapsable " + region.outlineName + region.outlineNameExtra)
    if (region.pos.hasPosition)
      (pos2eclipsePos(region.pos), new ProjectionAnnotation) #:: region.getOutline.flatMap(foldingAnnotations)
    else
     region.getOutline.flatMap(foldingAnnotations)
  }
}

import org.eclipse.jface.viewers.ITreeContentProvider
protected class CoqContentProvider extends ITreeContentProvider {
  import dk.itu.sdg.coqparser.VernacularRegion
  import org.eclipse.jface.text.IDocument
  import org.eclipse.jface.viewers.Viewer
  import org.eclipse.ui.part.FileEditorInput
  import org.eclipse.ui.texteditor.IDocumentProvider

  var documentProvider : Option[IDocumentProvider] = None

  def dispose() : Unit = {}

  var content : List[VernacularRegion] = Nil
  val parser = new dk.itu.sdg.coqparser.VernacularParser {}
  var root : parser.VernacularDocument = null
  def parse(document : IDocument) : Unit = {
    require(document != null)
    import scala.util.parsing.input.CharSequenceReader

    println("parse the coq")
    val result = parser.parseString(document.get) match {
      case parser.Success(doc, _) => root = doc; doc
      case r : parser.NoSuccess => println("Could not parse: " + r)
    }
    // Remove beginning whitespace from parse result positions
    if (root != null) killLeadingWhitespace(root, document.get)
    println("got " + result)
  }

  def killLeadingWhitespace(reg : VernacularRegion, doc : String) : Unit = {
    import dk.itu.sdg.parsing._
    def advance(reg : VernacularRegion) : Unit = {
      reg.pos match {
        case NoLengthPosition => ()
        case RegionPosition(off, len) if len > 0 => {
          if ((doc(off) == ' ' || doc(off) == '\t' || doc(off) == '\n' || doc(off) == '\r') && reg.advancePosStart) advance(reg)
          else if (doc.substring(off, off+2) == "(*") advanceComment(reg, 1)
        }
        case _ => ()
      }
    }
    def advanceComment(reg : VernacularRegion, depth : Int) : Unit = {
      reg.pos match {
        case NoLengthPosition => ()
        case RegionPosition(off, len) if len > 0=> {
          println("Advancing " + doc.substring(off, off+len) + " at " + off)
          if (doc.substring(off, off+2) == "(*") {
            reg.advancePosStart(2)
            advanceComment(reg, depth + 1)
          }
          else if (doc.substring(off, off+2) == "*)") {
            reg.advancePosStart(2)
            if (depth > 1) advanceComment(reg, depth - 1)
            else advance(reg)
          }
          else {
            reg.advancePosStart
            advanceComment(reg, depth)
          }
        }
        case _ => ()
      }
    }
    advance(reg)
    reg.subRegions foreach { r => killLeadingWhitespace(r, doc) }
  }

  def inputChanged(viewer : Viewer, oldInput : Any, newInput : Any) : Unit = {
    if (newInput != null) {
      println("inputChanged")
      documentProvider foreach {prov => parse(prov.getDocument(newInput))}
    }
  }

  def hasChildren (obj : Any) : Boolean = obj match {
    case something : VernacularRegion if something.getOutline.length > 0 => true
    case _ => false
  }

  def getChildren (obj : Any) : Array[AnyRef] = {
    Console.println("getChildren" + obj)
    obj match {
      case something : VernacularRegion => something.getOutline.toArray
      case something : FileEditorInput => if (root == null) Array[AnyRef]() else root.getOutline.toArray
      case _ => Array[AnyRef]()
    }
  }
  def getElements (obj : Any) : Array[AnyRef] = {
    Console.println("getElements " + obj)
    getChildren(obj)
  }

  def getParent (obj : Any) : AnyRef = {
    Console.println("getParent " + obj)
    null //FIXME: Figure out how to do this - perhaps compute a table of parents?
  }
}

object CoqJavaDocumentProvider extends FileDocumentProvider {
  import org.eclipse.jface.text.IDocument
  import dk.itu.sdg.javaparser.JavaAST
  import scala.util.parsing.input.CharArrayReader

  object JavaToCoq extends JavaAST { }

  override def getDefaultEncoding () : String = "UTF-8"

  import dk.itu.sdg.javaparser.FinishAST
  def updateCoqCode (coq : IDocument, s : String, name : String) : Unit = {
    val (prog, spec) = JavaToCoq.parseNoSpec(new CharArrayReader(s.toArray), name)
    //Console.println("got prog " + prog + " and spec " + spec)
    //TODO: actually do a proper diff - there might be changes from the user in that area
    //like Definition of eeqptr, elt, eand, eneqptr in Snap.v
    val old = coq.get
    val pstart = old.indexOf(prog.substring(0, 15))
    val proend = prog.lastIndexOf("End ")
    val pend = old.indexOf(prog.substring(proend, prog.length))
    Console.println("old start " + pstart + " old end " + pend) //+ ":: " + old.substring(pstart, pend + (prog.length - proend)))
    coq.replace(pstart, pend + (prog.length - proend) - pstart, prog)
    val sstart = old.indexOf(spec.substring(0, spec.indexOf(" :=")))
    val specend = spec.lastIndexOf("}}.")
    val send = old.indexOf(spec.substring(specend, spec.length))
    Console.println("old spec start " + sstart + " old spec end " + send) // + ":: " + old.substring(sstart, send + (spec.length - specend)))
    val off = if (pend + (prog.length - proend) - pstart == prog.length) 0 else 1
    coq.replace(sstart + off, send + (spec.length - specend) - sstart, spec)
  }

  // The model of Coq code, used for outline view etc.
  import dk.itu.sdg.coqparser.VernacularRegion
  private val contentProviders : collection.mutable.Map[IDocument, CoqContentProvider] =
    collection.mutable.Map.empty
  def getOutline (doc : IDocument) : Option[CoqContentProvider] = {
    val outline = contentProviders.get(doc) getOrElse {
      val cprov = new CoqContentProvider()
      cprov.documentProvider = Some(this)
      contentProviders += (doc -> cprov)
      cprov
    }
    outline.parse(doc)
    Some(outline)
  }
}

import org.eclipse.jface.text.rules.IWordDetector

object CoqWordDetector extends IWordDetector {
  // TODO: Look up in spec...
  def isWordStart(character : Char) =
    (character >= 'a' && character <= 'z') ||
    (character >= 'A' && character <= 'Z') ||
    (character >= '0' && character <= '9')

  def isWordPart(character : Char) = isWordStart(character)
}

import org.eclipse.jface.text.rules.RuleBasedScanner
import dk.itu.sdg.coqparser.VernacularReserved

object CoqTokenScanner extends RuleBasedScanner with VernacularReserved with EclipseUtils {
  import org.eclipse.jface.text.rules.{IToken, MultiLineRule, SingleLineRule, Token, WordRule}
  import org.eclipse.jface.text.{IDocument, TextAttribute}
  import org.eclipse.swt.SWT.{BOLD, ITALIC}

  Console.println("Initializing CoqTokenScanner")

  private val black = color(0, 0, 0)
  private val white = color(255, 255, 255)

  private val keywordToken : IToken = new Token(new TextAttribute(black, white, BOLD))
  private val definerToken : IToken = new Token(new TextAttribute((0, 30, 0), white, BOLD))
  private val opToken : IToken = new Token(new TextAttribute((0, 0, 30), white, 0))
  private val commentToken : IToken = new Token(new TextAttribute((30, 30, 0), white, ITALIC))
  private val otherToken : IToken = new Token(new TextAttribute(black, white, 0))

  private val rules = Seq(
    new MultiLineRule("(*", "*)", commentToken),
    new SingleLineRule("(*", "*)", commentToken)
  )

  private val wordRule = new WordRule(CoqWordDetector, otherToken)
  for (k <- keyword) wordRule.addWord(k, definerToken)
  for (k <- keywords) wordRule.addWord(k, keywordToken)
  for (o <- operator) wordRule.addWord(o, opToken)

  setRules((rules ++ Seq(wordRule)).toArray)
}

import org.eclipse.jface.text.reconciler.IReconcilingStrategy
import org.eclipse.jface.text.IDocument
class CoqOutlineReconcilingStrategy(var document : IDocument, editor : CoqEditor) extends IReconcilingStrategy with EclipseUtils {
  import dk.itu.sdg.coqparser.VernacularRegion
  import org.eclipse.jface.text.{IDocument, IRegion, Position}
  import org.eclipse.swt.widgets.Display
  import org.eclipse.jface.text.reconciler._
  import org.eclipse.ui.views.contentoutline.IContentOutlinePage

  def reconcile (region : DirtyRegion, subregion : IRegion) : Unit = ()

  def reconcile (partition : IRegion) : Unit = {
    println("Reconciling " + partition + " with editor = " + editor + " and doc = " + document)
    val outline = CoqJavaDocumentProvider.getOutline(document) // updates model as side effect
    if (editor != null) {
      val outlinePage = editor.getAdapter(classOf[IContentOutlinePage]).asInstanceOf[CoqContentOutlinePage]
      Display.getDefault.asyncExec(new java.lang.Runnable {
        def run() : Unit = {
          outlinePage.update() //Update GUI outline
        }
      })
    } else
      println(" null editor")

    // update folding
    outline map (_.root) foreach { root =>
      if (editor != null) editor.updateFolding(root)
    }
  }

  def setDocument(doc : IDocument) : Unit = {
    document = doc
  }
}


import org.eclipse.jface.text.source.SourceViewerConfiguration

class CoqSourceViewerConfiguration(editor : CoqEditor) extends SourceViewerConfiguration {
  import org.eclipse.jface.text.presentation.{IPresentationReconciler, PresentationReconciler}
  import org.eclipse.jface.text.rules.DefaultDamagerRepairer
  import org.eclipse.jface.text.{TextAttribute,IDocument}
  import org.eclipse.jface.text.reconciler.{IReconciler, MonoReconciler}
  import org.eclipse.swt.graphics.{Color,RGB}
  import org.eclipse.swt.widgets.Display
  import org.eclipse.jface.text.source.ISourceViewer
  import org.eclipse.jface.text.contentassist.{IContentAssistant,ContentAssistant}

  override def getContentAssistant(v : ISourceViewer) : IContentAssistant = {
    val assistant= new ContentAssistant
    assistant.setContentAssistProcessor(CoqContentAssistantProcessor, IDocument.DEFAULT_CONTENT_TYPE)
    assistant
  }

  override def getReconciler (v : ISourceViewer) : IReconciler = {
    val strategy = new CoqOutlineReconcilingStrategy(v.getDocument, editor)
    val reconciler = new MonoReconciler(strategy, false)
    reconciler
  }

  override def getPresentationReconciler (v : ISourceViewer) : IPresentationReconciler = {
    val pr = new PresentationReconciler
    println("About to create damager/repairer")
    val ddr = new DefaultDamagerRepairer(CoqTokenScanner, new TextAttribute(new Color(Display.getDefault, new RGB(0, 0, 220))))
    println("Created damager/repairer successfully")
    pr.setDamager(ddr, IDocument.DEFAULT_CONTENT_TYPE)
    pr.setRepairer(ddr, IDocument.DEFAULT_CONTENT_TYPE)
    pr
  }
}

object EclipseTables {
  import scala.collection.mutable.HashMap
  import org.eclipse.jface.text.IDocument

  val DocToString = new HashMap[IDocument,String]()
  val StringToDoc = new HashMap[String,IDocument]()
}

object EclipseBoilerPlate {
  import org.eclipse.ui.{IWorkbenchWindow,IEditorPart}
  import org.eclipse.ui.texteditor.{ITextEditor,IDocumentProvider,AbstractTextEditor}
  import org.eclipse.jface.text.{IDocument,ITextSelection}

  var window : IWorkbenchWindow = null

  def getCaretPosition () : Int = {
    val sel = window.getActivePage.getActiveEditor.asInstanceOf[CoqEditor].getSelectionProvider.getSelection.asInstanceOf[ITextSelection]
    //Console.println("cursor position is " + sel.getLength + " @" + sel.getOffset)
    sel.getOffset
  }

  def getContent () : String = {
    val editorpart = window.getActivePage.getActiveEditor
    if (editorpart.isInstanceOf[CoqEditor]) {
      val texteditor = editorpart.asInstanceOf[CoqEditor]
      val dp : IDocumentProvider = texteditor.getDocumentProvider
      val doc : IDocument = dp.getDocument(texteditor.getEditorInput)
      doc.get
    } else {
      Console.println("not a CoqEditor!")
      ""
    }
  }

  import org.eclipse.core.resources.{IResource, IFile}
  import org.eclipse.ui.{IEditorInput, IFileEditorInput}

  def getResource () : IFile = {
    val editorpart = window.getActivePage.getActiveEditor
    if (editorpart.isInstanceOf[CoqEditor]) {
      val texteditor = editorpart.asInstanceOf[CoqEditor]
      val ei : IEditorInput = texteditor.getEditorInput
      if (ei.isInstanceOf[IFileEditorInput]) {
        val fei = ei.asInstanceOf[IFileEditorInput]
        fei.getFile
      } else {
        Console.println("not a file editor")
        null
      }
    } else null
  }

  def getProjectDir () : String = {
    getResource.getProject.getLocation.toOSString
  }

  import org.eclipse.swt.widgets.Display
  import org.eclipse.jface.dialogs.MessageDialog
  def warnUser (title : String, message : String) : Unit = {
    Display.getDefault.syncExec(
      new Runnable() {
        def run() = MessageDialog.openWarning(Display.getDefault.getActiveShell, title, message)
      })
  }

  import org.eclipse.core.runtime.IProgressMonitor
  import org.eclipse.jface.dialogs.ProgressMonitorDialog
  import org.eclipse.swt.widgets.Shell
  class MyProgressMonitorDialog (parent : Shell) extends ProgressMonitorDialog(parent) {
    import org.eclipse.swt.widgets.Button
    def getC () : Button = cancel
  }

  import org.eclipse.swt.events.{MouseListener, MouseEvent}
  private var p : IProgressMonitor = null
  private var pmd : MyProgressMonitorDialog = null
  var multistep : Boolean = false
  private val nam = "Coq interaction"
  def startProgress () : Unit = {
    //Console.println("Starting progress monitor")
    if (p == null) {
    //assert(pmd == null)
      pmd = new MyProgressMonitorDialog(Display.getDefault.getActiveShell)
      pmd.setCancelable(true)
      pmd.open
      pmd.getC.addMouseListener(new MouseListener() {
        override def mouseDoubleClick (m : MouseEvent) : Unit = ()
        override def mouseDown (m : MouseEvent) : Unit = CoqTop.interruptCoq
        override def mouseUp (m : MouseEvent) : Unit = ()
      })
      p = pmd.getProgressMonitor
      p.beginTask(nam, IProgressMonitor.UNKNOWN)
    }
  }

  def nameProgress (n : String) : Unit = {
    if (p != null)
      Display.getDefault.asyncExec(
        new Runnable() {
          def run() = {
            if (p != null)
              p.setTaskName(nam + ": " + n)
          }})
  }

  def finishedProgress () : Unit = {
    //Console.println("Finished progress monitor " + p)
    if (p != null && !multistep) {
      val oldp = p
      val oldpmd = pmd
      p = null
      pmd = null
      Display.getDefault.asyncExec(
        new Runnable() {
          def run() = {
            oldp.done
            oldpmd.close
          }
        })
    }
  }

  import org.eclipse.core.resources.IMarker

  def mark (text : String) : Unit = {
    val file = getResource
    val marker = file.createMarker(IMarker.PROBLEM)
    marker.setAttribute(IMarker.MESSAGE, text)
    marker.setAttribute(IMarker.LOCATION, file.getName)
    marker.setAttribute(IMarker.CHAR_START, DocumentState.position + 1)
    marker.setAttribute(IMarker.CHAR_END, DocumentState.position + DocumentState.oldsendlen - 1) //for tha whitespace
    marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR)
    marker.setAttribute(IMarker.TRANSIENT, true)
  }

  def unmark () : Unit = {
    getResource.deleteMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)
  }

  def maybeunmark (until : Int) : Unit = {
    val marks = getResource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO)
    marks.foreach(x => if (x.getAttribute(IMarker.CHAR_START, 0) < until) x.delete)
  }
}

object DocumentState {
  import org.eclipse.jface.text.{ITextViewer}
  import org.eclipse.swt.graphics.{Color,RGB}
  import org.eclipse.swt.widgets.Display

  var sourceview : ITextViewer = null
  //var position : Int = 0
  var sendlen : Int = 0
  var totallen_ : Int = 0
  def totallen : Int = totallen_
  def totallen_= (n : Int) {
    if (position > n)
      position = n - 1
    totallen_ = n
  }
  var coqstart : Int = 0
  var realundo : Boolean = false

  import org.eclipse.core.resources.IMarker
  var coqmarker : IMarker = null

  var position_ : Int = 0
  def position : Int = position_
  def position_= (x : Int) {
    //Console.println("new pos is " + x + " (old was " + position_ + ")");
    if (coqmarker == null) {
      val file = EclipseBoilerPlate.getResource
      coqmarker = file.createMarker(IMarker.BOOKMARK)
      coqmarker.setAttribute(IMarker.MESSAGE, "coq position")
      coqmarker.setAttribute(IMarker.LOCATION, file.getName)
      coqmarker.setAttribute(IMarker.TRANSIENT, true)
      coqmarker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO)
    }
    coqmarker.setAttribute(IMarker.CHAR_START, x)
    coqmarker.setAttribute(IMarker.CHAR_END, x)
    position_ = x
  }

  def undoAll () : Unit = synchronized {
    if (sourceview != null) {
      val bl = new Color(Display.getDefault, new RGB(0, 0, 0))
      Display.getDefault.syncExec(
        new Runnable() {
          def run() = sourceview.setTextColor(bl, 0, totallen, true)
        });
    }
  }

  var lastcommit : Int = 0
  var oldsendlen : Int = 0
  def undo (id : Int) : Unit = synchronized {
    //Console.println("undo (@" + position + ", " + sendlen + ")")
    if (sendlen != 0 && id > lastcommit) {
      lastcommit = id
      if (realundo) {
        realundo = false
        val bl = new Color(Display.getDefault, new RGB(0, 0, 0))
        val start = scala.math.max(position - sendlen, 0)
        Console.println("undo (start " + start + " len " + sendlen + " totallen " + totallen + ")")
        if (CoqUndoAction.text == None)
          Display.getDefault.syncExec(
            new Runnable() {
              def run() = sourceview.setTextColor(bl, start, sendlen, true)
            });
        position = start
        sendlen = 0
      } else { //just an error
        Console.println("undo: barf")
        oldsendlen = sendlen
        sendlen = 0
      }
    }
  }

  def commit (id : Int) : Unit = synchronized {
    //Console.println("commited (@" + position + ", " + sendlen + ")")
    if (sendlen != 0 && id > lastcommit) {
      lastcommit = id
      Console.println("commited - and doing some work")
      val bl = new Color(Display.getDefault, new RGB(0, 0, 220))
      val end = scala.math.min(sendlen, totallen - position)
      //Console.println("commiting, end is " + end + " (pos + len: " + (position + sendlen) + ")" + ", pos:" + position + " sourceview: " + sourceview)
      Display.getDefault.syncExec(
        new Runnable() {
          def run() = sourceview.setTextColor(bl, position, end, true)
        });
      position += end
      sendlen = 0
    }
  }
}

object EclipseConsole {
  import org.eclipse.ui.console.{MessageConsole,MessageConsoleStream,IConsole,IConsoleManager,ConsolePlugin}
  var out : MessageConsoleStream = null

  def initConsole () : Unit = {
    val conman : IConsoleManager = ConsolePlugin.getDefault.getConsoleManager
    val existing = conman.getConsoles
    var outputconsole : MessageConsole = null
    if (existing.length > 0) {
      Console.println("have existing console(s) : " + existing.length)
      outputconsole = existing(0).asInstanceOf[MessageConsole]
    } else {
      Console.println("needed to create new console")
      val mycon = new MessageConsole("Coq", null)
      val cons = new Array[IConsole](1)
      cons(0) = mycon
      conman.addConsoles(cons)
      outputconsole = mycon
    }
    out = outputconsole.newMessageStream
    out.setEncoding("UTF-8")
  }

//   display console in workbench!
//   IWorkbenchPage page = ...; obtain the active page
//   String id = IConsoleConstants.ID_CONSOLE_VIEW;
//   IConsoleView view = (IConsoleView) page.showView(id);
//   view.display(myConsole);
}

import org.eclipse.ui.part.ViewPart

class GoalViewer extends ViewPart {
  import org.eclipse.swt.widgets.{Composite,Label,Text}
  import org.eclipse.swt.SWT
  import org.eclipse.swt.layout.{GridData,GridLayout}
  import org.eclipse.swt.graphics.{Color,RGB}
  import org.eclipse.swt.widgets.Display


  var hypos : Text = null
  var goal : Text = null
  var othersubs : Text = null
  var comp : Composite = null

  override def createPartControl (parent : Composite) : Unit = {
    comp = new Composite(parent, SWT.NONE)
    comp.setLayout(new GridLayout(1, true))
    hypos = new Text(comp, SWT.READ_ONLY | SWT.MULTI)
    hypos.setLayoutData(new GridData(GridData.FILL_HORIZONTAL))
    //hypos.setText("foo\nbar")
    new Label(comp, SWT.SEPARATOR | SWT.HORIZONTAL).setLayoutData(new GridData(GridData.FILL_HORIZONTAL))
    goal = new Text(comp, SWT.READ_ONLY | SWT.MULTI)
    goal.setLayoutData(new GridData(GridData.FILL_HORIZONTAL))
    //goal.setText("baz")
    val other = new Label(comp, SWT.READ_ONLY)
    other.setLayoutData(new GridData(GridData.FILL_HORIZONTAL))
    other.setText("other subgoals")
    othersubs = new Text(comp, SWT.READ_ONLY | SWT.MULTI)
    othersubs.setLayoutData(new GridData(GridData.FILL_HORIZONTAL))
    //othersubs.setText("buz\nfoobar")
    CoqOutputDispatcher.goalviewer = this
  }

  def clear () : Unit = {
    Display.getDefault.syncExec(
      new Runnable() {
        def run() = {
          hypos.setText("")
          goal.setText("")
          othersubs.setText("")
          comp.layout
        }
      })
  }

  def setFocus() : Unit = {
  //  viewer.getControl.setFocus
  }
}

object GoalViewer extends GoalViewer { }

import org.eclipse.jface.text.contentassist.IContentAssistProcessor

object CoqContentAssistantProcessor extends IContentAssistProcessor {
  import org.eclipse.jface.text.contentassist.{IContextInformationValidator,IContextInformation,ICompletionProposal,CompletionProposal,ContextInformation}
  import org.eclipse.jface.text.ITextViewer
//  import org.eclipse.ui.examples.javaeditor.JavaEditorMessages
  import java.text.MessageFormat

  private val completions = Array("intros", "assumption", "induction ", "apply")

  def computeCompletionProposals(viewer : ITextViewer, documentOffset : Int) : Array[ICompletionProposal] = {
    val result= new Array[ICompletionProposal](completions.length);
	var i : Int = 0
    while (i < completions.length) {
//	  val info = new ContextInformation(completions(i), MessageFormat.format(JavaEditorMessages.getString("CompletionProcessor.Proposal.ContextInfo.pattern"), Array(completions(i))));
//	  result(i)= new CompletionProposal(completions(i), documentOffset, 0, completions(i).length, null, completions(i), info, MessageFormat.format(JavaEditorMessages.getString("CompletionProcessor.Proposal.hoverinfo.pattern"), Array(completions(i))));
	  val info = new ContextInformation(completions(i), MessageFormat.format("CompletionProcessor.Proposal.ContextInfo.pattern"));
	  result(i)= new CompletionProposal(completions(i), documentOffset, 0, completions(i).length, null, completions(i), info, MessageFormat.format("CompletionProcessor.Proposal.hoverinfo.pattern"));
	  i += 1
    }
	return result;
    }

  def computeContextInformation(viewer : ITextViewer, offset : Int) : Array[IContextInformation] = null
  def getCompletionProposalAutoActivationCharacters() : Array[Char] = Array('.')
  def getContextInformationAutoActivationCharacters() : Array[Char] = null
  def getContextInformationValidator() : IContextInformationValidator = null
  def getErrorMessage() : String = "not yet implemented"
}


import org.eclipse.ui.views.contentoutline.ContentOutlinePage

class CoqContentOutlinePage extends ContentOutlinePage {
  import dk.itu.sdg.coqparser.VernacularRegion
  import org.eclipse.jface.text.{IDocument, DocumentEvent}
  import org.eclipse.jface.viewers.{ITreeContentProvider, LabelProvider, TreeViewer, Viewer, SelectionChangedEvent, StructuredSelection}
  import org.eclipse.swt.widgets.{Composite, Control}
  import org.eclipse.ui.part.FileEditorInput
  import org.eclipse.ui.texteditor.{IDocumentProvider, ITextEditor}

  var documentProvider : Option[IDocumentProvider] = None
  var textEditor : ITextEditor = null
  def this(provider : org.eclipse.ui.texteditor.IDocumentProvider, editor : org.eclipse.ui.texteditor.ITextEditor) = {
    this()
    documentProvider = Some(provider)
    textEditor = editor
  }

  var input : Any = null // TODO: Make into less of a direct Java port

  def setInput (arg : AnyRef) : Unit = {
    input = arg
    update()
  }

  def update () : Unit = {
    val viewer : TreeViewer = getTreeViewer()
    println("Called update when input = [" + input + "] and viewer = [" + viewer + "]")

    if (viewer != null) {
      val control : Control = viewer.getControl()
      if (control != null && !control.isDisposed()) {
        control.setRedraw(false)
        viewer.setInput(input)
        val doc = textEditor.getDocumentProvider.getDocument(input)
        Console.println("  In update(), document is " + doc)
        CoqJavaDocumentProvider.getOutline(doc) foreach { cprov => viewer.setContentProvider(cprov) }
        viewer.expandAll()
        control.setRedraw(true)
      }
    }
  }

  override def selectionChanged(event : SelectionChangedEvent) : Unit = {
    import dk.itu.sdg.parsing.{LengthPosition, NoLengthPosition, RegionPosition}

    super.selectionChanged(event)

    val selection = event.getSelection

    if (!selection.isEmpty) {
      val sel = selection.asInstanceOf[StructuredSelection].getFirstElement.asInstanceOf[VernacularRegion]
      sel.pos match {
        case NoLengthPosition => println("missing position from parser!"); textEditor.resetHighlightRange
        case at : RegionPosition => textEditor.setHighlightRange(at.offset, at.length, true)
        case _ => ()
      }
    }
  }

  class CoqLabelProvider extends LabelProvider {
    override def getText(obj : AnyRef) : String = obj match {
      case something : VernacularRegion => something.outlineName + " " + something.outlineNameExtra
      case something => something.toString
    }
  }

  override def createControl(parent : Composite) : Unit = {
    super.createControl(parent)

    val viewer : TreeViewer = getTreeViewer()
    viewer.setContentProvider(new CoqContentProvider()) // Just to satisfy precondition - replaced later!
    viewer.setLabelProvider(new CoqLabelProvider())
    viewer.addSelectionChangedListener(this)

    if (input != null)
      viewer.setInput(input)

    update()
  }
}

