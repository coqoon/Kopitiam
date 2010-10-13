package dk.itu.sdg.javaparser

import scala.util.parsing.input._
import java.io.PrintWriter

trait JavaAST extends JavaParser {
  def parse(r: Reader[Char], out : PrintWriter) : ParseResult[Any] = {
    val p = phrase(compilationUnit)(new lexical.Scanner(r))
    p match {
      case Success(x @ ~(_,_), _) =>
        val ast = FinishAST.doit(x, out);
      case Failure(msg, remainder) => Console.println("Failure: "+msg+"\n"+"Remainder: \n"+remainder.pos.longString)
      case Error(msg, remainder) => Console.println("Error: "+msg+"\n"+"Remainder: \n"+remainder.pos.longString)
    }
    p
  }

  def pp(xs: Any, indent: Int): Unit = {
    def iprint(s: String) = {
      for (i <- 1 to indent) Console.print(" ")
      Console.print(s)
    }
    def iprintln(s: String) = iprint(s); Console.print("\n");

    xs match {
      case x1~x2 =>
        pp(x1, indent + 2)
        print(" ~ ")
        pp(x2, indent + 2)
      case xs @ List(_) =>
        for (x <- xs) yield pp(x, indent + 2)
      case xs : JClass =>
        iprintln("Class " + xs.id + " (" + xs.body.length + ")"); for (x <- xs.body) yield pp(x, indent + 2)
      case x @ _ =>
        iprintln(x.asInstanceOf[AnyRef].getClass().toString() + ": " + x.toString)
    }
  }
}

object Gensym {
  var freevars : List[String] = List[String]()
  var count : Int = 0
  def newsym () : String = {
    count += 1
    freevars ::= "tmp_" + count
    return "tmp_" + count
  }

  def getfree () : List[String] = {
    val tmp = freevars
    freevars = List[String]()
    tmp
  }
}


trait JavaStatements {
  trait JStatement
  case class JClassDefinition (id : String, superclass : String, interfaces : List[String], body : List[JStatement]) extends JStatement
  case class JInterfaceDefinition (id : String, interfaces : List[String], body : List[JStatement]) extends JStatement

  trait InnerStatement extends JStatement

  case class JFieldDefinition (id : String, jtype : String) extends InnerStatement
  case class JMethodDefinition (id : String, parameters : List[JArgument], body : List[JBodyStatement]) extends InnerStatement

  case class JArgument (id : String, jtype : String) extends InnerStatement

  trait JBodyStatement extends InnerStatement
  case class JBlock (body : List[JBodyStatement]) extends JBodyStatement
  case class JAssignment (left : String, right : JExpression) extends JBodyStatement
  case class JConditional (test : JExpression, consequent : JBodyStatement, alternative : JBodyStatement) extends JBodyStatement
  case class JFieldWrite (variable : String, field : String, value : JExpression) extends JBodyStatement
  case class JReturn (ret : JExpression) extends JBodyStatement
  case class JBinding (name : String, jtype : String, init : JExpression) extends JBodyStatement

  trait JExpression extends JBodyStatement
  case class JBinaryExpression (operation : String, left : JExpression, right : JExpression) extends JExpression
  case class JCall (fun : String, arguments : List[JExpression]) extends JExpression
  case class JNewExpression (jtype : String, arguments : List[JExpression]) extends JExpression
  case class JLiteral (value : String) extends JExpression
  case class JVariableAccess (variable : String) extends JExpression
  case class JFieldAccess (variable : String, field : String) extends JExpression
}


import scala.util.parsing.combinator.Parsers
object FinishAST extends JavaTerms with Parsers with JavaStatements with JavaToSimpleJava with CoqOutputter {
  private var fields : List[String] = List[String]()
  private var lvars : List[String] = List[String]()

