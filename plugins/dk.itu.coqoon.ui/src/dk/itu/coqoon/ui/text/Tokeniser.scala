/* Tokeniser.scala
 * A string tokeniser that works by inspecting pushdown automaton transitions
 * Copyright © 2015 Alexander Faithfull
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

package dk.itu.coqoon.ui.text

class Tokeniser(val automaton : PushdownAutomaton[Char]) {
  import Tokeniser._
  import PushdownAutomaton.State

  type TransitionInspector = Transition => Option[(State, Int)]
  protected object TransitionInspector {
    def apply(transition : TransitionInspector) = append(transition)
    def append(transition : TransitionInspector) =
      appendTransitionInspector(transition)
    def prepend(transition : TransitionInspector) =
      prependTransitionInspector(transition)
  }
  protected object InterestingTransition {
    def apply(transition : Transition, begins : State, leadin : Int) =
      appendTransitionInspector {
        case t if t == transition => Some((begins, leadin))
        case _ => None
      }
  }

  class TokenIterator(initialState : State, start : RType#Execution,
      input : CharSequence) extends Iterator[Token] {
    private var lastTokenStart = 0
    private var lastTokenType = initialState
    private var position = 0
    private var state = start
    private var exhausted = false

    override def hasNext = {
      prime()
      nextToken != None
    }
    override def next : Token = {
      prime()
      nextToken match {
        case Some(t) =>
          nextToken = None
          return t
        case None =>
          nextToken.get
      }
    }

    private var nextToken : Option[Token] = None
    private def prime() : Unit = {
      import dk.itu.coqoon.core.utilities.Substring
      if (!exhausted && nextToken == None) {
        while (nextToken == None && position < input.length) {
          val c = input.charAt(position)
          state.accept(c) match {
            case Some((t, e)) =>
              position += 1
              transitionInspectors.flatMap(ti => ti(t)).headOption foreach {
                case (begins, leadin)
                    if lastTokenType != begins =>
                  val tokenContent = Substring(
                      input, lastTokenStart, position - leadin)
                  nextToken = Some((lastTokenType, tokenContent.toString))
                  lastTokenStart = Math.max(0, position - leadin)
                  lastTokenType = begins
                case _ =>
              }
              state = e
            case _ =>
              /* Oh no, bail out */
              ???
          }
        }

        if (nextToken == None) {
          /* Produce a final token */
          val tokenContent =
            Substring(input, lastTokenStart, position)
          nextToken = Some((lastTokenType, tokenContent.toString))
          lastTokenStart = position
          exhausted = true
        }
      }
    }
  }

  private var transitionInspectors : Seq[TransitionInspector] = Seq()
  private def appendTransitionInspector(t : TransitionInspector) =
    transitionInspectors :+= t
  private def prependTransitionInspector(t : TransitionInspector) =
    transitionInspectors +:= t

  def tokens(start : State, input : CharSequence) : Iterator[Token] =
    new TokenIterator(start, automaton.Execution(start, Seq()), input)
}
object Tokeniser {
  type Token = (PushdownAutomaton.State, String)

  protected type RType = PushdownAutomaton[Char]
  protected type Transition = PushdownAutomaton.Transition[Char]
}