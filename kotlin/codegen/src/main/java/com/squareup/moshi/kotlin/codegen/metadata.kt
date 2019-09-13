/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.moshi.kotlin.codegen

import com.google.auto.common.AnnotationMirrors
import com.google.auto.common.MoreTypes
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.PUBLIC
import com.squareup.kotlinpoet.KModifier.VARARG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.WildcardTypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.asTypeVariableName
import com.squareup.kotlinpoet.tag
import com.squareup.moshi.Json
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.kotlin.codegen.api.DelegateKey
import com.squareup.moshi.kotlin.codegen.api.PropertyGenerator
import com.squareup.moshi.kotlin.codegen.api.TargetConstructor
import com.squareup.moshi.kotlin.codegen.api.TargetParameter
import com.squareup.moshi.kotlin.codegen.api.TargetProperty
import com.squareup.moshi.kotlin.codegen.api.TargetType
import com.squareup.moshi.kotlin.codegen.api.TypeResolver
import me.eugeniomarletti.kotlin.metadata.KotlinClassMetadata
import me.eugeniomarletti.kotlin.metadata.KotlinMetadata
import me.eugeniomarletti.kotlin.metadata.classKind
import me.eugeniomarletti.kotlin.metadata.declaresDefaultValue
import me.eugeniomarletti.kotlin.metadata.getPropertyOrNull
import me.eugeniomarletti.kotlin.metadata.isDataClass
import me.eugeniomarletti.kotlin.metadata.isInnerClass
import me.eugeniomarletti.kotlin.metadata.isPrimary
import me.eugeniomarletti.kotlin.metadata.jvm.getJvmConstructorSignature
import me.eugeniomarletti.kotlin.metadata.kotlinMetadata
import me.eugeniomarletti.kotlin.metadata.modality
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Class
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Modality.ABSTRACT
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Modality.SEALED
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Type
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.TypeParameter
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.TypeParameter.Variance
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Visibility
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Visibility.INTERNAL
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Visibility.LOCAL
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Visibility.PRIVATE
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Visibility.PRIVATE_TO_THIS
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.ProtoBuf.Visibility.PROTECTED
import me.eugeniomarletti.kotlin.metadata.shadow.metadata.deserialization.NameResolver
import me.eugeniomarletti.kotlin.metadata.shadow.util.capitalizeDecapitalize.decapitalizeAsciiOnly
import me.eugeniomarletti.kotlin.metadata.visibility
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target
import javax.annotation.processing.Messager
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.Modifier.DEFAULT
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.NATIVE
import javax.lang.model.element.Modifier.STATIC
import javax.lang.model.element.Modifier.SYNCHRONIZED
import javax.lang.model.element.Modifier.TRANSIENT
import javax.lang.model.element.Modifier.VOLATILE
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.TypeVariable
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import javax.tools.Diagnostic.Kind.ERROR
import kotlin.reflect.KClass

private fun TypeParameter.asTypeName(
    nameResolver: NameResolver,
    getTypeParameter: (index: Int) -> TypeParameter,
    resolveAliases: Boolean = false
): TypeVariableName {
  val possibleBounds = upperBoundList.map {
    it.asTypeName(nameResolver, getTypeParameter, resolveAliases)
  }
  return if (possibleBounds.isEmpty()) {
    TypeVariableName(
        name = nameResolver.getString(name),
        variance = variance.asKModifier())
  } else {
    TypeVariableName(
        name = nameResolver.getString(name),
        bounds = *possibleBounds.toTypedArray(),
        variance = variance.asKModifier())
  }
}

private fun TypeParameter.Variance.asKModifier(): KModifier? {
  return when (this) {
    Variance.IN -> KModifier.IN
    Variance.OUT -> KModifier.OUT
    Variance.INV -> null
  }
}

private fun Visibility?.asKModifier(): KModifier {
  return when (this) {
    INTERNAL -> KModifier.INTERNAL
    PRIVATE -> KModifier.PRIVATE
    PROTECTED -> KModifier.PROTECTED
    Visibility.PUBLIC -> KModifier.PUBLIC
    PRIVATE_TO_THIS -> KModifier.PRIVATE
    LOCAL -> KModifier.PRIVATE
    else -> PUBLIC
  }
}