  def unpackR (r : Any) : String = {
    r match {
      case x~y => unpackR(x) + unpackR(y)
      case QualId(xs) => "\"" + xs.map(unpackR).reduceLeft(_ + "." + _) + "\""
      case Some(x) => unpackR(x)
      case Expr(x) => unpackR(x)
      case PrimaryExpr(x) => unpackR(x)
      case PostFixExpression(x) => unpackR(x)
      case Name(x) => x
      case Num(x) => x
      case Lit(x) => unpackR(x)
      case x :: rt => unpackR(x) + unpackR(rt)
      case x : String => x
      case Primitive(x) => unpackR(x)
      case Key(x) => unpackR(x)
      case Nil => ""
      case None => ""
      case x => Console.println("unpackR dunno " + x + " class " + x.asInstanceOf[AnyRef].getClass.getName); ""
    }
  }

  def transformOLF (xs : Option[Any]) : List[String] = {
    xs match {
      case Some(xs : List[List[Any]]) => xs.flatten.map(unpackR)
      case None => List[String]()
    }
  }

  def transformOL [T](xs : Option[Any], x : List[T]) : List[T] = {
    xs match {
      case None => List[T]()
      case Some(x : List[Any]) =>
        transformList(x).map(x => if (x.isInstanceOf[T]) Some(x.asInstanceOf[T]) else None).flatten
      case Some(y) => Console.println("transformOL dunno " + y); List[T]()
    }
  }

  def transformList (xs : List[Any]) : List[JStatement] = {
    xs.map(transform).flatten
  }

  def transformBlock (cs : JStatement) : JBlock = {
    cs match {
      case (x : JBlock) => x
      case (xs : JBodyStatement) => JBlock(List(xs))
      case x => Console.println("transformBlock, dunno about " + x); null
    }
  }

  def transformExpression (x : Any) : JExpression = {
    val y = transformStatement(x)
    if (y.isInstanceOf[JExpression])
      y.asInstanceOf[JExpression]
    else {
      Console.println("transformExpression: wanted an JExpression, got " + y);
      null
    }
  }

  def transformStatement (x : Any) : JStatement = {
    //Console.println("transformstatement " + x)
    x match {
      case Expr(x) => transformStatement(x)
      case PostFixExpression(x) => transformStatement(x)
      case PrimaryExpr(x) => transformStatement(x)
      case ParExpr(x) => transformStatement(x)
      case Some(x) => transformStatement(x)
      case NewExpression(ntype, args) => JNewExpression(unpackR(ntype), args.map(transformExpression))
      case Conditional(test, consequence, alternative) =>
        val cs = transformBlock(transformStatement(consequence))
        val alt = alternative match {
          case None => JBlock(List[JBodyStatement]())
          case Some(x) => transformBlock(transformStatement(x))
        }
        JConditional(transformExpression(test), cs, alt)
      case BinaryExpr(op, le, ri) =>
        val oper = unpackR(op)
        if (oper == "=") { //assign -- but there are other assignments (+=, -=, ++,...)
          val lef = transformStatement(le) //will be either FieldAccess or VariableAccess
          lef match {
            case JFieldAccess(cnx, nam) => JFieldWrite(cnx, nam, transformExpression(ri))
            case JVariableAccess(nam) => JAssignment(nam, transformExpression(ri))
          }
        } else
          JBinaryExpression(oper, transformExpression(le), transformExpression(ri))
      case Call(fun, args) => JCall(unpackR(fun), args.map(transformExpression))
      case QualId(x) =>
        val v = unpackR(x)
        if (fields.contains(v))
          JFieldAccess("this", v)
        else if (lvars.contains(v))
          JVariableAccess(v)
        else {
          Console.println("got a qualid which isn't in lvars or fields " + v + "(fields: " + fields + ")(lvar: " + lvars + ")")
          JVariableAccess(v)
        }
      case Lit(x) => JLiteral(unpackR(x))
      case (x : PrimaryExpr)~(y : List[Any]) =>
        val rx = unpackR(x) //most likely a name (to a class/var)
        val rest = unpackR(y)
        if (rx == "this")
          if (fields.contains(rest.drop(1))) //get rid of first '.'
            JFieldAccess(rx, rest.drop(1))
          else
            Console.println("dunno what you mean with this (not a field): " + rx + " rest " + rest)
        else
          //now we have to lookup type of rx, and look whether rest is a field inside there
          Console.println("unsafe field access (maybe non-existant?): " + rx + " rest " + rest)
          JFieldAccess(rx, rest.drop(1))
      case y => Console.println("transformStatement dunno " + x); null
    }
  }

