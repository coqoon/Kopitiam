/* (c) 2010-2011 Hannes Mehnert and David Christiansen
 * Copyright © 2013, 2014 Alexander Faithfull */

package dk.itu.coqoon.ui

import org.eclipse.ui.editors.text.TextSourceViewerConfiguration

class BaseCoqSourceViewerConfiguration(
    editor : BaseCoqEditor) extends TextSourceViewerConfiguration {
  import org.eclipse.jface.text.presentation.{IPresentationReconciler, PresentationReconciler}
  import org.eclipse.jface.text.{TextAttribute,IDocument}
  import org.eclipse.jface.text.reconciler.{IReconciler, MonoReconciler}
  import org.eclipse.swt.graphics.{Color,RGB}
  import org.eclipse.jface.text.source.ISourceViewer

  override def getAutoEditStrategies(v : ISourceViewer, contentType : String) =
    contentType match {
      case CoqPartitions.Types.STRING | CoqPartitions.Types.COMMENT =>
        Array()
      case _ =>
        /* Using _ here rather than Types.COQ makes the automatic indentation
         * work properly at the end of the file, where there's a mysterious
         * shadow realm whose content type is IDocument.DEFAULT_CONTENT_TYPE */
        Array(new CoqAutoEditStrategy)
    }

  import org.eclipse.jface.text.formatter.{
    MultiPassContentFormatter, IContentFormatter}
  override def getContentFormatter(v : ISourceViewer) : IContentFormatter = {
    val formatter = new MultiPassContentFormatter(
        getConfiguredDocumentPartitioning(v), IDocument.DEFAULT_CONTENT_TYPE)
    formatter.setMasterStrategy(new CoqMasterFormattingStrategy)
    formatter.setSlaveStrategy(new CoqSubservientFormattingStrategy,
        CoqPartitions.Types.COQ)
    formatter.setSlaveStrategy(new CommentSubservientFormattingStrategy,
        CoqPartitions.Types.COMMENT)
    formatter
  }

  override def getReconciler (v : ISourceViewer) : IReconciler = {
    val strategy = new CoqWorkingCopyReconcilingStrategy(v.getDocument, editor)
    val reconciler = new MonoReconciler(strategy, false)
    reconciler
  }

  override def getConfiguredContentTypes(v : ISourceViewer) =
    IDocument.DEFAULT_CONTENT_TYPE +: CoqPartitions.TYPES
  override def getConfiguredDocumentPartitioning(v : ISourceViewer) =
    CoqPartitions.COQ

  override def getPresentationReconciler(v : ISourceViewer) = {
    val pr = new PresentationReconciler
    pr.setDocumentPartitioning(CoqPartitions.COQ)
    BaseCoqSourceViewerConfiguration.addDamagerRepairers(pr)
  }

  import org.eclipse.jface.text.quickassist.QuickAssistAssistant

  override def getQuickAssistAssistant(v : ISourceViewer) = {
    val qa = new QuickAssistAssistant
    qa.setQuickAssistProcessor(new CoqQuickAssistProcessor)
    qa
  }
}
object BaseCoqSourceViewerConfiguration {
  import org.eclipse.jface.text.rules.DefaultDamagerRepairer
  import org.eclipse.jface.text.presentation.PresentationReconciler

  def addDamagerRepairers(pr : PresentationReconciler) = {
    for ((typ, scanner) <- Seq(
        (CoqPartitions.Types.COQ,
            new DefaultDamagerRepairer(new CoqTokenScanner)),
        (CoqPartitions.Types.COMMENT,
            new DefaultDamagerRepairer(new CommentTokenScanner)),
        (CoqPartitions.Types.STRING,
            new DefaultDamagerRepairer(new StringTokenScanner)))) {
      pr.setDamager(scanner, typ)
      pr.setRepairer(scanner, typ)
    }
    pr
  }
}

import dk.itu.coqoon.ui.utilities.UIUtils
import org.eclipse.jface.text.rules.RuleBasedScanner
import org.eclipse.jface.text.TextAttribute
import org.eclipse.jface.text.rules.{Token, IToken}

class CoqTokenScanner extends RuleBasedScanner {
  import CoqTokenScanner._

  private val keyword =
    Seq("Axiom", "Conjecture", "Parameter", "Parameters", "Variable",
        "Variables", "Hypothesis", "Hypotheses", "Definition", "Example",
        "Inductive", "CoInductive", "Fixpoint", "CoFixpoint", "Program",
        "Goal", "Let", "Remark", "Fact", "Corollary", "Proposition", "Lemma",
        "Instance", "Theorem", "Tactic", "Ltac", "Notation", "Infix", "Add",
        "Record", "Section", "Module", "Require", "Import", "Export", "Open",
        "Proof", "End", "Qed", "Admitted", "Save", "Defined", "Print", "Eval",
        "Check", "Hint", "Unset")

  private val operator =
    Seq("!", "%", "&", "&&", "(", "()", ")", "*", "+", "++", ",", "-", "->",
        ".", ".(", "..", "/", "/\\", ":", "::", ":<", ":=", ":>", ";", "<",
        "<-", "<->", "<:", "<=", "<>", "=", "=>", "=_D", ">", ">->", ">=", "?",
        "?=", "@", "[", "\\/", "]", "^", "{", "|", "|-", "||", "}", "~")

