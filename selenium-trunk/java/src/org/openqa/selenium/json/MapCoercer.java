// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.openqa.selenium.json;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collector;

class MapCoercer<T> extends TypeCoercer<T> {

  private final Class<T> stereotype;
  private final JsonTypeCoercer coercer;
  private final Collector<Map.Entry<?, ?>, ?, ? extends T> collector;

  public MapCoercer(
      Class<T> stereotype,
      JsonTypeCoercer coercer,
      Collector<Map.Entry<?, ?>, ?, ? extends T> collector) {
    this.stereotype = stereotype;
    this.coercer = coercer;
    this.collector = collector;
  }

  @Override
  public boolean test(Class<?> type) {
    return stereotype.isAssignableFrom(type);
  }

  @Override
  public BiFunction<JsonInput, PropertySetting, T> apply(Type type) {
    Type keyType;
    Type valueType;

    if (type instanceof ParameterizedType) {
      ParameterizedType pt = (ParameterizedType) type;
      keyType = pt.getActualTypeArguments()[0];
      valueType = pt.getActualTypeArguments()[1];
    } else if (type instanceof Class) {
      keyType = Object.class;
      valueType = Object.class;
    } else {
      throw new IllegalArgumentException("Unhandled type: " + type.getClass());
    }

    return (jsonInput, setting) -> {
      jsonInput.beginObject();
      T toReturn =
          new JsonInputIterator(jsonInput)
              .asStream()
              .map(
                  in -> {
                    Object key = coercer.coerce(in, keyType, setting);
                    Object value = coercer.coerce(in, valueType, setting);

                    return new AbstractMap.SimpleImmutableEntry<>(key, value);
                  })
              .collect(collector);
      jsonInput.endObject();

      return toReturn;
    };
  }
}