  def transform (x : Any) : Option[JStatement] = {
    //Console.println("transform " + x)
    x match {
      case Modifier(_) => None
      case None => None
      case Some(x) => transform(x)
      case Block(xs) =>
        val res = walk(xs)
        if (res.forall(_.isInstanceOf[JBodyStatement]))
          Some(JBlock(res.map(_.asInstanceOf[JBodyStatement])))
        else {
          Console.println("transform: not valid block elements: " + res)
          None
        }
      case LocalVar(x) => x match {
        case (mod~jtype)~(decls : List[Any]) =>
          val vars = decls.map(_ match {
            case (name : Name)~b~Some(y) =>
              val init = transformExpression(y)
              val realn = unpackR(name)
              lvars ::= realn
              Some(JBinding(realn, unpackR(jtype), init))
            case (name : Name)~b~None =>
              val realn = unpackR(name)
              lvars ::= realn
              Some(JBinding(realn, unpackR(jtype), null))
            case x => Console.println("dunno about decl " + x); None
          })
          assert(vars.length == 1)
          vars(0)
        case y => Console.println("dunno about lvar " + y); None
      }
      case FormalVariable(mods, jtype, name) => Some(JArgument(unpackR(name), unpackR(jtype)))
      case FieldDeclaration(id, jtype, rest) =>
        val name = unpackR(id)
        fields ::= name
        Some(JFieldDefinition(name, unpackR(jtype)))
      case MethodDeclaration(id, jtype, parameters, throws, body) =>
        lvars = List[String]()
        val args = transformOL(parameters, List[JArgument]())
        lvars ++= args.map(_.id)
        Console.println("walking over body " + body)
        val realbody = walk(body)
        Console.println("walked over body " + realbody)
        val realrealbody : List[JBodyStatement] =
          if (realbody.forall(_.isInstanceOf[JBodyStatement]))
            realbody.map(_.asInstanceOf[JBodyStatement])
          else {
            Console.println("cannot convert method body " + realbody)
            List[JBodyStatement]()
          }
        Console.println("MethodDeclaration done, lvars " + lvars) 
        Some(JMethodDefinition(unpackR(id), args, realrealbody))
      case JInterface(id, jtype, interfaces, body) =>
        val is = transformOLF(interfaces)
        Some(JInterfaceDefinition(unpackR(id), is, walk(body)))
      case JClass(id, jtype, superclass, interfaces, body) =>
        fields = List[String]()
        val is = transformOLF(interfaces)
        val cs = unpackR(superclass)
        Some(JClassDefinition(unpackR(id), cs, is, walk(body)))
      case Expr(y) => Some(transformStatement(y))
      case (x : Conditional) => Some(transformStatement(x))
      case Return(x) => Some(JReturn(transformExpression(x)))
      case x => Console.println("transform: dunno about [" + x.asInstanceOf[AnyRef].getClass.toString + "] " + x); None
    }
  }

  def walk (statements : Any) : List[JStatement] = {
    statements match {
      case Nil => Console.println("hit nil"); List[JStatement]()
      case x ~ y => walk(x) ++ walk(y)
      case x :: xs => walk(x) ++ walk(xs)
      case x =>
        val res = transform(x)
        //Console.println("walk return value (for " + x + ") is " + res)
        res match { case None => List[JStatement](); case Some(x) => List(x) }
    }
  }

