/*
 * Copyright (C) 2010 Google Inc.
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
package com.squareup.moshi;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import okio.BufferedSink;
import okio.Sink;

import static com.squareup.moshi.JsonScope.DANGLING_NAME;
import static com.squareup.moshi.JsonScope.EMPTY_ARRAY;
import static com.squareup.moshi.JsonScope.EMPTY_DOCUMENT;
import static com.squareup.moshi.JsonScope.EMPTY_OBJECT;
import static com.squareup.moshi.JsonScope.NONEMPTY_ARRAY;
import static com.squareup.moshi.JsonScope.NONEMPTY_DOCUMENT;
import static com.squareup.moshi.JsonScope.NONEMPTY_OBJECT;

/**
 * Writes a JSON (<a href="http://www.ietf.org/rfc/rfc4627.txt">RFC 4627</a>)
 * encoded value to a stream, one token at a time. The stream includes both
 * literal values (strings, numbers, booleans and nulls) as well as the begin
 * and end delimiters of objects and arrays.
 *
 * <h3>Encoding JSON</h3>
 * To encode your data as JSON, create a new {@code JsonWriter}. Each JSON
 * document must contain one top-level array or object. Call methods on the
 * writer as you walk the structure's contents, nesting arrays and objects as
 * necessary:
 * <ul>
 *   <li>To write <strong>arrays</strong>, first call {@link #beginArray()}.
 *       Write each of the array's elements with the appropriate {@link #value}
 *       methods or by nesting other arrays and objects. Finally close the array
 *       using {@link #endArray()}.
 *   <li>To write <strong>objects</strong>, first call {@link #beginObject()}.
 *       Write each of the object's properties by alternating calls to
 *       {@link #name} with the property's value. Write property values with the
 *       appropriate {@link #value} method or by nesting other objects or arrays.
 *       Finally close the object using {@link #endObject()}.
 * </ul>
 *
 * <h3>Example</h3>
 * Suppose we'd like to encode a stream of messages such as the following: <pre> {@code
 * [
 *   {
 *     "id": 912345678901,
 *     "text": "How do I stream JSON in Java?",
 *     "geo": null,
 *     "user": {
 *       "name": "json_newb",
 *       "followers_count": 41
 *      }
 *   },
 *   {
 *     "id": 912345678902,
 *     "text": "@json_newb just use JsonWriter!",
 *     "geo": [50.454722, -104.606667],
 *     "user": {
 *       "name": "jesse",
 *       "followers_count": 2
 *     }
 *   }
 * ]}</pre>
 * This code encodes the above structure: <pre>   {@code
 *   public void writeJsonStream(BufferedSink sink, List<Message> messages) throws IOException {
 *     JsonWriter writer = JsonWriter.of(sink);
 *     writer.setIndentSpaces(4);
 *     writeMessagesArray(writer, messages);
 *     writer.close();
 *   }
 *
 *   public void writeMessagesArray(JsonWriter writer, List<Message> messages) throws IOException {
 *     writer.beginArray();
 *     for (Message message : messages) {
 *       writeMessage(writer, message);
 *     }
 *     writer.endArray();
 *   }
 *
 *   public void writeMessage(JsonWriter writer, Message message) throws IOException {
 *     writer.beginObject();
 *     writer.name("id").value(message.getId());
 *     writer.name("text").value(message.getText());
 *     if (message.getGeo() != null) {
 *       writer.name("geo");
 *       writeDoublesArray(writer, message.getGeo());
 *     } else {
 *       writer.name("geo").nullValue();
 *     }
 *     writer.name("user");
 *     writeUser(writer, message.getUser());
 *     writer.endObject();
 *   }
 *
 *   public void writeUser(JsonWriter writer, User user) throws IOException {
 *     writer.beginObject();
 *     writer.name("name").value(user.getName());
 *     writer.name("followers_count").value(user.getFollowersCount());
 *     writer.endObject();
 *   }
 *
 *   public void writeDoublesArray(JsonWriter writer, List<Double> doubles) throws IOException {
 *     writer.beginArray();
 *     for (Double value : doubles) {
 *       writer.value(value);
 *     }
 *     writer.endArray();
 *   }}</pre>
 *
 * <p>Each {@code JsonWriter} may be used to write a single JSON stream.
 * Instances of this class are not thread safe. Calls that would result in a
 * malformed JSON string will fail with an {@link IllegalStateException}.
 */
public class JsonWriter implements Closeable, Flushable {

