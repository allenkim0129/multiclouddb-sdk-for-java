// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.
package com.multiclouddb.api.changefeed.internal;

import com.multiclouddb.api.TestJson;
import com.multiclouddb.api.changefeed.ChangeFeedCursor;
import com.multiclouddb.api.changefeed.CursorExpiredException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonSupportTest {
    @Test
    void readerMatchesLegacyScalarsAndNestedValues() throws IOException {
        for (String json : List.of("", " \t\r\n", "null", "true", "false",
                "0", "-0", "1.5", "-0.0", "1e999", "-1e999",
                "2147483648", "-2147483649", "9223372036854775808", "-9223372036854775809",
                "{\"a\":[null,true,1,{\"b\":\"text\"}],\"a\":[2]} trailing invalid text",
                "\"\\uFEFFinside\"", "\"\\ud800\"", "\"\\udfff\"", "\"\ud800\"",
                "[\"\\ud800\",{\"\\udfff\":\"\\ud800\\ud83d\\ude00\"}]",
                "\"\\\\uD800\"", "\"text\" TRUE", "true false", "0 1")) {
            Object legacy = TestJson.read(json, Object.class);
            Object decoded = JsonSupport.read(json);
            assertEquals(legacy, decoded, json);
            if (legacy != null) assertEquals(legacy.getClass(), decoded.getClass(), json);
        }
    }

    @Test
    void readerRejectsLegacyLexicalFailuresIncludingGsonLegacyExtensions() {
        for (String json : List.of("{'v':1}", "{v:1}", "{\"v\":NaN}", "{\"v\":Infinity}",
                "{\"v\":1,}", "[1,]", "{\"v\":01}", "{/*comment*/\"v\":1}",
                "\uFEFF{}", "TRUE", "Null", "[fAlSe]", "{\"v\":TRUE}",
                "\"raw\ncontrol\"", "\"raw\ttab\"", "\"escaped\\'quote\"", "\"escaped\\\nline\"",
                "\"\\uZZZZ\"", "\"unterminated", "[\"raw\u0000control\"]")) {
            assertThrows(UncheckedIOException.class, () -> TestJson.read(json, Object.class), json);
            assertThrows(IOException.class, () -> JsonSupport.read(json), json);
        }
    }

    @Test
    void ignoresEvenMalformedTrailingContentAfterTheFirstRoot() throws IOException {
        for (String trailing : List.of(" TRUE", " \"raw\ncontrol\"", " /* comment", " [", " \uFEFF")) {
            String json = "{\"last\":1,\"last\":2}" + trailing;
            assertEquals(TestJson.read(json, Object.class), JsonSupport.read(json));
        }
    }

    @Test
    void numberLengthAccountingMatchesLegacyAtBoundaries() throws IOException {
        for (String token : List.of("1".repeat(1000), "-" + "1".repeat(1000),
                "0." + "9".repeat(1000), "-0." + "9".repeat(1000),
                "1" + "0".repeat(998) + "e+1", "1e+" + "0".repeat(998) + "1",
                "0e" + "0".repeat(999) + "1", "0.1e" + "0".repeat(997) + "1")) {
            String json = "[" + token + "]";
            assertEquals(TestJson.read(json, Object.class), JsonSupport.read(json));
        }
        for (String token : List.of("1".repeat(1001), "-" + "1".repeat(1001),
                "0." + "9".repeat(1001), "-0." + "9".repeat(1001),
                "1" + "0".repeat(999) + "e+1", "1e+" + "0".repeat(999) + "1",
                "0e" + "0".repeat(1000) + "1", "0.1e" + "0".repeat(998) + "1")) {
            String json = "[" + token + "]";
            assertThrows(UncheckedIOException.class, () -> TestJson.read(json, Object.class));
            assertThrows(IOException.class, () -> JsonSupport.read(json));
        }
    }

    @Test
    void preservesDepthNameAndStringLimits() throws IOException {
        for (boolean object : List.of(false, true)) {
            String open = object ? "{\"x\":" : "[";
            String close = object ? "}" : "]";
            Object decoded = JsonSupport.read(open.repeat(1000) + "null" + close.repeat(1000));
            for (int i = 0; i < 1000; i++) {
                decoded = object ? assertInstanceOf(Map.class, decoded).get("x")
                        : assertInstanceOf(List.class, decoded).get(0);
            }
            assertNull(decoded);
            assertThrows(IOException.class,
                    () -> JsonSupport.read(open.repeat(1001) + "null" + close.repeat(1001)));
        }
        String name = "n".repeat(50_000);
        assertEquals(Map.of(name, 1), JsonSupport.read("{\"" + name + "\":1}"));
        assertThrows(IOException.class, () -> JsonSupport.read("{\"" + name + "n\":1}"));
        String text = "s".repeat(20_000_000);
        assertEquals(text, JsonSupport.read("\"" + text + "\""));
        assertThrows(IOException.class, () -> JsonSupport.read("\"" + text + "s\""));
    }

    @Test
    void envelopeWriterMatchesLegacyEscapingAndOrdering() throws IOException {
        for (String text : List.of("<>&='\u2028\u2029\\u2028", "\u000b\u000e\u001f\\u000b",
                "\u00e9 \ud83d\ude00", "\ud800", "\udfff", "quote\"slash/\\\b\f\n\r\t")) {
            Map<String, Object> payload = Map.of("s", List.of(Map.of("id", "p", "c", text)));
            String json = JsonSupport.write(payload);
            assertEquals(TestJson.write(payload), json);
            assertEquals(payload, JsonSupport.read(json));
        }
    }

    @Test
    void parserFailuresDoNotLeakTokensOrParserDiagnostics() {
        String secret = "private-cursor-payload";
        for (String json : List.of("{\"" + secret + "\":[1,]}", "{\"s\":\"" + secret + "\\x\"}",
                "{\"" + secret + "\":" + "[".repeat(1001) + "0" + "]".repeat(1001) + "}",
                "\uFEFF" + secret, "{\"s\":\"" + secret + "\",\"v\":TRUE}")) {
            String token = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
            CursorExpiredException failure = assertThrows(CursorExpiredException.class,
                    () -> ChangeFeedCursor.fromToken(token));
            assertEquals(CursorTokenCodec.REASON_MALFORMED, failure.error().providerDetails().get("reason"));
            assertEquals("fromToken", failure.error().operation());
            assertNull(failure.getCause());
            assertEquals(0, failure.getSuppressed().length);
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(secret));
            assertFalse(trace.toString().contains(token));
        }
    }
}
