package scala.pickling

import scala.language.experimental.macros
import scala.language.reflectiveCalls

import HasCompat._

package object internal {

  import scala.reflect.runtime.{universe => ru}
  import ru._
  import compat._

  /* Global reflection lock.
   * It is used to avoid data races that typically lead to runtime exceptions
   * when using (Scala) runtime reflection on Scala 2.10.x.
   *
   * Note: visibility must be public, so that the lock can be accessed from
   *       macro-generated code.
   */
  val GRL = new java.util.concurrent.locks.ReentrantLock

  // TOGGLE DEBUGGING
  private val debugEnabled: Boolean = System.getProperty("pickling.debug", "false").toBoolean
  private[pickling] def debug(output: => String) = if (debugEnabled) println(output)


  // ----- internal extension methods for symbols and types -----
  private[pickling] implicit class RichSymbol(sym: scala.reflect.api.Universe#Symbol) {
    def isEffectivelyFinal = sym.asInstanceOf[scala.reflect.internal.Symbols#Symbol].isEffectivelyFinal
    def isEffectivelyPrimitive = throw new Exception("use Type.isEffectivelyPrimitive instead")
    def isNotNullable = sym.isClass && (sym.asClass.isPrimitive || sym.asClass.isDerivedValueClass)
    def isNullable = sym.isClass && !isNotNullable
  }

  private[pickling] implicit class RichType(tpe: scala.reflect.api.Universe#Type) {
    def isEffectivelyFinal = tpe.typeSymbol.isEffectivelyFinal
    // TODO: doesn't work...
    // def isEffectivelyPrimitive: Boolean = {
    //   tpe.typeSymbol.isPrimitive || {
    //     val args = tpe.asInstanceOf[scala.reflect.internal.SymbolTable#Type].typeArguments
    //     def isArrayOfSomething = tpe.toString.startsWith("scala.Array[") || tpe.toString.startsWith("Array[")
    //     def isParameterizedByPrimitive = args.nonEmpty && args.head.isEffectivelyPrimitive
    //     isArrayOfSomething && isParameterizedByPrimitive
    //   }
    // }
    def isNotNullable = tpe.typeSymbol.isNotNullable
    def isNullable = tpe.typeSymbol.isNullable
  }

  var cachedMirror: ru.Mirror = null
  def currentMirror: ru.Mirror = macro Compat.CurrentMirrorMacro_impl

  private[pickling] def typeToString(tpe: Type): String = tpe.key

  private val typeFromStringCache = scala.collection.concurrent.TrieMap[String, Type]()
  private[pickling] def typeFromString(mirror: Mirror, stpe: String): Type = {
    // TODO: find out why typeFromString is called repeatedly for scala.Predef.String (at least in the evactor1 bench)
    if (typeFromStringCache.contains(stpe)) typeFromStringCache(stpe)
    else {
      val result =
        AppliedType.parse(stpe) match {
          case (AppliedType(typename, appliedTypeArgs), _) =>
            def errorMsg = s"""error: cannot find class or module with type name '$typename'
                              |full type string: '$stpe'""".stripMargin

            val sym = try {
              if (typename.endsWith(".type")) mirror.staticModule(typename.stripSuffix(".type")).moduleClass
              else mirror.staticClass(typename)
            } catch {
              case _: ScalaReflectionException =>
                sys.error(errorMsg)
              case _: scala.reflect.internal.MissingRequirementError =>
                sys.error(errorMsg)
            }
            val tycon = sym.asType.toTypeConstructor
            appliedType(tycon, appliedTypeArgs.map(starg => typeFromString(mirror, starg.toString)))
          case _ =>
            sys.error(s"fatal: cannot unpickle $stpe")
        }
      typeFromStringCache(stpe) = result
      result
    }
  }

  // FIXME: duplication wrt Tools, but I don't really fancy abstracting away this path-dependent madness
  private[pickling] implicit class RichTypeFIXME(tpe: Type) {
    import definitions._
    def key: String = {
      tpe.normalize match {
        case ExistentialType(tparams, TypeRef(pre, sym, targs))
        if targs.nonEmpty && targs.forall(targ => tparams.contains(targ.typeSymbol)) =>
          TypeRef(pre, sym, Nil).key
        case TypeRef(pre, sym, targs) if pre.typeSymbol.isModuleClass =>
          sym.fullName +
          (if (sym.isModuleClass) ".type" else "") +
          (if (targs.isEmpty) "" else targs.map(_.key).mkString("[", ",", "]"))
        case _ =>
          tpe.toString
      }
    }
    def isEffectivelyPrimitive: Boolean = tpe match {
      case TypeRef(_, sym: ClassSymbol, _) if sym.isPrimitive => true
      case TypeRef(_, sym, eltpe :: Nil) if sym == ArrayClass && eltpe.isEffectivelyPrimitive => true
      case _ => false
    }
  }


  // ----- utilities for managing object identity -----
  private val pickleesTL = new ThreadLocal[ReactMap] {
    override def initialValue() = new ReactMap
  }
  private val nextPickleeTL = new ThreadLocal[Int] {
    override def initialValue() = 0
  }

  def lookupPicklee(picklee: Any) = {
    var nextPicklee = nextPickleeTL.get()
    val picklees = pickleesTL.get()

    val index = nextPicklee
    val result = picklees.insertIfNotThere(picklee.asInstanceOf[AnyRef], index)
    // println(s"lookupPicklee($picklee) = $result")
    if (result == -1) {
      nextPicklee += 1
      nextPickleeTL.set(nextPicklee)
    }
    pickleesTL.set(picklees)
    result
  }
  def registerPicklee(picklee: Any) = {
    var nextPicklee = nextPickleeTL.get()
    val picklees = pickleesTL.get()

    val index = nextPicklee
    picklees.insert(picklee.asInstanceOf[AnyRef], index)
    // println(s"registerPicklee($picklee, $index)")
    nextPicklee += 1
    nextPickleeTL.set(nextPicklee)
    pickleesTL.set(picklees)
    index
  }
  def clearPicklees() = {
    var nextPicklee = nextPickleeTL.get()
    val picklees = pickleesTL.get()

    picklees.clear()
    nextPicklee = 0

    nextPickleeTL.set(nextPicklee)
    pickleesTL.set(picklees)
  }

  private val unpickleesTL = new ThreadLocal[Array[Any]] {
    override def initialValue() = new Array[Any](65536)
  }
  private val nextUnpickleeTL = new ThreadLocal[Int] {
    override def initialValue() = 0
  }

  def lookupUnpicklee(index: Int): Any = {
    val nextUnpicklee = nextUnpickleeTL.get()
    val unpicklees = unpickleesTL.get()

    // println(s"lookupUnpicklee($index)")
    if (index >= nextUnpicklee) throw new Error(s"fatal error: invalid index $index in unpicklee cache of length $nextUnpicklee")
    val result = unpicklees(index)
    if (result == null) throw new Error(s"fatal error: unpicklee cache is corrupted at $index")
    result
  }
  def preregisterUnpicklee() = {
    var nextUnpicklee = nextUnpickleeTL.get()
    val index = nextUnpicklee

    val unpicklees = unpickleesTL.get()

    val len = unpicklees.length
    val target = if (index == len) {
      val newArr = Array.ofDim[Any](len * 2)
      System.arraycopy(unpicklees, 0, newArr, 0, len)
      unpickleesTL.set(newArr)
      newArr
    } else
      unpicklees
    target(index) = null

    // println(s"preregisterUnpicklee() at $index")
    nextUnpicklee += 1
    nextUnpickleeTL.set(nextUnpicklee)
    index
  }
  def registerUnpicklee(unpicklee: Any, index: Int) = {
    val unpicklees = unpickleesTL.get()

    // println(s"registerUnpicklee($unpicklee, $index)")
    unpicklees(index) = unpicklee
    unpickleesTL.set(unpicklees)
  }
  def clearUnpicklees() = {
    var nextUnpicklee = nextUnpickleeTL.get()
    val unpicklees = unpickleesTL.get()

    var i = 0
    while (i < nextUnpicklee) {
      unpicklees(i) = null
      i += 1
    }
    nextUnpicklee = 0
    nextUnpickleeTL.set(nextUnpicklee)
    unpickleesTL.set(unpicklees)
  }
}