/**
 * Returns the TypeName of this type as it would be seen in the source code, including nullability
 * and generic type parameters.
 *
 * @param [nameResolver] a [NameResolver] instance from the source proto
 * @param [getTypeParameter] a function that returns the type parameter for the given index. **Only
 *     called if [ProtoBuf.Type.hasTypeParameter] is true!**
 */
private fun Type.asTypeName(
    nameResolver: NameResolver,
    getTypeParameter: (index: Int) -> TypeParameter,
    useAbbreviatedType: Boolean = true
): TypeName {

  val argumentList = when {
    useAbbreviatedType && hasAbbreviatedType() -> abbreviatedType.argumentList
    else -> argumentList
  }

  if (hasFlexibleUpperBound()) {
    return WildcardTypeName.producerOf(
        flexibleUpperBound.asTypeName(nameResolver, getTypeParameter, useAbbreviatedType))
        .copy(nullable = nullable)
  } else if (hasOuterType()) {
    return WildcardTypeName.consumerOf(
        outerType.asTypeName(nameResolver, getTypeParameter, useAbbreviatedType))
        .copy(nullable = nullable)
  }

  val realType = when {
    hasTypeParameter() -> return getTypeParameter(typeParameter)
        .asTypeName(nameResolver, getTypeParameter, useAbbreviatedType)
        .copy(nullable = nullable)
    hasTypeParameterName() -> typeParameterName
    useAbbreviatedType && hasAbbreviatedType() -> abbreviatedType.typeAliasName
    else -> className
  }

  var typeName: TypeName =
      ClassName.bestGuess(nameResolver.getString(realType)
          .replace("/", "."))

  if (argumentList.isNotEmpty()) {
    val remappedArgs: Array<TypeName> = argumentList.map { argumentType ->
      val nullableProjection = if (argumentType.hasProjection()) {
        argumentType.projection
      } else null
      if (argumentType.hasType()) {
        argumentType.type.asTypeName(nameResolver, getTypeParameter, useAbbreviatedType)
            .let { argumentTypeName ->
              nullableProjection?.let { projection ->
                when (projection) {
                  Type.Argument.Projection.IN -> WildcardTypeName.consumerOf(argumentTypeName)
                  Type.Argument.Projection.OUT -> WildcardTypeName.producerOf(argumentTypeName)
                  Type.Argument.Projection.STAR -> STAR
                  Type.Argument.Projection.INV -> TODO("INV projection is unsupported")
                }
              } ?: argumentTypeName
            }
      } else {
        STAR
      }
    }.toTypedArray()
    typeName = (typeName as ClassName).parameterizedBy(*remappedArgs)
  }

  return typeName.copy(nullable = nullable)
}

internal fun primaryConstructor(metadata: KotlinClassMetadata,
    elements: Elements): TargetConstructor {
  val (nameResolver, classProto) = metadata.data

  // TODO allow custom constructor
  val proto = classProto.constructorList
      .single { it.isPrimary }
  val constructorJvmSignature = proto.getJvmConstructorSignature(
      nameResolver, classProto.typeTable)
  val element = classProto.fqName
      .let(nameResolver::getString)
      .replace('/', '.')
      .let(elements::getTypeElement)
      .enclosedElements
      .mapNotNull {
        it.takeIf { it.kind == ElementKind.CONSTRUCTOR }?.let { it as ExecutableElement }
      }
      .first()
  // TODO Temporary until JVM method signature matching is better
  //  .single { it.jvmMethodSignature == constructorJvmSignature }

  val parameters = mutableMapOf<String, TargetParameter>()
  for (parameter in proto.valueParameterList) {
    val name = nameResolver.getString(parameter.name)
    val index = proto.valueParameterList.indexOf(parameter)
    val paramElement = element.parameters[index]
    parameters[name] = TargetParameter(
        name = name,
        index = index,
        hasDefault = parameter.declaresDefaultValue,
        qualifiers = paramElement.qualifiers.mapTo(mutableSetOf(), AnnotationMirror::asAnnotationSpec),
        jsonName = paramElement.jsonName ?: name,
        tags = mapOf(VariableElement::class to paramElement)
    )
  }

  return TargetConstructor(parameters,
      proto.visibility.asKModifier())
}