  /*
   * From RFC 4627, "All Unicode characters may be placed within the
   * quotation marks except for the characters that must be escaped:
   * quotation mark, reverse solidus, and the control characters
   * (U+0000 through U+001F)."
   *
   * We also escape '\u2028' and '\u2029', which JavaScript interprets as
   * newline characters. This prevents eval() from failing with a syntax
   * error. http://code.google.com/p/google-gson/issues/detail?id=341
   */
  private static final String[] REPLACEMENT_CHARS;
  static {
    REPLACEMENT_CHARS = new String[128];
    for (int i = 0; i <= 0x1f; i++) {
      REPLACEMENT_CHARS[i] = String.format("\\u%04x", (int) i);
    }
    REPLACEMENT_CHARS['"'] = "\\\"";
    REPLACEMENT_CHARS['\\'] = "\\\\";
    REPLACEMENT_CHARS['\t'] = "\\t";
    REPLACEMENT_CHARS['\b'] = "\\b";
    REPLACEMENT_CHARS['\n'] = "\\n";
    REPLACEMENT_CHARS['\r'] = "\\r";
    REPLACEMENT_CHARS['\f'] = "\\f";
  }

  /** The output data, containing at most one top-level array or object. */
  private final BufferedSink sink;

  private int[] stack = new int[32];
  private int stackSize = 0;
  {
    push(EMPTY_DOCUMENT);
  }

  private String[] pathNames = new String[32];
  private int[] pathIndices = new int[32];

  /**
   * A string containing a full set of spaces for a single level of
   * indentation, or null for no pretty printing.
   */
  private String indent;

  /**
   * The name/value separator; either ":" or ": ".
   */
  private String separator = ":";

  private boolean lenient;

  private String deferredName;

  private boolean serializeNulls;

  private boolean promoteNameToValue;

  private JsonWriter(BufferedSink sink) {
    if (sink == null) {
      throw new NullPointerException("sink == null");
    }
    this.sink = sink;
  }

  /**
   * Returns a new instance that writes a JSON-encoded stream to {@code sink}.
   */
  public static JsonWriter of(BufferedSink sink) {
    return new JsonWriter(sink);
  }

  /**
   * Sets the indentation string to be repeated for each level of indentation
   * in the encoded document. If {@code indent.isEmpty()} the encoded document
   * will be compact. Otherwise the encoded document will be more
   * human-readable.
   *
   * @param indent a string containing only whitespace.
   */
  public final void setIndent(String indent) {
    if (indent.length() == 0) {
      this.indent = null;
      this.separator = ":";
    } else {
      this.indent = indent;
      this.separator = ": ";
    }
  }

  /**
   * Configure this writer to relax its syntax rules. By default, this writer
   * only emits well-formed JSON as specified by <a
   * href="http://www.ietf.org/rfc/rfc4627.txt">RFC 4627</a>. Setting the writer
   * to lenient permits the following:
   * <ul>
   *   <li>Top-level values of any type. With strict writing, the top-level
   *       value must be an object or an array.
   *   <li>Numbers may be {@linkplain Double#isNaN() NaNs} or {@linkplain
   *       Double#isInfinite() infinities}.
   * </ul>
   */
  public final void setLenient(boolean lenient) {
    this.lenient = lenient;
  }

  /**
   * Returns true if this writer has relaxed syntax rules.
   */
  public boolean isLenient() {
    return lenient;
  }

  /**
   * Sets whether object members are serialized when their value is null.
   * This has no impact on array elements. The default is false.
   */
  public final void setSerializeNulls(boolean serializeNulls) {
    this.serializeNulls = serializeNulls;
  }

  /**
   * Returns true if object members are serialized when their value is null.
   * This has no impact on array elements. The default is false.
   */
  public final boolean getSerializeNulls() {
    return serializeNulls;
  }

  /**
   * Begins encoding a new array. Each call to this method must be paired with
   * a call to {@link #endArray}.
   *
   * @return this writer.
   */
  public JsonWriter beginArray() throws IOException {
    writeDeferredName();
    return open(EMPTY_ARRAY, "[");
  }

  /**
   * Ends encoding the current array.
   *
   * @return this writer.
   */
  public JsonWriter endArray() throws IOException {
    return close(EMPTY_ARRAY, NONEMPTY_ARRAY, "]");
  }

  /**
   * Begins encoding a new object. Each call to this method must be paired
   * with a call to {@link #endObject}.
   *
   * @return this writer.
   */
  public JsonWriter beginObject() throws IOException {
    writeDeferredName();
    return open(EMPTY_OBJECT, "{");
  }

  /**
   * Ends encoding the current object.
   *
   * @return this writer.
   */
  public JsonWriter endObject() throws IOException {
    promoteNameToValue = false;
    return close(EMPTY_OBJECT, NONEMPTY_OBJECT, "}");
  }