  def doit (a : Any, out : PrintWriter) = {
    //Console.println("walking over " + a)
    val x = walk(a)
    //Console.println("doit, walked " + x)
    val y = x.map(y => y match {
      case JClassDefinition(name, supers, interf, body) => 
        val nb = body.map(z => z match {
          case JMethodDefinition(name, params, body) =>
            //Console.println("body: " + body)
            val tb = body.foldRight(List[JBodyStatement]())(extractCalls)
            //Console.println("body translated: " + tb)
            JMethodDefinition(name, params, tb)
          case x => x
        })
        JClassDefinition(name, supers, interf, nb)
      case x => x
    })
    Console.println("outputting " + y)
    coqoutput(y, out)
  }
}

trait JavaToSimpleJava extends JavaStatements {

  def extractCalls (statement : JBodyStatement, acc : List[JBodyStatement]) : List[JBodyStatement] = {
    //Console.println("calling extract for " + statement)
    statement match {
      case JBlock(xs) => JBlock(xs.foldRight(List[JBodyStatement]())(extractCalls)) :: acc
      case JAssignment(x, r) =>
        val (args, ins) = extractHelper(List(r), List[JExpression](), List[JExpression]())
        assert(args.length == 1)
        ins ++ (JAssignment(x, args(0)) :: acc)
      case JBinaryExpression(op, l, r) =>
        //l must be simple!?
        val (args, ins) = extractHelper(List(r), List[JExpression](), List[JExpression]())
        assert(args.length == 1)
        ins ++ (JBinaryExpression(op, l, args(0)) :: acc)
      case JConditional(t, c, a) =>
        val (ta, ti) = extractHelper(List(t), List[JExpression](), List[JExpression]())
        assert(ta.length == 1)
        val tst = ta(0)
        val con = extractCalls(c, List[JBodyStatement]())
        val con2 = if (con.length == 1)
                     con(0).asInstanceOf[JBodyStatement]
                   else
                     JBlock(con)
        val r = extractCalls(a, List[JBodyStatement]())
        val alt =
          if (r.length == 1)
            r(0).asInstanceOf[JBodyStatement]
          else
            JBlock(r)
        JConditional(tst, con2, alt) :: ti ++ acc
      case JCall(name, args) => 
        val (ars, ins) = extractHelper(args, List[JExpression](), List[JExpression]())
        //Console.println("results from extracthelper: " + ars + " ins " + ins)
        JCall(name, ars) :: ins ++ acc
      case JNewExpression(name, arguments) =>
        val (ars, ins) = extractHelper(arguments, List[JExpression](), List[JExpression]())
        JNewExpression(name, ars) :: ins ++ acc
      case x => x :: acc
    }
  }

  def extractHelper (xs : List[JExpression], acca : List[JExpression], acci : List[JBodyStatement]) : (List[JExpression], List[JBodyStatement]) = {
    //Console.println("extracthelper called with " + xs + " acca " + acca + " acci " + acci)
    xs match {
      case Nil => (acca.reverse, acci)
      case JBinaryExpression(op, l, r) :: xs =>
        val (ri, rii) = extractHelper(List(r), List[JExpression](), acci)
        val (le, lei) = extractHelper(List(l), List[JExpression](), rii)
        //Console.println("HELP le " + le + " ri " + ri + " is " + lei)
        assert(le.length == 1 && ri.length == 1)
        extractHelper(xs, JBinaryExpression(op, le(0), ri(0)) :: acca, lei)
      case JCall(name, args) :: rt =>
        val (as, ins) = extractHelper(args, List[JExpression](), List[JExpression]())
        val t = Gensym.newsym
        extractHelper(rt, JVariableAccess(t) :: acca, JAssignment(t, JCall(name, as)) :: ins ++ acci)
      case JNewExpression(name, args) :: rt =>
        val (as, ins) = extractHelper(args, List[JExpression](), List[JExpression]())
        val t = Gensym.newsym
        extractHelper(rt, JVariableAccess(t) :: acca, JAssignment(t, JNewExpression(name, as)) :: ins ++ acci)
/*      case Conditional(test, c, Some(a)) :: rt =>
        val t = gensym
        val (ta, ti) = extractHelper(List(test.e), List[AnyExpr](), List[AnyExpr]())
        val (ca, ci) = extractHelper(List(c), List[AnyExpr](), List[AnyExpr]())
        val (aa, ai) = extractHelper(List(a), List[AnyExpr](), List[AnyExpr]())
        assert(ca.length == aa.length == 0)
        extractHelper(rt, t :: acca, ti ++ Conditional(ParExpr(ta(0)),
                                                       ci :: Assignment(t, last),
                                                       ai :: Assignment(t, last))) */
      case x :: rt => extractHelper(rt, x :: acca, acci)
    }
  }
}