private val OBJECT_CLASS = ClassName("java.lang", "Object")

/** Returns a target type for `element`, or null if it cannot be used with code gen. */
internal fun targetType(messager: Messager,
    elements: Elements,
    types: Types,
    element: Element): TargetType? {
  val typeMetadata: KotlinMetadata? = element.kotlinMetadata
  if (element !is TypeElement || typeMetadata !is KotlinClassMetadata) {
    messager.printMessage(
        ERROR, "@JsonClass can't be applied to $element: must be a Kotlin class", element)
    return null
  }

  val proto = typeMetadata.data.classProto
  when {
    proto.classKind == Class.Kind.ENUM_CLASS -> {
      messager.printMessage(
          ERROR,
          "@JsonClass with 'generateAdapter = \"true\"' can't be applied to $element: code gen for enums is not supported or necessary",
          element)
      return null
    }
    proto.classKind != Class.Kind.CLASS -> {
      messager.printMessage(
          ERROR, "@JsonClass can't be applied to $element: must be a Kotlin class", element)
      return null
    }
    proto.isInnerClass -> {
      messager.printMessage(
          ERROR, "@JsonClass can't be applied to $element: must not be an inner class", element)
      return null
    }
    proto.modality == SEALED -> {
      messager.printMessage(
          ERROR, "@JsonClass can't be applied to $element: must not be sealed", element)
      return null
    }
    proto.modality == ABSTRACT -> {
      messager.printMessage(
          ERROR, "@JsonClass can't be applied to $element: must not be abstract", element)
      return null
    }
    proto.visibility == LOCAL -> {
      messager.printMessage(
          ERROR, "@JsonClass can't be applied to $element: must not be local", element)
      return null
    }
  }

  val typeVariables = genericTypeNames(proto, typeMetadata.data.nameResolver)
  val appliedType = AppliedType.get(element)

  val constructor = primaryConstructor(typeMetadata, elements)
  if (constructor.visibility != KModifier.INTERNAL && constructor.visibility != KModifier.PUBLIC) {
    messager.printMessage(ERROR, "@JsonClass can't be applied to $element: " +
        "primary constructor is not internal or public", element)
    return null
  }

  val properties = mutableMapOf<String, TargetProperty>()
  for (supertype in appliedType.supertypes(types)) {
    if (supertype.element.asClassName() == OBJECT_CLASS) {
      continue // Don't load properties for java.lang.Object.
    }
    if (supertype.element.kind != ElementKind.CLASS) {
      continue // Don't load properties for interface types.
    }
    if (supertype.element.kotlinMetadata == null) {
      messager.printMessage(ERROR,
          "@JsonClass can't be applied to $element: supertype $supertype is not a Kotlin type",
          element)
      return null
    }
    val supertypeProperties = declaredProperties(
        supertype.element, supertype.resolver, constructor)
    for ((name, property) in supertypeProperties) {
      properties.putIfAbsent(name, property)
    }
  }
  return TargetType(
      typeName = element.asType().asTypeName(),
      constructor = constructor,
      properties = properties,
      typeVariables = typeVariables,
      isDataClass = proto.isDataClass,
      visibility = proto.visibility.asKModifier())
}