  /**
   * Enters a new scope by appending any necessary whitespace and the given
   * bracket.
   */
  private JsonWriter open(int empty, String openBracket) throws IOException {
    beforeValue(true);
    pathIndices[stackSize] = 0;
    push(empty);
    sink.writeUtf8(openBracket);
    return this;
  }

  /**
   * Closes the current scope by appending any necessary whitespace and the
   * given bracket.
   */
  private JsonWriter close(int empty, int nonempty, String closeBracket)
      throws IOException {
    int context = peek();
    if (context != nonempty && context != empty) {
      throw new IllegalStateException("Nesting problem.");
    }
    if (deferredName != null) {
      throw new IllegalStateException("Dangling name: " + deferredName);
    }

    stackSize--;
    pathNames[stackSize] = null; // Free the last path name so that it can be garbage collected!
    pathIndices[stackSize - 1]++;
    if (context == nonempty) {
      newline();
    }
    sink.writeUtf8(closeBracket);
    return this;
  }

  private void push(int newTop) {
    if (stackSize == stack.length) {
      int[] newStack = new int[stackSize * 2];
      System.arraycopy(stack, 0, newStack, 0, stackSize);
      stack = newStack;
    }
    stack[stackSize++] = newTop;
  }

  /**
   * Returns the value on the top of the stack.
   */
  private int peek() {
    if (stackSize == 0) {
      throw new IllegalStateException("JsonWriter is closed.");
    }
    return stack[stackSize - 1];
  }

  /**
   * Replace the value on the top of the stack with the given value.
   */
  private void replaceTop(int topOfStack) {
    stack[stackSize - 1] = topOfStack;
  }

  /**
   * Encodes the property name.
   *
   * @param name the name of the forthcoming value. May not be null.
   * @return this writer.
   */
  public JsonWriter name(String name) throws IOException {
    if (name == null) {
      throw new NullPointerException("name == null");
    }
    if (stackSize == 0) {
      throw new IllegalStateException("JsonWriter is closed.");
    }
    if (deferredName != null) {
      throw new IllegalStateException();
    }
    deferredName = name;
    pathNames[stackSize - 1] = name;
    promoteNameToValue = false;
    return this;
  }

  private void writeDeferredName() throws IOException {
    if (deferredName != null) {
      beforeName();
      string(deferredName);
      deferredName = null;
    }
  }

  /**
   * Encodes {@code value}.
   *
   * @param value the literal string value, or null to encode a null literal.
   * @return this writer.
   */
  public JsonWriter value(String value) throws IOException {
    if (value == null) {
      return nullValue();
    }
    if (promoteNameToValue) {
      return name(value);
    }
    writeDeferredName();
    beforeValue(false);
    string(value);
    pathIndices[stackSize - 1]++;
    return this;
  }

  /**
   * Encodes {@code null}.
   *
   * @return this writer.
   */
  public JsonWriter nullValue() throws IOException {
    if (deferredName != null) {
      if (serializeNulls) {
        writeDeferredName();
      } else {
        deferredName = null;
        return this; // skip the name and the value
      }
    }
    beforeValue(false);
    sink.writeUtf8("null");
    pathIndices[stackSize - 1]++;
    return this;
  }

  /**
   * Encodes {@code value}.
   *
   * @return this writer.
   */
  public JsonWriter value(boolean value) throws IOException {
    writeDeferredName();
    beforeValue(false);
    sink.writeUtf8(value ? "true" : "false");
    pathIndices[stackSize - 1]++;
    return this;
  }

