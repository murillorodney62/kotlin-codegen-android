/*
 * Copyright (C) 2017 Square, Inc.
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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.SimpleTimeZone
import kotlin.annotation.AnnotationRetention.RUNTIME

class KotlinCodeGenTest {
  @Ignore @Test fun duplicatedValue() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(DuplicateValue::class.java)

    try {
      jsonAdapter.fromJson("""{"a":4,"a":4}""")
      fail()
    } catch(expected: JsonDataException) {
      assertThat(expected).hasMessage("Multiple values for a at $.a")
    }
  }

  class DuplicateValue(var a: Int = -1, var b: Int = -2)

  @Ignore @Test fun nonNullPropertySetToNullFailsWithJsonDataException() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(HasNonNullProperty::class.java)

    try {
      jsonAdapter.fromJson("{\"a\":null}")
      fail()
    } catch (expected: JsonDataException) {
      assertThat(expected).hasMessage("Non-null value a was null at \$")
    }
  }

  class HasNonNullProperty {
    var a: String = ""
  }

  @Ignore @Test fun repeatedValue() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(RepeatedValue::class.java)

    try {
      jsonAdapter.fromJson("""{"a":4,"b":null,"b":6}""")
      fail()
    } catch(expected: JsonDataException) {
      assertThat(expected).hasMessage("Multiple values for b at $.b")
    }
  }

  class RepeatedValue(var a: Int, var b: Int?)

  @Ignore @Test fun requiredTransientConstructorParameterFails() {
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(RequiredTransientConstructorParameter::class.java)
      fail()
    } catch (expected: IllegalArgumentException) {
      assertThat(expected).hasMessage("No default value for transient constructor parameter #0 " +
          "a of fun <init>(kotlin.Int): " +
          "com.squareup.moshi.KotlinJsonAdapterTest.RequiredTransientConstructorParameter")
    }
  }

  class RequiredTransientConstructorParameter(@Transient var a: Int)

  @Ignore @Test fun supertypeConstructorParameters() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(SubtypeConstructorParameters::class.java)

    val encoded = SubtypeConstructorParameters(3, 5)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3,"b":5}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"b":6}""")!!
    assertThat(decoded.a).isEqualTo(4)
    assertThat(decoded.b).isEqualTo(6)
  }

  open class SupertypeConstructorParameters(var a: Int)

  class SubtypeConstructorParameters(a: Int, var b: Int) : SupertypeConstructorParameters(a)

  @Ignore @Test fun supertypeProperties() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(SubtypeProperties::class.java)

    val encoded = SubtypeProperties()
    encoded.a = 3
    encoded.b = 5
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"b":5,"a":3}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"b":6}""")!!
    assertThat(decoded.a).isEqualTo(4)
    assertThat(decoded.b).isEqualTo(6)
  }

  open class SupertypeProperties {
    var a: Int = -1
  }

  class SubtypeProperties : SupertypeProperties() {
    var b: Int = -1
  }

  @Ignore @Test fun extendsPlatformClassWithPrivateField() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(ExtendsPlatformClassWithPrivateField::class.java)

    val encoded = ExtendsPlatformClassWithPrivateField(3)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"id":"B"}""")!!
    assertThat(decoded.a).isEqualTo(4)
    assertThat(decoded.id).isEqualTo("C")
  }

  internal class ExtendsPlatformClassWithPrivateField(var a: Int) : SimpleTimeZone(0, "C")

  @Ignore @Test fun extendsPlatformClassWithProtectedField() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(ExtendsPlatformClassWithProtectedField::class.java)

    val encoded = ExtendsPlatformClassWithProtectedField(3)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3,"buf":[0,0],"count":0}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"buf":[0,0],"size":0}""")!!
    assertThat(decoded.a).isEqualTo(4)
    assertThat(decoded.buf()).isEqualTo(ByteArray(2, { 0 }))
    assertThat(decoded.count()).isEqualTo(0)
  }

  internal class ExtendsPlatformClassWithProtectedField(var a: Int) : ByteArrayOutputStream(2) {
    fun buf() = buf
    fun count() = count
  }

  @Ignore @Test fun platformTypeThrows() {
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(Triple::class.java)
      fail()
    } catch (e: IllegalArgumentException) {
      assertThat(e).hasMessage("Platform class kotlin.Triple annotated [] "
          + "requires explicit JsonAdapter to be registered")
    }
  }

  @Ignore @Test fun privateConstructorParameters() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(PrivateConstructorParameters::class.java)

    val encoded = PrivateConstructorParameters(3, 5)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3,"b":5}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"b":6}""")!!
    assertThat(decoded.a()).isEqualTo(4)
    assertThat(decoded.b()).isEqualTo(6)
  }

  class PrivateConstructorParameters(private var a: Int, private var b: Int) {
    fun a() = a
    fun b() = b
  }

  @Ignore @Test fun privateConstructor() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(PrivateConstructor::class.java)

    val encoded = PrivateConstructor.newInstance(3, 5)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3,"b":5}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"b":6}""")!!
    assertThat(decoded.a()).isEqualTo(4)
    assertThat(decoded.b()).isEqualTo(6)
  }

  class PrivateConstructor private constructor(var a: Int, var b: Int) {
    fun a() = a
    fun b() = b
    companion object {
      fun newInstance(a: Int, b: Int) = PrivateConstructor(a, b)
    }
  }

  @Ignore @Test fun privateProperties() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(PrivateProperties::class.java)

    val encoded = PrivateProperties()
    encoded.a(3)
    encoded.b(5)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3,"b":5}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"b":6}""")!!
    assertThat(decoded.a()).isEqualTo(4)
    assertThat(decoded.b()).isEqualTo(6)
  }

  class PrivateProperties {
    var a: Int = -1
    var b: Int = -1

    fun a() = a

    fun a(a: Int) {
      this.a = a
    }

    fun b() = b

    fun b(b: Int) {
      this.b = b
    }
  }

  @Ignore @Test fun unsettablePropertyIgnored() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(UnsettableProperty::class.java)

    val encoded = UnsettableProperty()
    encoded.b = 5
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"b":5}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"b":6}""")!!
    assertThat(decoded.a).isEqualTo(-1)
    assertThat(decoded.b).isEqualTo(6)
  }

  class UnsettableProperty {
    val a: Int = -1
    var b: Int = -1
  }

  @Ignore @Test fun getterOnlyNoBackingField() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(GetterOnly::class.java)

    val encoded = GetterOnly(3, 5)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3,"b":5}""")

    val decoded = jsonAdapter.fromJson("""{"a":4,"b":6}""")!!
    assertThat(decoded.a).isEqualTo(4)
    assertThat(decoded.b).isEqualTo(6)
    assertThat(decoded.total).isEqualTo(10)
  }

  class GetterOnly(var a: Int, var b: Int) {
    val total : Int
      get() = a + b
  }

  @Ignore @Test fun getterAndSetterNoBackingField() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(GetterAndSetter::class.java)

    val encoded = GetterAndSetter(3, 5)
    assertThat(jsonAdapter.toJson(encoded)).isEqualTo("""{"a":3,"b":5,"total":8}""")

    // Whether b is 6 or 7 is an implementation detail. Currently we call constructors then setters.
    val decoded1 = jsonAdapter.fromJson("""{"a":4,"b":6,"total":11}""")!!
    assertThat(decoded1.a).isEqualTo(4)
    assertThat(decoded1.b).isEqualTo(7)
    assertThat(decoded1.total).isEqualTo(11)

    // Whether b is 6 or 7 is an implementation detail. Currently we call constructors then setters.
    val decoded2 = jsonAdapter.fromJson("""{"a":4,"total":11,"b":6}""")!!
    assertThat(decoded2.a).isEqualTo(4)
    assertThat(decoded2.b).isEqualTo(7)
    assertThat(decoded2.total).isEqualTo(11)
  }

  class GetterAndSetter(var a: Int, var b: Int) {
    var total : Int
      get() = a + b
      set(value) {
        b = value - a
      }
  }

  @Ignore @Test fun nonPropertyConstructorParameter() {
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(NonPropertyConstructorParameter::class.java)
      fail()
    } catch(expected: IllegalArgumentException) {
      assertThat(expected).hasMessage(
          "No property for required constructor parameter #0 a of fun <init>(" +
              "kotlin.Int, kotlin.Int): ${NonPropertyConstructorParameter::class.qualifiedName}")
    }
  }

  class NonPropertyConstructorParameter(a: Int, val b: Int)

  @Ignore @Test fun kotlinEnumsAreNotCovered() {
    val moshi = Moshi.Builder().build()
    val adapter = moshi.adapter(UsingEnum::class.java)

    assertThat(adapter.fromJson("""{"e": "A"}""")).isEqualTo(UsingEnum(KotlinEnum.A))
  }

  data class UsingEnum(val e: KotlinEnum)

  enum class KotlinEnum {
    A, B
  }

  @Ignore @Test fun interfacesNotSupported() {
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(Interface::class.java)
      fail()
    } catch (e: IllegalArgumentException) {
      assertThat(e).hasMessage("No JsonAdapter for interface " +
          "com.squareup.moshi.KotlinJsonAdapterTest\$Interface annotated []")
    }
  }

  interface Interface

  @Ignore @Test fun abstractClassesNotSupported() {
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(AbstractClass::class.java)
      fail()
    } catch (e: IllegalArgumentException) {
      assertThat(e).hasMessage(
          "Cannot serialize abstract class com.squareup.moshi.KotlinJsonAdapterTest\$AbstractClass")
    }
  }

  abstract class AbstractClass(val a: Int)

  @Ignore @Test fun innerClassesNotSupported() {
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(InnerClass::class.java)
      fail()
    } catch (e: IllegalArgumentException) {
      assertThat(e).hasMessage(
          "Cannot serialize non-static nested class com.squareup.moshi.KotlinCodeGenTest\$InnerClass")
    }
  }

  inner class InnerClass(val a: Int)

  @Ignore @Test fun localClassesNotSupported() {
    class LocalClass(val a: Int)
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(LocalClass::class.java)
      fail()
    } catch (e: IllegalArgumentException) {
      assertThat(e).hasMessage("Cannot serialize local class or object expression " +
          "com.squareup.moshi.KotlinJsonAdapterTest\$localClassesNotSupported\$LocalClass")
    }
  }

  @Ignore @Test fun objectDeclarationsNotSupported() {
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(ObjectDeclaration.javaClass)
      fail()
    } catch (e: IllegalArgumentException) {
      assertThat(e).hasMessage("Cannot serialize object declaration " +
          "com.squareup.moshi.KotlinJsonAdapterTest\$ObjectDeclaration")
    }
  }

  object ObjectDeclaration {
    var a = 5
  }

  @Ignore @Test fun objectExpressionsNotSupported() {
    val expression = object : Any() {
      var a = 5
    }
    val moshi = Moshi.Builder().build()
    try {
      moshi.adapter(expression.javaClass)
      fail()
    } catch (e: IllegalArgumentException) {
      assertThat(e).hasMessage("Cannot serialize local class or object expression " +
          "com.squareup.moshi.KotlinJsonAdapterTest\$objectExpressionsNotSupported\$expression$1")
    }
  }

  @Ignore @Test fun manyProperties32() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(ManyProperties32::class.java)

    val encoded = ManyProperties32(
        101, 102, 103, 104, 105,
        106, 107, 108, 109, 110,
        111, 112, 113, 114, 115,
        116, 117, 118, 119, 120,
        121, 122, 123, 124, 125,
        126, 127, 128, 129, 130,
        131, 132)
    val json = ("""
        |{
        |"v01":101,"v02":102,"v03":103,"v04":104,"v05":105,
        |"v06":106,"v07":107,"v08":108,"v09":109,"v10":110,
        |"v11":111,"v12":112,"v13":113,"v14":114,"v15":115,
        |"v16":116,"v17":117,"v18":118,"v19":119,"v20":120,
        |"v21":121,"v22":122,"v23":123,"v24":124,"v25":125,
        |"v26":126,"v27":127,"v28":128,"v29":129,"v30":130,
        |"v31":131,"v32":132
        |}
        |""").trimMargin().replace("\n", "")

    assertThat(jsonAdapter.toJson(encoded)).isEqualTo(json)

    val decoded = jsonAdapter.fromJson(json)!!
    assertThat(decoded.v01).isEqualTo(101)
    assertThat(decoded.v32).isEqualTo(132)
  }

  class ManyProperties32(
      var v01: Int, var v02: Int, var v03: Int, var v04: Int, var v05: Int,
      var v06: Int, var v07: Int, var v08: Int, var v09: Int, var v10: Int,
      var v11: Int, var v12: Int, var v13: Int, var v14: Int, var v15: Int,
      var v16: Int, var v17: Int, var v18: Int, var v19: Int, var v20: Int,
      var v21: Int, var v22: Int, var v23: Int, var v24: Int, var v25: Int,
      var v26: Int, var v27: Int, var v28: Int, var v29: Int, var v30: Int,
      var v31: Int, var v32: Int)

  @Ignore @Test fun manyProperties33() {
    val moshi = Moshi.Builder().build()
    val jsonAdapter = moshi.adapter(ManyProperties33::class.java)

    val encoded = ManyProperties33(
        101, 102, 103, 104, 105,
        106, 107, 108, 109, 110,
        111, 112, 113, 114, 115,
        116, 117, 118, 119, 120,
        121, 122, 123, 124, 125,
        126, 127, 128, 129, 130,
        131, 132, 133)
    val json = ("""
        |{
        |"v01":101,"v02":102,"v03":103,"v04":104,"v05":105,
        |"v06":106,"v07":107,"v08":108,"v09":109,"v10":110,
        |"v11":111,"v12":112,"v13":113,"v14":114,"v15":115,
        |"v16":116,"v17":117,"v18":118,"v19":119,"v20":120,
        |"v21":121,"v22":122,"v23":123,"v24":124,"v25":125,
        |"v26":126,"v27":127,"v28":128,"v29":129,"v30":130,
        |"v31":131,"v32":132,"v33":133
        |}
        |""").trimMargin().replace("\n", "")

    assertThat(jsonAdapter.toJson(encoded)).isEqualTo(json)

    val decoded = jsonAdapter.fromJson(json)!!
    assertThat(decoded.v01).isEqualTo(101)
    assertThat(decoded.v32).isEqualTo(132)
    assertThat(decoded.v33).isEqualTo(133)
  }

  class ManyProperties33(
      var v01: Int, var v02: Int, var v03: Int, var v04: Int, var v05: Int,
      var v06: Int, var v07: Int, var v08: Int, var v09: Int, var v10: Int,
      var v11: Int, var v12: Int, var v13: Int, var v14: Int, var v15: Int,
      var v16: Int, var v17: Int, var v18: Int, var v19: Int, var v20: Int,
      var v21: Int, var v22: Int, var v23: Int, var v24: Int, var v25: Int,
      var v26: Int, var v27: Int, var v28: Int, var v29: Int, var v30: Int,
      var v31: Int, var v32: Int, var v33: Int)

  // TODO(jwilson): resolve generic types?

  @Retention(RUNTIME)
  @JsonQualifier
  annotation class Uppercase

  class UppercaseJsonAdapter {
    @ToJson fun toJson(@Uppercase s: String) : String {
      return s.toUpperCase(Locale.US)
    }
    @FromJson @Uppercase fun fromJson(s: String) : String {
      return s.toLowerCase(Locale.US)
    }
  }
}
