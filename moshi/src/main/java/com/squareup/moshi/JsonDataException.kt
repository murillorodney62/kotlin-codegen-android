/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.moshi

/**
 * Thrown when the data in a JSON document doesn't match the data expected by the caller. For
 * example, suppose the application expects a boolean but the JSON document contains a string. When
 * the call to [JsonReader.nextBoolean] is made, a `JsonDataException` is thrown.
 *
 * Exceptions of this type should be fixed by either changing the application code to accept the
 * unexpected JSON, or by changing the JSON to conform to the application's expectations.
 *
 * This exception may also be triggered if a document's nesting exceeds 31 levels. This depth is
 * sufficient for all practical applications, but shallow enough to avoid uglier failures like
 * [StackOverflowError].
 */
public class JsonDataException : RuntimeException {
  public constructor() : super()
  public constructor(message: String?) : super(message)
  public constructor(cause: Throwable?) : super(cause)
  public constructor(message: String?, cause: Throwable?) : super(message, cause)
}