  /**
   * Encodes {@code value}.
   *
   * @param value a finite value. May not be {@linkplain Double#isNaN() NaNs} or
   *     {@linkplain Double#isInfinite() infinities}.
   * @return this writer.
   */
  public JsonWriter value(double value) throws IOException {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new IllegalArgumentException("Numeric values must be finite, but was " + value);
    }
    if (promoteNameToValue) {
      return name(Double.toString(value));
    }
    writeDeferredName();
    beforeValue(false);
    sink.writeUtf8(Double.toString(value));
    pathIndices[stackSize - 1]++;
    return this;
  }

  /**
   * Encodes {@code value}.
   *
   * @return this writer.
   */
  public JsonWriter value(long value) throws IOException {
    if (promoteNameToValue) {
      return name(Long.toString(value));
    }
    writeDeferredName();
    beforeValue(false);
    sink.writeUtf8(Long.toString(value));
    pathIndices[stackSize - 1]++;
    return this;
  }

  /**
   * Encodes {@code value}.
   *
   * @param value a finite value. May not be {@linkplain Double#isNaN() NaNs} or
   *     {@linkplain Double#isInfinite() infinities}.
   * @return this writer.
   */
  public JsonWriter value(Number value) throws IOException {
    if (value == null) {
      return nullValue();
    }

    String string = value.toString();
    if (!lenient
        && (string.equals("-Infinity") || string.equals("Infinity") || string.equals("NaN"))) {
      throw new IllegalArgumentException("Numeric values must be finite, but was " + value);
    }
    if (promoteNameToValue) {
      return name(string);
    }
    writeDeferredName();
    beforeValue(false);
    sink.writeUtf8(string);
    pathIndices[stackSize - 1]++;
    return this;
  }

  /**
   * Ensures all buffered data is written to the underlying {@link Sink}
   * and flushes that writer.
   */
  public void flush() throws IOException {
    if (stackSize == 0) {
      throw new IllegalStateException("JsonWriter is closed.");
    }
    sink.flush();
  }

  /**
   * Flushes and closes this writer and the underlying {@link Sink}.
   *
   * @throws JsonDataException if the JSON document is incomplete.
   */
  public void close() throws IOException {
    sink.close();

    int size = stackSize;
    if (size > 1 || size == 1 && stack[size - 1] != NONEMPTY_DOCUMENT) {
      throw new IOException("Incomplete document");
    }
    stackSize = 0;
  }

  private void string(String value) throws IOException {
    String[] replacements = REPLACEMENT_CHARS;
    sink.writeByte('"');
    int last = 0;
    int length = value.length();
    for (int i = 0; i < length; i++) {
      char c = value.charAt(i);
      String replacement;
      if (c < 128) {
        replacement = replacements[c];
        if (replacement == null) {
          continue;
        }
      } else if (c == '\u2028') {
        replacement = "\\u2028";
      } else if (c == '\u2029') {
        replacement = "\\u2029";
      } else {
        continue;
      }
      if (last < i) {
        sink.writeUtf8(value, last, i);
      }
      sink.writeUtf8(replacement);
      last = i + 1;
    }
    if (last < length) {
      sink.writeUtf8(value, last, length);
    }
    sink.writeByte('"');
  }

  private void newline() throws IOException {
    if (indent == null) {
      return;
    }

    sink.writeByte('\n');
    for (int i = 1, size = stackSize; i < size; i++) {
      sink.writeUtf8(indent);
    }
  }

  /**
   * Inserts any necessary separators and whitespace before a name. Also
   * adjusts the stack to expect the name's value.
   */
  private void beforeName() throws IOException {
    int context = peek();
    if (context == NONEMPTY_OBJECT) { // first in object
      sink.writeByte(',');
    } else if (context != EMPTY_OBJECT) { // not in an object!
      throw new IllegalStateException("Nesting problem.");
    }
    newline();
    replaceTop(DANGLING_NAME);
  }

  /**
   * Inserts any necessary separators and whitespace before a literal value,
   * inline array, or inline object. Also adjusts the stack to expect either a
   * closing bracket or another element.
   *
   * @param root true if the value is a new array or object, the two values
   *     permitted as top-level elements.
   */
  @SuppressWarnings("fallthrough")
  private void beforeValue(boolean root) throws IOException {
    switch (peek()) {
      case NONEMPTY_DOCUMENT:
        if (!lenient) {
          throw new IllegalStateException(
              "JSON must have only one top-level value.");
        }
        // fall-through
      case EMPTY_DOCUMENT: // first in document
        if (!lenient && !root) {
          throw new IllegalStateException(
              "JSON must start with an array or an object.");
        }
        replaceTop(NONEMPTY_DOCUMENT);
        break;

      case EMPTY_ARRAY: // first in array
        replaceTop(NONEMPTY_ARRAY);
        newline();
        break;

      case NONEMPTY_ARRAY: // another in array
        sink.writeByte(',');
        newline();
        break;

      case DANGLING_NAME: // value for name
        sink.writeUtf8(separator);
        replaceTop(NONEMPTY_OBJECT);
        break;

      default:
        throw new IllegalStateException("Nesting problem.");
    }
  }

  /**
   * Changes the reader to treat the next string value as a name. This is useful for map adapters so
   * that arbitrary type adapters can use {@link #value(String)} to write a name value.
   */
  void promoteNameToValue() throws IOException {
    int context = peek();
    if (context != NONEMPTY_OBJECT && context != EMPTY_OBJECT) {
      throw new IllegalStateException("Nesting problem.");
    }
    promoteNameToValue = true;
  }

  /**
   * Returns a <a href="http://goessner.net/articles/JsonPath/">JsonPath</a> to
   * the current location in the JSON value.
   */
  public String getPath() {
    return JsonScope.getPath(stackSize, stack, pathNames, pathIndices);
  }
}
