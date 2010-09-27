package dk.itu.sdg.javaparser

import scala.util.parsing.combinator.Parsers
import scala.util.parsing.input.StreamReader

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * Parser for an untyped lambda calculus
 *
 * Usage: scala examples.parsing.lambda.Main <file>
 *
 * (example files: see test/ *.kwi)
 *
 * @author Miles Sabin (adapted slightly by Adriaan Moors)
 */
object Main extends Application with JavaAST
{
  override def main(args: Array[String]) = {
    val in = StreamReader(new InputStreamReader(new FileInputStream(new File(args(0))), "ISO-8859-1"))
    val outfile = args(0) + ".v"
    val out = new PrintWriter(new File(outfile))
    parse(in, out)
    out.close
  }
}
