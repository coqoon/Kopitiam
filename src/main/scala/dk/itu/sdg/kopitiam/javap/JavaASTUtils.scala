/* (c) 2013 Hannes Mehnert */

package dk.itu.sdg.kopitiam.javap
import dk.itu.sdg.kopitiam._

object JavaASTUtils {

  import org.eclipse.jdt.core.dom.{EmptyStatement, Statement}
  def printProofScript (statement : Statement) : Option[String] =
    statement match {
      case x : EmptyStatement =>
        statement.getProperty("dk.itu.sdg.kopitiam.contentExpr") match {
          case script : String if script.length > 0 =>
            val con =
              if (script.contains("invariant:")) {
                val i1 = script.indexOf(":")
                val i2 = script.indexOf("frame:")
                val i3 = if (i2 == -1) script.length - 3 else i2
                val i = script.substring(i1 + 1, i3).trim
                val f =
                  if (i2 == -1)
                    "<true>"
                  else
                    script.substring(i3 + 6, script.length - 3).trim
                "forward (" + i + ") (" + f + ")."
              } else script
            Some(con)
          case _ =>
            None
        }
      case x : Statement =>
        val fwd = Activator.getDefault.getPreferenceStore.getBoolean("implicit")
        if (fwd)
          Some("forward.")
        else
          None
    }

  import scala.collection.immutable.Stack
  import org.eclipse.jdt.core.dom.{MethodDeclaration, Statement, WhileStatement, IfStatement, Block}
  def traverseAST [A](method : MethodDeclaration, forward : Boolean, early : Boolean, callback : Statement => Option[A]) : List[A] = {
    var todo : Stack[Statement] = Stack[Statement]()
    todo = todo.push(method.getBody)
    var res : List[A] = List[A]()
    var cont : Boolean = true
    while (! todo.isEmpty && cont) {
      val st = todo.top
      todo = todo.pop
      st match {
        case x : Block =>
          val body = scala.collection.JavaConversions.asScalaBuffer(x.statements).map(_.asInstanceOf[Statement])
          if (forward)
            todo = todo.pushAll(body.reverse)
          else
            todo = todo.pushAll(body)
        case x : WhileStatement =>
          todo = todo.push(x.getBody)
        case x : IfStatement =>
          val el = x.getElseStatement
          if (el != null && forward)
            todo = todo.push(el)
          todo = todo.push(x.getThenStatement)
          if (el != null && ! forward)
            todo = todo.push(el)
        case x : Statement =>
          callback(x) match {
            case Some(x) =>
              res ::= x
              if (early)
                cont = false
            case None =>
          }
      }
    }
    res.reverse
  }


  import org.eclipse.jdt.core.dom.{AbstractTypeDeclaration, CompilationUnit, MethodDeclaration, TypeDeclaration}
  def traverseCU [A](c : CompilationUnit, callback : MethodDeclaration => A) : List[A] = {
    var res : List[A] = List[A]()
    var todo : Stack[AbstractTypeDeclaration] = Stack[AbstractTypeDeclaration]()
    todo = todo.pushAll(scala.collection.JavaConversions.asScalaBuffer(c.types).map(_.asInstanceOf[AbstractTypeDeclaration]))
    while (!todo.isEmpty) {
      val t = todo.top
      todo = todo.pop
      t match {
        case x : TypeDeclaration =>
          todo = todo.pushAll(x.getTypes)
          for (m <- x.getMethods)
            res ::= callback(m)
        case _ =>
      }
    }
    res.reverse
  }

  def countMethods (c : CompilationUnit) : Int = traverseCU(c, _ => 1).size
}