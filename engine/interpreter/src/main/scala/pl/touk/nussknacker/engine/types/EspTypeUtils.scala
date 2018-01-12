package pl.touk.nussknacker.engine.types

import java.lang.reflect._

import cats.Eval
import cats.data.StateT
import org.apache.commons.lang3.ClassUtils
import pl.touk.nussknacker.engine.api.{Documentation, ParamName}
import pl.touk.nussknacker.engine.api.process.ClassExtractionSettings
import pl.touk.nussknacker.engine.api.typed.ClazzRef
import pl.touk.nussknacker.engine.definition.TypeInfos.{ClazzDefinition, MethodInfo, Parameter}
import pl.touk.nussknacker.engine.util.ThreadUtils
import sun.reflect.generics.reflectiveObjects.ParameterizedTypeImpl

import scala.concurrent.Future

object EspTypeUtils {

  private object ScalaCaseClassStub {
    case class DumpCaseClass()
    object DumpCaseClass
  }
  private val blackilistedMethods: Set[String] = {
    (methodNames(classOf[ScalaCaseClassStub.DumpCaseClass]) ++
      methodNames(ScalaCaseClassStub.DumpCaseClass.getClass)).toSet
  }

  private val baseClazzPackagePrefix = Set("java", "scala")

  private val blacklistedClazzPackagePrefix = Set(
    "scala.collection", "scala.Function", "scala.xml",
    "javax.xml", "java.util",
    "cats", "argonaut", "dispatch",
    "org.apache.flink.api.common.typeinfo.TypeInformation"
  )

  private val primitiveTypesToBoxed : Map[Class[_], Class[_]] = Map(
    Void.TYPE -> classOf[Void],
    java.lang.Boolean.TYPE -> classOf[java.lang.Boolean],
    java.lang.Integer.TYPE -> classOf[java.lang.Integer],
    java.lang.Long.TYPE -> classOf[java.lang.Long],
    java.lang.Float.TYPE -> classOf[java.lang.Float],
    java.lang.Double.TYPE -> classOf[java.lang.Double],
    java.lang.Byte.TYPE -> classOf[java.lang.Byte],
    java.lang.Short.TYPE -> classOf[java.lang.Short],
    java.lang.Character.TYPE -> classOf[java.lang.Character]
  )

  //they can always appear...
  //TODO: what else should be here?
  private val mandatoryClasses = Set(
    classOf[java.util.List[_]],
    classOf[java.util.Map[_, _]],
    classOf[java.math.BigDecimal],
    classOf[Number],
    classOf[String]
  ) ++ primitiveTypesToBoxed.keys ++ primitiveTypesToBoxed.values

  private val boxedToPrimitives = primitiveTypesToBoxed.map(_.swap)

  private val primitiveTypesSimpleNames = primitiveTypesToBoxed.keys.map(_.getName).toSet

  private def methodNames(clazz: Class[_]): List[String] = {
    clazz.getMethods.map(_.getName).toList
  }

  def clazzAndItsChildrenDefinition(clazzes: Iterable[Class[_]])
                                   (implicit settings: ClassExtractionSettings): List[ClazzDefinition] = {
    (clazzes ++ mandatoryClasses).flatMap(clazzAndItsChildrenDefinition).toList.distinct
  }

  private def clazzAndItsChildrenDefinition(clazz: Class[_])
                                   (implicit settings: ClassExtractionSettings): List[ClazzDefinition] = {
    val result = if (clazz.isPrimitive || baseClazzPackagePrefix.exists(clazz.getName.startsWith)) {
      List(clazzDefinition(clazz))
    } else {
      val mainClazzDefinition = clazzDefinition(clazz)
      val recursiveClazzes = mainClazzDefinition.methods.values.toList
        .filter(m => !primitiveTypesSimpleNames.contains(m.refClazzName) && m.refClazzName != clazz.getName)
        .filter(m => !blacklistedClazzPackagePrefix.exists(m.refClazzName.startsWith))
        .filter(m => !m.refClazzName.startsWith("["))
        .map(_.refClazzName).distinct
        .flatMap(m => clazzAndItsChildrenDefinition(ThreadUtils.loadUsingContextLoader(m)))
      mainClazzDefinition :: recursiveClazzes
    }
    result.distinct
  }

  private def clazzDefinition(clazz: Class[_])
                             (implicit settings: ClassExtractionSettings): ClazzDefinition =
    ClazzDefinition(ClazzRef(clazz), getPublicMethodAndFields(clazz))

  private def getPublicMethodAndFields(clazz: Class[_])
                                      (implicit settings: ClassExtractionSettings): Map[String, MethodInfo] = {
    val methods = publicMethods(clazz)
    val fields = publicFields(clazz)
    methods ++ fields
  }

