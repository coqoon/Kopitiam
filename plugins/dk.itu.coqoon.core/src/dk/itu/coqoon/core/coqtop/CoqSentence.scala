/* CoqSentence.scala
 * Coq sentence extraction
 * Copyright © 2013 Alexander Faithfull
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License. */

package dk.itu.coqoon.core.coqtop

import dk.itu.coqoon.core.utilities.Substring

object CoqSentence {
  final val CommentStart = """^\(\*""".r.unanchored
  final val CommentEnd = """^\*\)""".r.unanchored
  final val QuotationMark = "^\"".r.unanchored
  final val Bullet = """^(\+|-|\*)""".r.unanchored
  final val CurlyBracket = """^(\{|\})(\s|$)""".r.unanchored
  final val FullStop = """^\.(\s|$)""".r.unanchored
  final val Ellipsis = """^\.\.\.(\s|$)""".r.unanchored

  final val DotRun = """^(\.+)(\s|$)""".r.unanchored
  final val WhitespaceRun = """^(\s+)""".r.unanchored

  type Sentence = (Substring, Boolean)

  def getNextSentence(
      doc : CharSequence, offset : Int = 0) : Option[Sentence] = {
    var i = offset
    var commentDepth = 0
    var inString = false
    var content = false
    while (i < doc.length) Substring(doc, i) match {
      case CommentStart() if !inString =>
        commentDepth += 1
        i += 2
      case CommentEnd() if !content && !inString && commentDepth == 1 =>
        return Some((Substring(doc, offset, i + 2), true))
      case CommentEnd() if !inString && commentDepth > 0 =>
        commentDepth -= 1
        i += 2
      case QuotationMark() =>
        inString = !inString
        i += 1
      case FullStop(_) if !inString && commentDepth == 0 =>
        return Some((Substring(doc, offset, i + 1), false))
      case Ellipsis(_) if !inString && commentDepth == 0 =>
        return Some((Substring(doc, offset, i + 3), false))
      case CurlyBracket(t, _) if !content && !inString && commentDepth == 0 =>
        return Some((Substring(doc, offset, i + 1), false))
      case Bullet(_) if !content && !inString && commentDepth == 0 =>
        return Some((Substring(doc, offset, i + 1), false))
      case DotRun(dots, end) if !inString && commentDepth == 0 =>
        content = true
        i += dots.length + end.length
      case WhitespaceRun(ws) =>
        i += ws.length
      case _ =>
        if (commentDepth == 0)
          content = true
        i += 1
    }
    None
  }

  def getNextSentences(
      doc : CharSequence, from : Int, to : Int) : Seq[Sentence] = {
    val steps = Seq.newBuilder[Sentence]
    var offset = from
    while (offset <= to) getNextSentence(doc, offset) match {
      case Some(s @ (text, synthetic)) =>
        offset = text.end
        if (offset <= to)
          steps += s
      case _ => offset = Int.MaxValue
    }
    steps.result
  }

  object Classifier {
    sealed abstract class Classification

    case class Assertion(
        keyword : String, identifier : String, body : String)
            extends Classification
    object AssertionSentence {
      val expr =
        ("^\\s*(Theorem|Lemma|Remark|Fact|Corollary|" +
         "Proposition|Definition|Example)\\s+([a-zA-Z0-9_]+)(?s)(.*)\\.$").r
      def unapply(input : CharSequence) = input match {
        case expr(keyword, identifier, body) =>
          Some((keyword, identifier, body))
        case _ => None
      }
    }

    case class Fixpoint(
        keyword : String, identifier : String, body : String)
            extends Classification
    object FixpointSentence {
      val expr =
        ("^\\s*(Fixpoint|CoFixpoint)\\s+([a-zA-Z0-9_]+)(?s)(.*)\\.$").r
      def unapply(input : CharSequence) = input match {
        case expr(keyword, identifier, body) =>
          Some((keyword, identifier, body))
        case _ => None
      }
    }

    case class Inductive(
        keyword : String, identifier : String, body : String)
            extends Classification
    object InductiveSentence {
      val expr =
        ("^\\s*(Inductive|CoInductive)\\s+([a-zA-Z0-9_]+)(?s)(.*)\\.$").r
      def unapply(input : CharSequence) = input match {
        case expr(keyword, identifier, body) =>
          Some((keyword, identifier, body))
        case _ => None
      }
    }

    case class Definition(
        keyword : String, identifier : String, binders : String, body : String)
            extends Classification
    object DefinitionSentence {
      val expr =
        ("^\\s*(Definition|Let)\\s+([a-zA-Z0-9_]+)(?s)(.*):=(.*)\\.$").r
      def unapply(input : CharSequence) = input match {
        case expr(keyword, identifier, binders, body) =>
          Some((keyword, identifier, binders, body))
        case _ => None
      }
    }

    case class ProofEnd(keyword : String) extends Classification
    object ProofEndSentence {
      val expr = ("^\\s*(Qed|Defined|Admitted)\\s*\\.$").r
      def unapply(input : CharSequence) = input match {
        case expr(keyword) => Some((keyword))
        case _ => None
      }
    }
  }
}