  // The reserved words as listed in the reference manual
  private val keywords =
    Seq("_", "as", "at", "cofix", "else", "end", "exists", "exists2", "fix",
        "for", "forall", "fun", "if", "IF", "in", "let", "match", "mod",
        "Prop", "return", "Set", "then", "Type", "using", "where", "with")

  private val options = /* Correct as of Coq 8.4pl3 */
    Seq("Whelp Server", "Whelp Getter", "Virtual Machine",
        "Verbose Compat Notations", "Undo", "Typeclasses Depth",
        "Typeclasses Debug", "Transparent Obligations",
        "Tactic Pattern Unification", "Tactic Evars Pattern Unification",
        "Strongly Strict Implicit", "Strict Proofs", "Strict Implicit",
        "Silent", "Short Module Printing", "Rewriting Schemes",
        "Reversible Pattern Implicit", "Printing Wildcard", "Printing Width",
        "Printing Universes", "Printing Synth", "Printing Records",
        "Printing Projections", "Printing Notations", "Printing Matching",
        "Printing Implicit Defensive", "Printing Existential Instances",
        "Printing Depth", "Printing Coercions", "Printing All",
        "Parsing Explicit", "Maximal Implicit Insertion", "Ltac Debug",
        "Intuition Iff Unfolding", "Inline Level", "Info Trivial",
        "Info Eauto", "Info Auto", "Implicit Arguments", "Hyps Limit",
        "Hide Obligations", "Functional Induction Rewrite Dependent",
        "Function_raw_tcc", "Function_debug", "Firstorder Depth",
        "Extraction TypeExpand", "Extraction Optimize",
        "Extraction KeepSingleton", "Extraction Flag", "Extraction AutoInline",
        "Extraction AccessOpaque", "Equality Scheme", "Elimination Schemes",
        "Discriminate Introduction", "Dependent Propositions Elimination",
        "Default Timeout", "Default Proof Mode", "Decidable Equality Schemes",
        "Debug Trivial", "Debug Eauto", "Debug Auto", "Contextual Implicit",
        "Congruence Verbose", "Congruence Depth", "Compat Notations",
        "Case Analysis Schemes", "Bullet Behavior", "Boxed Values",
        "Boolean Equality Schemes", "Automatic Introduction",
        "Automatic Coercions Import", "Autoinstance", "Atomic Load")

  private val wordRule = new FuturisticWordRule(CoqWordDetector, otherToken)
  for (k <- keyword) wordRule.addWord(k, definerToken)
  for (k <- keywords) wordRule.addWord(k, keywordToken)
  private val opRule = new BasicRule("operator")
  for (o <- operator) opRule.recognise(o, opToken)
  private val optionRule = new BasicRule("option")
  for (o <- options) optionRule.recognise(o, optionToken)

  setRules(Array(optionRule, wordRule, opRule))
}
private object CoqTokenScanner {
  import org.eclipse.swt.SWT.{BOLD, ITALIC}

  val black = UIUtils.Color(0, 0, 0)
  val white = UIUtils.Color(255, 255, 255)

  val keywordToken : IToken = new Token(new TextAttribute(
      UIUtils.Color.fromPreference("coqKeywordFg"), white, BOLD))
  val definerToken : IToken = new Token(new TextAttribute(
      UIUtils.Color.fromPreference("coqKeywordFg"), white, BOLD))
  val opToken : IToken = new Token(
      new TextAttribute(UIUtils.Color(0, 0, 128), white, 0))
  val optionToken : IToken = new Token(
      new TextAttribute(UIUtils.Color(200, 0, 100), white, 0))
  val otherToken : IToken = new Token(new TextAttribute(black, white, 0))
}

class CommentTokenScanner extends RuleBasedScanner {
  import CommentTokenScanner._

  private val commentRule = new BasicRule("!comment")
  commentRule.getStartState.setFallback(Some(commentRule.getStartState))
  commentRule.getStartState.setToken(Some(commentToken))
  setRules(Array(commentRule))
}
private object CommentTokenScanner {
  import org.eclipse.swt.SWT

  val commentToken : IToken = new Token(new TextAttribute(
      UIUtils.Color(63, 127, 95), CoqTokenScanner.white, SWT.ITALIC))
}

class StringTokenScanner extends RuleBasedScanner {
  import StringTokenScanner._

  private val stringRule = new BasicRule("!string")
  stringRule.getStartState.setFallback(Some(stringRule.getStartState))
  stringRule.getStartState.setToken(Some(stringToken))
  setRules(Array(stringRule))
}
private object StringTokenScanner {
  val stringToken : IToken = new Token(new TextAttribute(
      UIUtils.Color(0, 0, 255), CoqTokenScanner.white, 0))
}

import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.reconciler.IReconcilingStrategy
class CoqWorkingCopyReconcilingStrategy(var document : IDocument,
    editor : BaseCoqEditor) extends IReconcilingStrategy {
  import org.eclipse.jface.text.{IRegion, Position}
  import org.eclipse.jface.text.reconciler._

  override def reconcile(dr : DirtyRegion, r : IRegion) = reconcile(dr)

  override def reconcile(partition : IRegion) = UIUtils.asyncExec {
    editor.workingCopy.get.setContents(document.get)
    editor.updateFolding
  }

  override def setDocument(doc : IDocument) = (document = doc)
}