/** Returns the properties declared by `typeElement`. */
private fun declaredProperties(
    typeElement: TypeElement,
    typeResolver: TypeResolver,
    constructor: TargetConstructor
): Map<String, TargetProperty> {
  val typeMetadata: KotlinClassMetadata = typeElement.kotlinMetadata as KotlinClassMetadata
  val nameResolver = typeMetadata.data.nameResolver
  val classProto = typeMetadata.data.classProto

  val annotationHolders = mutableMapOf<String, ExecutableElement>()
  val fields = mutableMapOf<String, VariableElement>()
  val setters = mutableMapOf<String, ExecutableElement>()
  val getters = mutableMapOf<String, ExecutableElement>()
  for (element in typeElement.enclosedElements) {
    if (element is VariableElement) {
      fields[element.name] = element
    } else if (element is ExecutableElement) {
      when {
        element.name.startsWith("get") -> {
          val name = element.name.substring("get".length).decapitalizeAsciiOnly()
          getters[name] = element
        }
        element.name.startsWith("is") -> {
          val name = element.name.substring("is".length).decapitalizeAsciiOnly()
          getters[name] = element
        }
        element.name.startsWith("set") -> {
          val name = element.name.substring("set".length).decapitalizeAsciiOnly()
          setters[name] = element
        }
      }

      val propertyProto = typeMetadata.data.getPropertyOrNull(element)
      if (propertyProto != null) {
        val name = nameResolver.getString(propertyProto.name)
        annotationHolders[name] = element
      }
    }
  }

  val result = mutableMapOf<String, TargetProperty>()
  for (property in classProto.propertyList) {
    val name = nameResolver.getString(property.name)
    val type = typeResolver.resolve(property.returnType.asTypeName(
        nameResolver, classProto::getTypeParameter, false))
    val parameter = constructor.parameters[name]
    val fieldElement = fields[name]
    val annotationHolder = annotationHolders[name]
    // Used for setter/getter/is lookups. Guaranteed to be safe because kotlin doesn't allow you to
    // have both "AAA" and "aAA".
    val decapitalizedName = name.decapitalizeAsciiOnly()
    result[name] = TargetProperty(name = name,
        type = type,
        parameter = parameter,
        annotationHolder = annotationHolder?.asFunSpec(),
        field = fieldElement?.asPropertySpec(),
        setter = setters[decapitalizedName]?.asFunSpec(),
        getter = getters[decapitalizedName]?.asFunSpec(),
        visibility = property.visibility.asKModifier(),
        jsonName = jsonName(
            fieldElement = fieldElement,
            parameter = parameter,
            annotationHolder = annotationHolder,
            name = name
        )
    )
  }

  return result
}

/** Returns the @Json name of this property, or this property's name if none is provided. */
private fun jsonName(
    fieldElement: Element?,
    parameter: TargetParameter?,
    annotationHolder: ExecutableElement?,
    name: String): String {
  val fieldJsonName = fieldElement.jsonName
  val annotationHolderJsonName = annotationHolder.jsonName
  val parameterJsonName = parameter?.jsonName

  return when {
    fieldJsonName != null -> fieldJsonName
    annotationHolderJsonName != null -> annotationHolderJsonName
    parameterJsonName != null -> parameterJsonName
    else -> name
  }
}

private val Element.name get() = simpleName.toString()

private fun genericTypeNames(proto: Class, nameResolver: NameResolver): List<TypeVariableName> {
  return proto.typeParameterList.map { typeParameter ->
    val possibleBounds = typeParameter.upperBoundList
        .map { it.asTypeName(nameResolver, proto::getTypeParameter, false) }
    val typeVar = if (possibleBounds.isEmpty()) {
      TypeVariableName(
          name = nameResolver.getString(typeParameter.name),
          variance = typeParameter.varianceModifier)
    } else {
      TypeVariableName(
          name = nameResolver.getString(typeParameter.name),
          bounds = *possibleBounds.toTypedArray(),
          variance = typeParameter.varianceModifier)
    }
    return@map typeVar.copy(reified = typeParameter.reified)
  }
}

private val TypeParameter.varianceModifier: KModifier?
  get() {
    return variance.asKModifier().let {
      // We don't redeclare out variance here
      if (it == KModifier.OUT) {
        null
      } else {
        it
      }
    }
  }

/**
 * Returns a new [PropertySpec] representation of [this].
 *
 * This will copy its name, type, visibility modifiers, constant value, and annotations. Note that
 * Java modifiers that correspond to annotations in kotlin will be added as well (`volatile`,
 * `transient`, etc`.
 *
 * The original `field` ([this]) is stored in [PropertySpec.tag].
 */