trait CoqOutputter extends JavaToSimpleJava with JavaStatements {
  import scala.collection.mutable.HashMap
  var symboltable : HashMap[String,String] = new HashMap[String,String]()

  var classes : List[Pair[String,String]] = List[Pair[String,String]]()
  var interfaces : List[Pair[String,String]] = List[Pair[String,String]]()

  def getArgs (x : List[InnerStatement]) : List[String] = {
    x.flatMap {
      case JArgument(id, jtype) =>
        symboltable += id -> jtype
        Some(id)
      case (x : JBinaryExpression) =>
        Some(getExpr(x))
      case _ => None
    }
  }

  def interfaceMethods (body : List[JStatement]) : List[Pair[String,String]] = {
    body.flatMap {
      case JMethodDefinition(name, params, body) =>
        val ps = getArgs(params)
        val f = "(" + printArgList(ps) + ")"
        Some(("\"" + name + "\"", f))
    }
  }

  def translateOp (x : String) : String = {
    if (x == ">") "egt"
    else if (x == "-") "eminus"
    else if (x == "*") "etimes"
    else if (x == "+") "eplus"
    else if (x == "=") "cassign"
    else { Console.println("translateOp dunno Key " + x); "" }
  }

  def getExpr (something : JBodyStatement) : String = {
    //Console.println("getexpr called with " + something + " class " + something.asInstanceOf[AnyRef].getClass.getName)
    something match {
      case JAssignment(name, value : JCall) =>
        val funname = value.fun
        val args = value.arguments
        val callpref = ""
        val p = if (callpref.length == 0)
                  "this"
                else
                  "not this"
        val mname = funname
        val argstring = args.map(getExpr).foldRight("nil")(_ + " :: " + _)
        val typ = symboltable(p)
        "(ccall \"" + name + "\" \"" + p + "\" " + mname + " (" + argstring + ") " + "(TClass \"" + typ + "\")" + ")"
      case JAssignment(name, value : JNewExpression) =>
        val t = value.jtype
        val a = value.arguments.map(getExpr).foldRight("nil")(_ + " :: " + _)
        symboltable += name -> t
        "(calloc " + name + " " + t + " " + a + ")"
      case JAssignment(name, value) => "(cassign \"" + name + "\" " + getExpr(value) + ")"
      case JBinaryExpression(op, l, r) =>
        "(" + translateOp(op) + " " + getExpr(l) + " " + getExpr(r) + ")"
      case JLiteral(x) => x
      case JVariableAccess(x) => "\"" + x + "\""
      case y => Console.println("getExpr: no handler for " + y); ""
    }
  }

  def optPrintBody (b : Option[List[String]]) : String = {
    b match {
      case None => ""
      case Some(x) => printBody(x)
    }
  }

  def printBody (bs : List[String]) : String = {
    bs match {
      case Nil => ""
      case x :: Nil => x
      case x :: y => "(cseq " + x + " " + printBody(y) + ")"
    }
  }

