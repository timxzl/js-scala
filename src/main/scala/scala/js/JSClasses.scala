package scala.js

import scala.virtualization.lms.common._

import javassist._
import javassist.expr._

import java.io.PrintWriter

trait JSClasses extends JSClassProxyBase {
  trait Factory[+T] {
    def apply(args: Rep[Any]*): Rep[T]
  }
  def register[T<:AnyRef:Manifest](outer: AnyRef): Factory[T]
}

trait JSClassesExp extends JSClasses with JSClassProxyExp {
  trait Constructor[+T]

  case class MethodTemplate(name: String, params: List[Sym[Any]], body: Exp[Any])
  case class ParentTemplate(constructor: Exp[Any], instance: Exp[Any])

  case class This[T:Manifest]() extends Exp[T]

  case class ClassTemplate[T:Manifest](parent: Option[ParentTemplate], methods: List[MethodTemplate]) extends Def[Constructor[T]]
  case class New[T:Manifest](constructor: Exp[Constructor[T]], args: List[Rep[Any]]) extends Def[T]

  override def register[T<:AnyRef:Manifest](outer: AnyRef) = {
    val constructor = registerInternal[T](outer)
    new Factory[T] {
      override def apply(args: Rep[Any]*) = create[T](constructor, args.toList)
    }
  }

  private def create[T<:AnyRef:Manifest](constructor: Exp[Constructor[T]], args: List[Rep[Any]]): Exp[T] =
    reflectEffect(New(constructor, args))

  private var registered : Map[String, Exp[Constructor[Any]]] = Map()
  private def registerInternal[T<:AnyRef:Manifest](outer: AnyRef) : Exp[Constructor[T]] = {
    val m = implicitly[Manifest[T]]
    val clazz = m.erasure
    val key = clazz.getName

    registered.get(key) match {
       case Some(constructor) => return constructor.asInstanceOf[Exp[Constructor[T]]]
       case None => ()
    }

    val cp = ClassPool.getDefault
    cp.insertClassPath(new ClassClassPath(clazz))
    val cc = cp.get(key)

    for (field <- cc.getDeclaredFields;
         if field.getName != "$outer") {
      try {
        cc.getDeclaredMethod(field.getName)
      } catch {
        case e: NotFoundException =>
          cc.addMethod(CtNewMethod.getter(
            field.getName, field))
      }
      try {
        cc.getDeclaredMethod(
          updateMethodFromField(field.getName),
          Array(field.getType))
      } catch {
        case e: NotFoundException =>
          cc.addMethod(CtNewMethod.setter(
            updateMethodFromField(field.getName), field))
      }
    }

    def isFieldAccess(method: String) = {
      try {
        cc.getField(method)
        true
      } catch {
        case e: NotFoundException => false
      }
    }
    def fieldAccess(field: String) =
      "$_ = $0." + field + "();"
    def fieldUpdate(field: String) =
      "$0." + updateMethodFromField(field) + "($1);"
    val exprEditor = new ExprEditor() {
      override def edit(f: expr.FieldAccess) {
        if (f.getClassName == key && f.getFieldName != "$outer") {
          f.replace((if (f.isReader) fieldAccess _ else fieldUpdate _) (f.getFieldName))
        }
      }
    }

    val ctConstructor = (cc.getDeclaredConstructors())(0)
    val ctConstructorMethod = ctConstructor.toMethod("$init$", cc)
    cc.addMethod(ctConstructorMethod)
    ctConstructorMethod.instrument(exprEditor)

    for (method <- cc.getDeclaredMethods)
      if (!method.getName.contains("$") && !isFieldAccess(method.getName))
        method.instrument(exprEditor)

    // val parents = traitClazz.getInterfaces.filter(_ != implicitly[Manifest[scala.ScalaObject]].erasure)
    // assert (parents.length < 2, "Only single inheritance is supported.")
    // val parentConstructor = if (parents.length == 0) None else Some(registerInternal[AnyRef](outer)(Manifest.classType(parents(0))))
    // val parent = parentConstructor.map(c => ParentTemplate(c, create[AnyRef](c)))
    val parent = None // TODO

    cc.setName(clazz.getName + "$bis")
    val bisClazz = cc.toClass()

    val jConstructor = (bisClazz.getDeclaredConstructors())(0)
    val jConstructorMethod = bisClazz.getDeclaredMethod("$init$", jConstructor.getParameterTypes: _*)
    val constructorTemplate = {
      val n = jConstructorMethod.getParameterTypes.length
      val params = (1 to (n-1)).toList.map(_ => fresh[Any])
      val args = (outer::params).toArray
      val self = repMasqueradeProxy(bisClazz, This[T](), outer, List("$init$"))
      MethodTemplate("$init$", params, reifyEffects(jConstructorMethod.invoke(self, args: _*).asInstanceOf[Exp[Any]]))
    }

    val methods = 
      for (method <- bisClazz.getDeclaredMethods.toList;
           if !method.getName.contains("$") && !isFieldAccess(method.getName))
      yield {
        val n = method.getParameterTypes.length
        val params = (1 to n).toList.map(_ => fresh[Any])
        val args = params.toArray
        val self = repMasqueradeProxy(bisClazz, This[T](), outer, List(method.getName))
        MethodTemplate(method.getName, params, reifyEffects(method.invoke(self, args: _*).asInstanceOf[Exp[Any]]))
      }
    
    val constructor = ClassTemplate[T](parent, constructorTemplate::methods) : Exp[Constructor[T]]
    registered = registered.updated(key, constructor)
    constructor
  }

  
  override def syms(e: Any): List[Sym[Any]] = e match {
    case MethodTemplate(_, params, body) => syms(body)
    case _ => super.syms(e)
  }

  override def boundSyms(e: Any): List[Sym[Any]] = e match {
    case MethodTemplate(_, params, body) => params.flatMap(syms) ::: effectSyms(body)
    case _ => super.boundSyms(e)
  }

  override def symsFreq(e: Any): List[(Sym[Any], Double)] = e match {
    case MethodTemplate(_, params, body) => freqHot(body)
    case _ => super.symsFreq(e)
  }
}

trait JSGenClasses extends JSGenBase with JSGenClassProxy {
  val IR: JSClassesExp
  import IR._

  override def emitNode(sym: Sym[Any], rhs: Def[Any])(implicit stream: PrintWriter) = rhs match {
    case ClassTemplate(parentTemplate, methodTemplates @ MethodTemplate(_, params, _)::_) =>
      stream.println("var " + quote(sym) + " = function" + params.map(quote).mkString("(", ",", ")") + "{")
      parentTemplate.foreach(pt => stream.println("this.$super$ = " + quote(pt.constructor) + ".prototype"))
      stream.println("this.$init$" + params.map(quote).mkString("(", ",", ")"))
      stream.println("}")
      parentTemplate.foreach(pt => stream.println(quote(sym) + ".prototype = " + quote(pt.instance)))
      for (MethodTemplate(name, params, body) <- methodTemplates) {
	stream.println(quote(sym) + ".prototype." + name + " = function" + params.map(quote).mkString("(", ",", ")") + " {")
	emitBlock(body)
	stream.println("return " + quote(getBlockResult(body)))
	stream.println("}")
      }
    case New(constructor, args) =>
      emitValDef(sym, "new " + quote(constructor) + args.map(quote).mkString("(", ", ", ")"))
    case _ => super.emitNode(sym, rhs)
  }

  override def quote(x: Exp[Any]) : String = x match {
    case This() => "this"
    case _ => super.quote(x)
  }
}