internal fun VariableElement.asPropertySpec(asJvmField: Boolean = false): PropertySpec {
  require(kind == ElementKind.FIELD) {
    "Must be a field!"
  }
  val modifiers: Set<Modifier> = modifiers
  val fieldName = simpleName.toString()
  val propertyBuilder = PropertySpec.builder(fieldName, asType().asTypeName())
  propertyBuilder.addModifiers(*modifiers.mapNotNull { it.asKModifier() }.toTypedArray())
  constantValue?.let {
    if (it is String) {
      propertyBuilder.initializer(CodeBlock.of("%S", it))
    } else {
      propertyBuilder.initializer(CodeBlock.of("%L", it))
    }
  }
  propertyBuilder.addAnnotations(annotationMirrors.map(AnnotationMirror::asAnnotationSpec))
  propertyBuilder.addAnnotations(modifiers.mapNotNull { it.asAnnotation() })
  propertyBuilder.tag(this)
  if (asJvmField && KModifier.PRIVATE !in propertyBuilder.modifiers) {
    propertyBuilder.addAnnotation(JvmField::class)
  }
  return propertyBuilder.build()
}

/**
 * Returns a new [AnnotationSpec] representation of [this].
 *
 * Identical and delegates to [AnnotationSpec.get], but the original `mirror` is also stored
 * in [AnnotationSpec.tag].
 */
internal fun AnnotationMirror.asAnnotationSpec(): AnnotationSpec {
  return AnnotationSpec.get(this)
      .toBuilder()
      .tag(MoreTypes.asTypeElement(annotationType))
      .build()
}

/**
 * Returns a new [FunSpec] representation of [this].
 *
 * This will copy its visibility modifiers, type parameters, return type, name, parameters, and
 * throws declarations.
 *
 * The original `method` ([this]) is stored in [FunSpec.tag].
 *
 * Nearly identical to [FunSpec.overriding], but no override modifier is added nor are checks around
 * overridability done
 */
internal fun ExecutableElement.asFunSpec(): FunSpec {
  var modifiers: Set<Modifier> = modifiers
  val methodName = simpleName.toString()
  val funBuilder = FunSpec.builder(methodName)

  modifiers = modifiers.toMutableSet()
  funBuilder.jvmModifiers(modifiers)

  typeParameters
      .map { it.asType() as TypeVariable }
      .map { it.asTypeVariableName() }
      .forEach { funBuilder.addTypeVariable(it) }

  funBuilder.returns(returnType.asTypeName())
  funBuilder.addParameters(ParameterSpec.parametersOf(this))
  if (isVarArgs) {
    funBuilder.parameters[funBuilder.parameters.lastIndex] = funBuilder.parameters.last()
        .toBuilder()
        .addModifiers(VARARG)
        .build()
  }

  if (thrownTypes.isNotEmpty()) {
    val throwsValueString = thrownTypes.joinToString { "%T::class" }
    funBuilder.addAnnotation(AnnotationSpec.builder(Throws::class)
        .addMember(throwsValueString, *thrownTypes.toTypedArray())
        .build())
  }

  funBuilder.tag(this)
  return funBuilder.build()
}

private val TargetProperty.isTransient get() = field != null && field.annotations.any { it.className == Transient::class.asClassName() }
private val TargetProperty.isSettable get() = setter != null || parameter != null
private val TargetProperty.isVisible: Boolean
  get() {
    return visibility == KModifier.INTERNAL
        || visibility == KModifier.PROTECTED
        || visibility == KModifier.PUBLIC
  }

/**
 * Returns a generator for this property, or null if either there is an error and this property
 * cannot be used with code gen, or if no codegen is necessary for this property.
 */
