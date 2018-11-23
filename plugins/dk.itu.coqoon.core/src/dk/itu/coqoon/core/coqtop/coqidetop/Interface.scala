package dk.itu.coqoon.core.coqtop.coqidetop

import scala.xml.{Elem, Attribute}

object Interface {
  /* This is just a Scala translation of lib/interface.mli */
  type raw = Boolean
  type verbose = Boolean

  case class goal(
    goal_id : String,
    goal_hyp : List[String],
    goal_ccl : String)

  case class evar(
    evar_info : String)

  case class status(
    status_path : List[String],
    status_proofname : Option[String],
    status_allproofs : List[String],
    status_proofnum : Int)

  case class goals(
    fg_goals : List[goal],
    bg_goals : List[(List[goal], List[goal])],
    shelved_goals : List[goal],
    given_up_goals : List[goal])

  type hint = List[(String, String)]

  type option_name = List[String]

  abstract class option_value
  case class BoolValue(
    value : Boolean) extends option_value
  case class IntValue(
    value : Option[Int]) extends option_value
  case class StringValue(
    value : String) extends option_value
  case class StringOptValue(
    value : String) extends option_value

  case class option_state(
    opt_sync : Boolean,
    opt_depr : Boolean,
    opt_name : String,
    opt_value : option_value)

  abstract class search_constraint
  case class Name_Pattern(
    value : String) extends search_constraint
  case class Type_Pattern(
    value : String) extends search_constraint
  case class SubType_Pattern(
    value : String) extends search_constraint
  case class In_Module(
    value : List[String]) extends search_constraint
  case class Include_Blacklist() extends search_constraint

  type search_flags = List[(search_constraint, Boolean)]

  case class coq_object[A](
    coq_object_prefix : List[String],
    coq_object_qualid : List[String],
    coq_object_object : A)

  case class coq_info(
    coqtop_version : String,
    protocol_version : String,
    release_date : String,
    compile_date : String)

  type location = Option[(Int, Int)]
  type state_id = Int
  type route_id = Int
  
  /* Obsolete(?) */
  type edit_id = Int

  abstract class value[A]
  case class Good[A](value : A) extends value[A]
  case class Fail[A](value : (state_id, location, String)) extends value[A]

  type union[A, B] = Either[A, B]

  object XML {
    private def _attr(e : Elem, a : String) =
      Option(e \@ a).filterNot(_.isEmpty)
    private def _elch(e : Elem) = e.child.filter(_.isInstanceOf[Elem])
    def unwrapValue[A](a : Elem => A)(e : Elem) : value[A] =
      (_attr(e, "val"), _elch(e)) match {
        case (Some("good"), Seq(c : Elem)) =>
          Good(a(c))
        case (Some("fail"), Seq(sid : Elem, error : Elem)) =>
          val loc = 
            (_attr(e, "loc_s"), _attr(e, "loc_e")) match {
              case (Some(start), Some(end)) =>
                Some((start.toInt, end.toInt))
              case _ =>
                None
            }
          Fail(unwrapStateId(sid), loc, unwrapString(error))
      }

    def wrapUnit() = <unit />
    def unwrapUnit(e : Elem) = ()
    
    def wrapString(a : String) = <string>{a}</string>
    def unwrapString(e : Elem) = e.text.trim()
  
    def wrapInt(a : Int) = <int>{a}</int>
    def unwrapInt(e : Elem) = unwrapString(e).toInt

    def wrapStateId(a : state_id) = <state_id val={a.toString} />
    def unwrapStateId(e : Elem) : state_id = _attr(e, "val").get.toInt

    def wrapBoolean(a : Boolean) = <bool val={a.toString} />
    def unwrapBoolean(e : Elem) =
      _attr(e, "val") match {
        case Some("true") => true
        case Some("false") => false
      }

    def unwrapList[A](a : Elem => A)(e : Elem) =
      e.child.map(_.asInstanceOf[Elem]).map(a).toList
    def unwrapOption[A](a : Elem => A)(e : Elem) =
      _attr(e, "val") match {
        case Some("some") =>
          Some(a(e.child(0).asInstanceOf[Elem]))
        case _ =>
          None
      }
    def wrapPair[A, B](a : A => Elem, b : B => Elem)(v : (A, B)) =
      <pair>{a(v._1)}{b(v._2)}</pair>
    def unwrapPair[A, B](a : Elem => A, b : Elem => B)(e : Elem) =
      _elch(e) match {
        case Seq(c1 : Elem, c2 : Elem) =>
          (a(c1), b(c2))
      }

    def wrapUnion[A, B](a : A => Elem, b : B => Elem)(v : union[A, B]) =
      v match {
        case Left(l) =>
          <union val="in_l">{a(l)}</union>
        case Right(r) =>
          <union val="in_r">{b(r)}</union>
      }
    def unwrapUnion[A, B](a : Elem => A, b : Elem => B)(e : Elem) : Either[A, B] =
      (_attr(e, "val"), _elch(e)) match {
        case (Some("in_l"), Seq(c : Elem)) =>
          Left(a(c))
        case (Some("in_r"), Seq(c : Elem)) =>
          Right(b(c))
      }

    def unwrapStatus(e : Elem) : status = e.child match {
      case Seq(a : Elem, b : Elem, c : Elem, d : Elem) =>
        status(unwrapList(unwrapString)(a),
            unwrapOption(unwrapString)(b),
            unwrapList(unwrapString)(c),
            unwrapInt(d))
    }
  }
}