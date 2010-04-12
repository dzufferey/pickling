/* NEST (New Scala Test)
 * Copyright 2007-2010 LAMP/EPFL
 * @author  Paul Phillips
 */

package scala.tools
package cmd

/** A sample command specification for illustrative purposes.
 *  First take advantage of the meta-options:
 *
 *    // this command creates an executable runner script "demo"
 *    % scala scala.tools.cmd.Demo --generate-runner demo
 *
 *    // this one creates and sources a completion file - note backticks
 *    % `./demo --bash`
 *
 *    // and now you have a runner with working completion
 *    % ./demo --<tab>
 *       --action           --defint           --int
 *       --bash             --defstr           --str
 *       --defenv           --generate-runner  --unary
 *
 *  The normal option configuration is plausibly self-explanatory.
 */
trait DemoSpec extends Spec with Meta.StdOpts with Interpolation {
  lazy val referenceSpec  = DemoSpec
  lazy val programInfo    = Spec.Names("demo", "scala.tools.cmd.Demo")

  help("""Usage: demo [<options>]""")
  heading("Unary options:")

  val optIsUnary      = "unary"         / "a unary option"              --?  ;
  ("action" / "a body which may be run") --> println("Hello, I am the --action body.")

  heading("Binary options:")
  val optopt          = "str"       / "an optional String"        --|
  val optoptInt       = ("int"      / "an optional Int") .        --^[Int]
  val optEnv          = "defenv"    / "an optional String"        defaultToEnv  "PATH"
  val optDefault      = "defstr"    / "an optional String"        defaultTo     "default"
  val optDefaultInt   = "defint"    / "an optional Int"           defaultTo     -1
  val optExpand       = "alias"     / "an option which expands"   expandTo      ("--int", "15")
}

object DemoSpec extends DemoSpec with Property {
  lazy val propMapper = new PropertyMapper(DemoSpec)

  type ThisCommandLine = SpecCommandLine
  def creator(args: List[String]) =
    new SpecCommandLine(args) {
      override def onlyKnownOptions     = true
      override def errorFn(msg: String) = { println("Error: " + msg) ; System.exit(0) }
    }
}

class Demo(args: List[String]) extends {
  val parsed = DemoSpec(args: _*)
} with DemoSpec with Instance {
  import java.lang.reflect._

  def helpMsg = DemoSpec.helpMsg
  def demoSpecMethods = this.getClass.getMethods.toList
  private def isDemo(m: Method) = (m.getName startsWith "opt") && !(m.getName contains "$") && (m.getParameterTypes.isEmpty)

  def demoString(ms: List[Method]) = {
    val longest   = ms map (_.getName.length) max
    val formatStr = "    %-" + longest + "s: %s"
    val xs        = ms map (m => formatStr.format(m.getName, m.invoke(this)))

    xs mkString ("Demo(\n  ", "\n  ", "\n)\n")
  }

  override def toString = demoString(demoSpecMethods filter isDemo)
}

object Demo {
  def main(args: Array[String]): Unit = {
    val runner = new Demo(args.toList)

    if (args.isEmpty)
      println(runner.helpMsg)

    println(runner)
  }
}