internal fun TargetProperty.generator(messager: Messager): PropertyGenerator? {
  val element = field?.tag<VariableElement>() ?: setter?.tag<ExecutableElement>()
  ?: getter!!.tag<ExecutableElement>()
  if (isTransient) {
    if (!hasDefault) {
      element?.let {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "No default value for transient property ${this}",
            it)
      }
      return null
    }
    return PropertyGenerator(this, DelegateKey(type, emptyList()), true)
  }

  if (!isVisible) {
    element?.let {
      messager.printMessage(Diagnostic.Kind.ERROR, "property ${this} is not visible",
          it)
    }
    return null
  }

  if (!isSettable) {
    return null // This property is not settable. Ignore it.
  }

  val jsonQualifierMirrors = jsonQualifiers(element)
  for (jsonQualifier in jsonQualifierMirrors) {
    // Check Java types since that covers both Java and Kotlin annotations.
    val annotationElement = jsonQualifier.tag<TypeElement>() ?: continue
    annotationElement.getAnnotation(Retention::class.java)?.let {
      if (it.value != RetentionPolicy.RUNTIME) {
        messager.printMessage(Diagnostic.Kind.ERROR,
            "JsonQualifier @${jsonQualifier.className.simpleName} must have RUNTIME retention")
      }
    }
    annotationElement.getAnnotation(Target::class.java)?.let {
      if (ElementType.FIELD !in it.value) {
        messager.printMessage(Diagnostic.Kind.ERROR,
            "JsonQualifier @${jsonQualifier.className.simpleName} must support FIELD target")
      }
    }
  }

  val jsonQualifierSpecs = jsonQualifierMirrors.map {
    it.toBuilder()
        .useSiteTarget(AnnotationSpec.UseSiteTarget.FIELD)
        .build()
  }

  return PropertyGenerator(this,
      DelegateKey(type, jsonQualifierSpecs))
}

/** Returns the JsonQualifiers on the field and parameter of this property. */
private fun TargetProperty.jsonQualifiers(element: Element?): Set<AnnotationSpec> {
  val elementQualifiers = element.qualifiers
  val annotationHolderQualifiers = annotationHolder?.tag<ExecutableElement>().qualifiers
  val parameterQualifiers = parameter?.qualifiers.orEmpty()

  // TODO(jwilson): union the qualifiers somehow?
  return when {
    elementQualifiers.isNotEmpty() -> elementQualifiers.mapTo(mutableSetOf(), AnnotationMirror::asAnnotationSpec)
    annotationHolderQualifiers.isNotEmpty() -> annotationHolderQualifiers.mapTo(
        mutableSetOf(), AnnotationMirror::asAnnotationSpec)
    parameterQualifiers.isNotEmpty() -> parameterQualifiers
    else -> setOf()
  }
}

private fun Modifier.asKModifier(): KModifier? {
  return when (this) {
    Modifier.PUBLIC -> KModifier.PUBLIC
    Modifier.PROTECTED -> KModifier.PROTECTED
    Modifier.PRIVATE -> KModifier.PRIVATE
    Modifier.ABSTRACT -> KModifier.ABSTRACT
    FINAL -> KModifier.FINAL
    else -> null
  }
}

private fun Modifier.asAnnotation(): AnnotationSpec? {
  return when (this) {
    DEFAULT -> JvmDefault::class.asAnnotationSpec()
    STATIC -> JvmStatic::class.asAnnotationSpec()
    TRANSIENT -> Transient::class.asAnnotationSpec()
    VOLATILE -> Volatile::class.asAnnotationSpec()
    SYNCHRONIZED -> Synchronized::class.asAnnotationSpec()
    NATIVE -> JvmDefault::class.asAnnotationSpec()
    else -> null
  }
}

private fun <T : Annotation> KClass<T>.asAnnotationSpec(): AnnotationSpec {
  return AnnotationSpec.builder(this).build()
}

private val Element?.qualifiers: Set<AnnotationMirror>
  get() {
    if (this == null) return setOf()
    return AnnotationMirrors.getAnnotatedAnnotations(this, JsonQualifier::class.java)
  }

private val Element?.jsonName: String?
  get() {
    if (this == null) return null
    return getAnnotation(Json::class.java)?.name
  }