  private var freevars : List[String] = List[String]()
  private var ret : String = "myreturnvaluedummy"

  def getBS (xs : JBodyStatement) : Option[List[String]] = {
    xs match {
      case JConditional(test, consequence, alternative) =>
        val te = getExpr(test)
        val tr = optPrintBody(getBS(consequence))
        val fa = optPrintBody(getBS(alternative))
        Some(List("cif " + te + "\n    " + tr + "\n    " + fa))
      case JBlock(xs) => Some(xs.map(getBS).flatten.flatten)
      case JBinding(x, typ, init) => freevars ::= x; None
      case JReturn(JVariableAccess(r)) => ret = r; None
      case (x : JBodyStatement) => Some(List(getExpr(x)))
      case y => Console.println("no handler for getBS " + y); None
    }
  }

  def getBody (xs : List[JStatement], myname : String) : (List[String], String) = {
    ret = "myreturnvaluedummy"
    freevars = List[String]()
    //Console.println("getbody, flattening " + xs)
    val body = xs.flatMap {
      case (x : JBodyStatement) => getBS(x)
    }
    //Console.println("getbody, flattened " + body)
    outp.println("Definition " + myname + " :=")
    val b = printBody(body.flatten)
    outp.println(b)
    outp.println(".")
    (freevars ++ Gensym.getfree, "var_expr \"" + ret + "\"")
  }

  def classMethods (body : List[JStatement]) : List[Pair[String,String]] = {
    body.flatMap {
      case JMethodDefinition(name, params, body) =>
        val bodyref = name + "_body"
        val args = getArgs(params)
        val (local, returnvar) = getBody(body, bodyref)
        outp.println("Definition " + name + "M :=")
        outp.println("  Build_Method (" + printArgList(args) + ") (" + printArgList(local) + ") " + bodyref + " (" + returnvar + ").")
        Some(("\"" + name + "\"", name + "M"))
      case _ => None
    }
  }

  def getFields (xs : List[JStatement]) : List[String] = {
    xs.flatMap {
      case JFieldDefinition (name, typ) => Some(name)
      case _ => None
    }
  }

  private var outp : PrintWriter = null

  def coqoutput (xs : List[JStatement], out : PrintWriter) : Unit = {
    outp = out
    xs.foreach(x => x match {
      case JInterfaceDefinition(id, inters, body) =>
        //Console.println("interfaces are " + inters)
        interfaces ::= ("\"" + id + "\"", id)
        outp.println("Definition " + id + " :=")
        val methods = interfaceMethods(body)
        outp.println("  Build_Inter " + printFiniteSet(inters) + " " + printFiniteMap(methods) + ".")
      case JClassDefinition(id, supers, inters, body) =>
        classes ::= ("\"" + id + "\"", id)
        symboltable = new HashMap[String, String]()
        symboltable += "this" -> id
        val fields = getFields(body)
        val methods = classMethods(body)
        outp.println("Definition " + id + " :=")
        outp.println("  Build_Class " + printFiniteSet(inters))
        outp.println("              " + printFiniteSet(fields))
        outp.println("              " + printFiniteMap(methods) + ".")
    })
    val cs = printFiniteMap(classes)
    val is = printFiniteMap(interfaces)
    outp.println("Definition P :=")
    outp.println("  Build_Program " + cs)
    outp.println("                " + is + ".")
  }

  def printArgList (l : List[String]) : String = {
    l.foldRight("nil")("\"" + _ + "\" :: " + _)
  }

  def printFiniteSet (l : List[String]) : String = {
    l.foldRight("(SS.empty)")("(SS.add \"" + _ + "\" " + _ + ")")
  }

  def printFiniteMap (map : List[Pair[String, String]]) : String = {
    map match {
      case Nil => "(SM.empty _)"
      case (k,v) :: b => "(SM.add " + k + " " + v + " " + printFiniteMap(b) + ")"
    }
  }

}
