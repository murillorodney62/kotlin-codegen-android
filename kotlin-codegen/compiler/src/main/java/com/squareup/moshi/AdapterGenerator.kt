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
package com.squareup.moshi

import com.squareup.kotlinpoet.ARRAY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import org.jetbrains.kotlin.serialization.ProtoBuf
import java.lang.reflect.Type
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements

/** Generates a JSON adapter for a target type. */
internal class AdapterGenerator(
  val className: ClassName,
  val propertyList: List<PropertyGenerator>,
  val originalElement: Element,
  val isDataClass: Boolean,
  val hasCompanionObject: Boolean,
  val visibility: ProtoBuf.Visibility,
  val elements: Elements,
  val genericTypeNames: List<TypeVariableName>?
) {
  val nameAllocator = NameAllocator()
  val adapterName = "${className.simpleNames().joinToString(separator = "_")}JsonAdapter"
  val originalTypeName = originalElement.asType().asTypeName()

  val moshiParam = ParameterSpec.builder(
      nameAllocator.newName("moshi"),
      Moshi::class).build()
  val typesParam = ParameterSpec.builder(
      nameAllocator.newName("types"),
      ParameterizedTypeName.get(ARRAY,
          Type::class.asTypeName()))
      .build()
  val readerParam = ParameterSpec.builder(
      nameAllocator.newName("reader"),
      JsonReader::class)
      .build()
  val writerParam = ParameterSpec.builder(
      nameAllocator.newName("writer"),
      JsonWriter::class)
      .build()
  val valueParam = ParameterSpec.builder(
      nameAllocator.newName("value"),
      originalTypeName.asNullable())
      .build()
  val jsonAdapterTypeName = ParameterizedTypeName.get(
      JsonAdapter::class.asClassName(), originalTypeName)

  // selectName() API setup
  val optionsProperty = PropertySpec.builder(
      nameAllocator.newName("options"), JsonReader.Options::class.asTypeName(),
      KModifier.PRIVATE)
      .initializer("%T.of(${propertyList.map { it.serializedName }
          .joinToString(", ") { "\"$it\"" }})", JsonReader.Options::class.asTypeName())
      .build()

  val delegateAdapters = propertyList.distinctBy { it.delegateKey() }

  fun generateFile(generatedOption: TypeElement?): FileSpec {
    for (property in delegateAdapters) {
      property.reserveDelegateNames(nameAllocator)
    }
    for (property in propertyList) {
      property.allocateNames(nameAllocator)
    }

    val result = FileSpec.builder(className.packageName(), adapterName)
    if (hasCompanionObject) {
      result.addFunction(generateJsonAdapterFun())
    }
    result.addType(generateType(generatedOption))
    return result.build()
  }

  private fun generateType(generatedOption: TypeElement?): TypeSpec {
    val result = TypeSpec.classBuilder(adapterName)

    generatedOption?.let {
      result.addAnnotation(AnnotationSpec.builder(it.asClassName())
          .addMember("%S", JsonClassCodeGenProcessor::class.java.canonicalName)
          .addMember("%S", "https://github.com/square/moshi")
          .build())
    }

    result.superclass(jsonAdapterTypeName)

    genericTypeNames?.let {
      result.addTypeVariables(genericTypeNames)
    }

    // TODO make this configurable. Right now it just matches the source model
    if (visibility == ProtoBuf.Visibility.INTERNAL) {
      result.addModifiers(KModifier.INTERNAL)
    }

    result.primaryConstructor(generateConstructor())

    result.addProperty(optionsProperty)
    for (uniqueAdapter in delegateAdapters) {
      result.addProperty(uniqueAdapter.generateDelegateProperty(this))
    }

    result.addFunction(generateToStringFun())
    result.addFunction(generateFromJsonFun())
    result.addFunction(generateToJsonFun())

    return result.build()
  }

  private fun generateConstructor(): FunSpec {
    val result = FunSpec.constructorBuilder()
    result.addParameter(moshiParam)

    genericTypeNames?.let {
      result.addParameter(typesParam)
    }

    return result.build()
  }

  private fun generateToStringFun(): FunSpec {
    return FunSpec.builder("toString")
        .addModifiers(KModifier.OVERRIDE)
        .returns(String::class)
        .addStatement("return %S",
            "GeneratedJsonAdapter(${originalTypeName.rawType().simpleNames().joinToString(".")})")
        .build()
  }

  private fun generateFromJsonFun(): FunSpec {
    val resultName = nameAllocator.newName("result")

    val result = FunSpec.builder("fromJson")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter(readerParam)
        .returns(originalTypeName)

    for (property in propertyList) {
      result.addCode("%L", property.generateLocalProperty())
      if (property.differentiateAbsentFromNull) {
        result.addCode("%L", property.generateLocalIsPresentProperty())
      }
    }

    result.addStatement("%N.beginObject()", readerParam)
    result.beginControlFlow("while (%N.hasNext())", readerParam)
    result.beginControlFlow("when (%N.selectName(%N))", readerParam, optionsProperty)

    propertyList.forEachIndexed { index, property ->
      if (property.differentiateAbsentFromNull) {
        result.beginControlFlow("%L -> ", index)
        result.addStatement("%N = %N.fromJson(%N)",
            property.localName, property.delegateName, readerParam)
        result.addStatement("%N = true", property.localIsPresentName)
        result.endControlFlow()
      } else {
        result.addStatement("%L -> %N = %N.fromJson(%N)",
            index, property.localName, property.delegateName, readerParam)
      }
    }

    result.beginControlFlow("-1 ->")
    result.addComment("Unknown name, skip it.")
    result.addStatement("%N.nextName()", readerParam)
    result.addStatement("%N.skipValue()", readerParam)
    result.endControlFlow()

    result.endControlFlow() // when
    result.endControlFlow() // while
    result.addStatement("%N.endObject()", readerParam)

    // Call the constructor providing only required parameters.
    var hasOptionalParameters = false
    result.addCode("%[var %N = %T(", resultName, originalTypeName)
    var separator = "\n"
    for (property in propertyList) {
      if (!property.hasConstructorParameter) {
        continue
      }
      if (property.hasDefault) {
        hasOptionalParameters = true
        continue
      }
      result.addCode(separator)
      result.addCode("%N = %N", property.name, property.localName)
      if (property.isRequired) {
        result.addCode(" ?: throw %T(\"Required property '%L' missing at \${%N.path}\")",
            JsonDataException::class, property.localName, readerParam)
      }
      separator = ",\n"
    }
    result.addCode(")%]\n", originalTypeName)

    // Call either the constructor again, or the copy() method, this time providing any optional
    // parameters that we have.
    if (hasOptionalParameters) {
      if (isDataClass) {
        result.addCode("%[%1N = %1N.copy(", resultName)
      } else {
        result.addCode("%[%1N = %2T(", resultName, originalTypeName)
      }
      separator = "\n"
      for (property in propertyList) {
        if (!property.hasConstructorParameter) {
          continue // No constructor parameter for this property.
        }
        if (isDataClass && !property.hasDefault) {
          continue // Property already assigned.
        }

        result.addCode(separator)
        if (property.differentiateAbsentFromNull) {
          result.addCode("%2N = if (%3N) %4N else %1N.%2N",
              resultName, property.name, property.localIsPresentName, property.localName)
        } else {
          result.addCode("%2N = %3N ?: %1N.%2N", resultName, property.name, property.localName)
        }
        separator = ",\n"
      }
      result.addCode("%])\n")
    }

    // Assign properties not present in the constructor.
    for (property in propertyList) {
      if (property.hasConstructorParameter) {
        continue // Property already handled.
      }
      if (property.differentiateAbsentFromNull) {
        result.addStatement("%1N.%2N = if (%3N) %4N else %1N.%2N",
            resultName, property.name, property.localIsPresentName, property.localName)
      } else {
        result.addStatement("%1N.%2N = %3N ?: %1N.%2N",
            resultName, property.name, property.localName)
      }
    }

    result.addStatement("return %1N", resultName)
    return result.build()
  }

  private fun generateToJsonFun(): FunSpec {
    val result = FunSpec.builder("toJson")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter(writerParam)
        .addParameter(valueParam)

    result.beginControlFlow("if (%N == null)", valueParam)
    result.addStatement("throw %T(%S)", NullPointerException::class,
        "${valueParam.name} was null! Wrap in .nullSafe() to write nullable values.")
    result.endControlFlow()

    result.addStatement("%N.beginObject()", writerParam)
    propertyList.forEach { property ->
      result.addStatement("%N.name(%S)", writerParam, property.serializedName)
      result.addStatement("%N.toJson(%N, %N.%L)",
          property.delegateName, writerParam, valueParam, property.name)
    }
    result.addStatement("%N.endObject()", writerParam)

    return result.build()
  }

  private fun generateJsonAdapterFun(): FunSpec {
    val rawType = when (originalTypeName) {
      is TypeVariableName -> throw IllegalArgumentException("Cannot get raw type of TypeVariable!")
      is ParameterizedTypeName -> originalTypeName.rawType
      else -> originalTypeName as ClassName
    }

    val result = FunSpec.builder("jsonAdapter")
        .receiver(rawType.nestedClass("Companion"))
        .returns(jsonAdapterTypeName)
        .addParameter(moshiParam)

    // TODO make this configurable. Right now it just matches the source model
    if (visibility == ProtoBuf.Visibility.INTERNAL) {
      result.addModifiers(KModifier.INTERNAL)
    }

    genericTypeNames?.let {
      result.addParameter(typesParam)
      result.addTypeVariables(it)
    }

    if (genericTypeNames != null) {
      result.addStatement("return %N(%N, %N)", adapterName, moshiParam, typesParam)
    } else {
      result.addStatement("return %N(%N)", adapterName, moshiParam)
    }

    return result.build()
  }
}
