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

package org.openqa.selenium.firefox;

import static org.openqa.selenium.json.Json.MAP_TYPE;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.io.CharStreams;
import com.google.common.io.Closeables;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.json.Json;

class Preferences {

  /**
   * The maximum amount of time scripts should be permitted to run. The user may increase this
   * timeout, but may not set it below the default value.
   */
  private static final String MAX_SCRIPT_RUN_TIME_KEY = "dom.max_script_run_time";

  private static final int DEFAULT_MAX_SCRIPT_RUN_TIME = 30;

  /**
   * This pattern is used to parse preferences in user.js. It is intended to match all preference
   * lines in the format generated by Firefox; it won't necessarily match all possible lines that
   * Firefox will parse.
   *
   * <p>e.g. if you have a line with extra spaces after the end-of-line semicolon, this pattern will
   * not match that line because Firefox never generates lines like that.
   */
  private static final Pattern PREFERENCE_PATTERN =
      Pattern.compile("user_pref\\(\"([^\"]+)\", (\"?.+?\"?)\\);");

  private Map<String, Object> immutablePrefs = new HashMap<>();
  private Map<String, Object> allPrefs = new HashMap<>();

  public Preferences() {}

  public Preferences(Reader defaults) {
    readDefaultPreferences(defaults);
  }

  public Preferences(Reader defaults, File userPrefs) {
    readDefaultPreferences(defaults);
    try (Reader reader = Files.newBufferedReader(userPrefs.toPath(), Charset.defaultCharset())) {
      readPreferences(reader);
    } catch (IOException e) {
      throw new WebDriverException(e);
    }
  }

  public Preferences(File userPrefs) {
    readUserPrefs(userPrefs);
  }

  public Preferences(Reader defaults, Reader reader) {
    readDefaultPreferences(defaults);
    try {
      readPreferences(reader);
    } catch (IOException e) {
      throw new WebDriverException(e);
    } finally {
      try {
        Closeables.close(reader, true);
      } catch (IOException ignored) {
      }
    }
  }

  private void readUserPrefs(File userPrefs) {
    try (Reader reader = Files.newBufferedReader(userPrefs.toPath(), Charset.defaultCharset())) {
      readPreferences(reader);
    } catch (IOException e) {
      throw new WebDriverException(e);
    }
  }

  private void readDefaultPreferences(Reader defaultsReader) {
    try {
      String rawJson = CharStreams.toString(defaultsReader);
      Map<String, Object> map = new Json().toType(rawJson, MAP_TYPE);

      Map<String, Object> frozen = (Map<String, Object>) map.get("frozen");
      frozen.forEach(
          (key, value) -> {
            if (value instanceof Long) {
              value = ((Long) value).intValue();
            }
            setPreference(key, value);
            immutablePrefs.put(key, value);
          });

      Map<String, Object> mutable = (Map<String, Object>) map.get("mutable");
      mutable.forEach(this::setPreference);

    } catch (IOException e) {
      throw new WebDriverException(e);
    }
  }

  public void setPreference(String key, Object value) {
    if (value instanceof String) {
      if (isStringified((String) value)) {
        throw new IllegalArgumentException(
            String.format("Preference values must be plain strings: %s: %s", key, value));
      }
      allPrefs.put(key, value);
    } else if (value instanceof Number) {
      allPrefs.put(key, ((Number) value).intValue());
    } else {
      allPrefs.put(key, value);
    }
  }

  private void readPreferences(Reader reader) throws IOException {
    BufferedReader allLines = new BufferedReader(reader);
    String line = allLines.readLine();
    while (line != null) {
      Matcher matcher = PREFERENCE_PATTERN.matcher(line);
      if (matcher.matches()) {
        allPrefs.put(matcher.group(1), preferenceAsValue(matcher.group(2)));
      }
      line = allLines.readLine();
    }
  }

  public void addTo(Preferences prefs) {
    // TODO(simon): Stop being lazy
    prefs.allPrefs.putAll(allPrefs);
  }

  public void writeTo(Writer writer) throws IOException {
    for (Map.Entry<String, Object> pref : allPrefs.entrySet()) {
      writer.append("user_pref(\"").append(pref.getKey()).append("\", ");
      writer.append(valueAsPreference(pref.getValue()));
      writer.append(");\n");
    }
  }

  private String valueAsPreference(Object value) {
    if (value instanceof String) {
      return "\"" + escapeValueAsPreference((String) value) + "\"";
    }
    return escapeValueAsPreference(String.valueOf(value));
  }

  private String escapeValueAsPreference(String value) {
    return value.replaceAll("\\\\", "\\\\\\\\").replaceAll("\"", "\\\\\"");
  }

  private Object preferenceAsValue(String toConvert) {
    if (toConvert.startsWith("\"") && toConvert.endsWith("\"")) {
      return toConvert.substring(1, toConvert.length() - 1).replaceAll("\\\\\\\\", "\\\\");
    }

    if ("false".equals(toConvert) || "true".equals(toConvert)) {
      return Boolean.parseBoolean(toConvert);
    }

    try {
      return Integer.parseInt(toConvert);
    } catch (NumberFormatException e) {
      throw new WebDriverException(e);
    }
  }

  @VisibleForTesting
  protected Object getPreference(String key) {
    return allPrefs.get(key);
  }

  private boolean isStringified(String value) {
    // Assume we a string is stringified (i.e. wrapped in " ") when
    // the first character == " and the last character == "
    return value.startsWith("\"") && value.endsWith("\"");
  }

  private void checkPreference(String key, Object value) {
    Require.nonNull("Key", key);
    Require.nonNull("Value", value);
    Require.stateCondition(
        !immutablePrefs.containsKey(key)
            || (immutablePrefs.containsKey(key) && value.equals(immutablePrefs.get(key))),
        "Preference %s may not be overridden: frozen value=%s, requested value=%s",
        key,
        immutablePrefs.get(key),
        value);
    if (MAX_SCRIPT_RUN_TIME_KEY.equals(key)) {
      int n;
      if (value instanceof String) {
        n = Integer.parseInt((String) value);
      } else if (value instanceof Integer) {
        n = (Integer) value;
      } else {
        throw new IllegalStateException(
            String.format(
                "%s value must be a number: %s",
                MAX_SCRIPT_RUN_TIME_KEY, value.getClass().getName()));
      }
      Require.stateCondition(
          n == 0 || n >= DEFAULT_MAX_SCRIPT_RUN_TIME,
          "%s must be == 0 || >= %s",
          MAX_SCRIPT_RUN_TIME_KEY,
          DEFAULT_MAX_SCRIPT_RUN_TIME);
    }
  }
}