  private def publicMethods(clazz: Class[_])
                           (implicit settings: ClassExtractionSettings): Map[String, MethodInfo] = {
    val interestingMethods = clazz.getMethods
        .filterNot(m => Modifier.isStatic(m.getModifiers))
        .filterNot(settings.isBlacklisted)
        .filter(m =>
          !blackilistedMethods.contains(m.getName) && !m.getName.contains("$")
        )
    interestingMethods.map { method =>
      method.getName -> MethodInfo(getParamNameParameters(method), ClazzRef(getReturnClassForMethod(method)), getNussknackerDocs(method))
    }.toMap
  }

  private def publicFields(clazz: Class[_])
                          (implicit settings: ClassExtractionSettings): Map[String, MethodInfo] = {
    val interestingFields = clazz.getFields
      .filterNot(f => Modifier.isStatic(f.getModifiers))
      .filterNot(settings.isBlacklisted)
      .filter(m =>
        !m.getName.contains("$")
      )
    interestingFields.map { field =>
      field.getName -> MethodInfo(List.empty, ClazzRef(getReturnClassForField(field)), getNussknackerDocs(field))
    }.toMap
  }

  def findParameterByParameterName(method: Method, paramName: String) =
    method.getParameters.find { p =>
      Option(p.getAnnotation(classOf[ParamName])).exists(_.value() == paramName)
    }

  def extractParameterType(p: java.lang.reflect.Parameter, classesToExtractGenericFrom: Class[_]*) =
    if (classesToExtractGenericFrom.contains(p.getType)) {
      val parameterizedType = p.getParameterizedType.asInstanceOf[ParameterizedType]
      parameterizedType.getActualTypeArguments.apply(0) match {
        case a:Class[_] => a
        case b:ParameterizedType => b.getRawType.asInstanceOf[Class[_]]
      }
    } else {
      p.getType
    }

  def getCompanionObject[T](klazz: Class[T]) : T= {
    klazz.getField("MODULE$").get(null).asInstanceOf[T]
  }

  def getGenericType(genericReturnType: Type): Option[Class[_]] = {
    val hasGenericReturnType = genericReturnType.isInstanceOf[ParameterizedTypeImpl]
    if (hasGenericReturnType) inferGenericMonadType(genericReturnType)
    else None
  }

  private def getReturnClassForMethod(method: Method): Class[_] = {
    getGenericType(method.getGenericReturnType).getOrElse(method.getReturnType)
  }

  private def getParamNameParameters(method: Method): List[Parameter] = {
    method.getParameters.toList.collect { case param if param.getAnnotation(classOf[ParamName]) != null =>
      val paramName = param.getAnnotation(classOf[ParamName]).value()
      Parameter(paramName, ClazzRef(param.getType))
    }
  }

  private def getNussknackerDocs(accessibleObject: AccessibleObject): Option[String] = {
    Option(accessibleObject.getAnnotation(classOf[Documentation])).map(_.description())
  }

  private def getReturnClassForField(field: Field): Class[_] = {
    getGenericType(field.getGenericType).getOrElse(field.getType)
  }

  //TODO this is not correct for primitives and complicated hierarchies, but should work in most cases
  //http://docs.oracle.com/javase/8/docs/api/java/lang/reflect/ParameterizedType.html#getActualTypeArguments--
  private def inferGenericMonadType(genericReturnType: Type): Option[Class[_]] = {
    val genericMethodType = genericReturnType.asInstanceOf[ParameterizedTypeImpl]
    if (classOf[StateT[Eval, _, _]].isAssignableFrom(genericMethodType.getRawType)) {
      val returnType = genericMethodType.getActualTypeArguments.apply(2) // bo StateT[Eval, S, A]
      extractClass(returnType)
    }
    else if (classOf[Future[_]].isAssignableFrom(genericMethodType.getRawType)) {
      val futureGenericType = genericMethodType.getActualTypeArguments.apply(0)
      extractClass(futureGenericType)
    }
    else if (classOf[Option[_]].isAssignableFrom(genericMethodType.getRawType)) {
      val optionGenericType = genericMethodType.getActualTypeArguments.apply(0)
      extractClass(optionGenericType)
    }
    else None
  }

  private def extractClass(futureGenericType: Type): Option[Class[_]] = {
    futureGenericType match {
      case t: Class[_] => Some(t)
      case t: ParameterizedTypeImpl => Some(t.getRawType)
      case t => None
    }
  }

  private def tryToUnBox(clazz : Class[_]) = boxedToPrimitives.getOrElse(clazz, clazz)

  //TODO: what is *really* needed here?? is it performant enough??
  def signatureElementMatches(signatureType: Class[_], passedValueClass: Class[_]) : Boolean = {
    ClassUtils.isAssignable(passedValueClass, signatureType, true) ||
      ClassUtils.isAssignable(tryToUnBox(passedValueClass), tryToUnBox(signatureType), true)
  }

}