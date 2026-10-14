package com.example.mixauth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（纯 Java 实现，无第三方依赖）。
 * 支持 object / array / string / number / boolean / null，字符串支持反斜杠u加4位十六进制的转义。
 */
public final class Json {

    private Json() {
    }

    /** 解析 JSON 文本，顶层必须是对象。 */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        java.lang.Object v = p.parseValue();
        p.skipWs();
        if (!p.eof()) {
            throw new IllegalArgumentException("JSON 末尾存在多余内容: " + p.restPreview());
        }
        if (!(v instanceof Object o)) {
            throw new IllegalArgumentException("JSON 顶层不是对象: " + text.substring(0, Math.min(60, text.length())));
        }
        return o;
    }

    /** JSON 对象 */
    public static final class Object {
        private final Map<String, java.lang.Object> map = new LinkedHashMap<>();

        void put(String k, java.lang.Object v) {
            map.put(k, v);
        }

        public boolean has(String key) {
            return map.containsKey(key);
        }

        public java.lang.Object get(String key) {
            return map.get(key);
        }

        public String optString(String key, String def) {
            java.lang.Object v = map.get(key);
            if (v instanceof String s) {
                return s;
            }
            if (v != null && !(v instanceof Json.Null)) {
                return String.valueOf(v);
            }
            return def;
        }

        public Object optObject(String key) {
            java.lang.Object v = map.get(key);
            return v instanceof Object o ? o : null;
        }

        public boolean optBoolean(String key, boolean def) {
            java.lang.Object v = map.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof String s) {
                return Boolean.parseBoolean(s);
            }
            return def;
        }

        public int size() {
            return map.size();
        }

        public Map<String, java.lang.Object> asMap() {
            return map;
        }

        @Override
        public String toString() {
            return toPrettyJson(map, 0);
        }
    }

    /** JSON 数组 */
    public static final class Array {
        private final List<java.lang.Object> list = new ArrayList<>();

        void add(java.lang.Object v) {
            list.add(v);
        }

        public int size() {
            return list.size();
        }

        public java.lang.Object get(int i) {
            return list.get(i);
        }

        @Override
        public String toString() {
            return toPrettyJson(list, 0);
        }
    }

    /** null 占位类型，区别于普通 null 引用 */
    public static final class Null {
        @Override
        public String toString() {
            return "null";
        }
    }

    public static final Null NULL_VALUE = new Null();

    // ============================ 解析器实现 ============================

    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return i >= s.length();
        }

        void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        String restPreview() {
            int end = Math.min(s.length(), i + 20);
            return s.substring(i, end);
        }

        char peek() {
            if (eof()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return s.charAt(i);
        }

        char next() {
            char c = peek();
            i++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("JSON 语法错误，期望 '" + c + "'，位置 " + (i - 1));
            }
        }

        java.lang.Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{' -> {
                    return parseObject();
                }
                case '[' -> {
                    return parseArray();
                }
                case '"' -> {
                    return parseString();
                }
                case 't' -> {
                    expectWord("true");
                    return Boolean.TRUE;
                }
                case 'f' -> {
                    expectWord("false");
                    return Boolean.FALSE;
                }
                case 'n' -> {
                    expectWord("null");
                    return NULL_VALUE;
                }
                default -> {
                    return parseNumber();
                }
            }
        }

        void expectWord(String w) {
            for (int k = 0; k < w.length(); k++) {
                if (next() != w.charAt(k)) {
                    throw new IllegalArgumentException("JSON 语法错误，位置 " + (i - 1));
                }
            }
        }

        Object parseObject() {
            expect('{');
            Object obj = new Object();
            skipWs();
            if (peek() == '}') {
                next();
                return obj;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                obj.put(key, parseValue());
                skipWs();
                char c = next();
                if (c == '}') {
                    return obj;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 语法错误，期望 ',' 或 '}'，位置 " + (i - 1));
                }
            }
        }

        Array parseArray() {
            expect('[');
            Array arr = new Array();
            skipWs();
            if (peek() == ']') {
                next();
                return arr;
            }
            while (true) {
                arr.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return arr;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("JSON 语法错误，期望 ',' 或 ']'，位置 " + (i - 1));
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append((char) Integer.parseInt(readHex(4), 16));
                        default -> throw new IllegalArgumentException("非法转义: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        String readHex(int n) {
            StringBuilder sb = new StringBuilder(n);
            for (int k = 0; k < n; k++) {
                sb.append(next());
            }
            return sb.toString();
        }

        java.lang.Object parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (!eof() && (Character.isDigit(peek()) || peek() == '.' || peek() == 'e'
                    || peek() == 'E' || peek() == '+' || peek() == '-')) {
                i++;
            }
            String num = s.substring(start, i).trim();
            if (num.isEmpty()) {
                throw new IllegalArgumentException("非法数字，位置 " + start);
            }
            try {
                if (num.indexOf('.') >= 0 || num.indexOf('e') >= 0 || num.indexOf('E') >= 0) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("非法数字: " + num);
            }
        }
    }

    // ============================ 输出辅助 ============================

    /** 字符串字面量转义（带双引号），用于拼装请求体 JSON。 */
    public static String quoteStr(String s) {
        return quote(s);
    }

    /** 生成带缩进的可读 JSON 字符串 */
    public static String toPrettyJson(java.lang.Object o, int indent) {
        if (o instanceof Object obj) {
            return prettyObject(obj.asMap(), indent);
        }
        if (o instanceof Array arr) {
            return prettyArray(arr, indent);
        }
        if (o == null || o instanceof Null) {
            return "null";
        }
        if (o instanceof String s) {
            return quote(s);
        }
        return String.valueOf(o);
    }

    private static String prettyObject(Map<String, java.lang.Object> map, int indent) {
        if (map.isEmpty()) {
            return "{}";
        }
        String pad = "  ".repeat(indent);
        String padIn = "  ".repeat(indent + 1);
        StringBuilder sb = new StringBuilder("{\n");
        int n = 0;
        for (Map.Entry<String, java.lang.Object> e : map.entrySet()) {
            if (n++ > 0) {
                sb.append(",\n");
            }
            sb.append(padIn).append(quote(e.getKey()))
              .append(": ").append(toPrettyJson(e.getValue(), indent + 1));
        }
        sb.append('\n').append(pad).append('}');
        return sb.toString();
    }

    private static String prettyArray(Array arr, int indent) {
        if (arr.size() == 0) {
            return "[]";
        }
        String pad = "  ".repeat(indent);
        String padIn = "  ".repeat(indent + 1);
        StringBuilder sb = new StringBuilder("[\n");
        for (int k = 0; k < arr.size(); k++) {
            if (k > 0) {
                sb.append(",\n");
            }
            sb.append(padIn).append(toPrettyJson(arr.get(k), indent + 1));
        }
        sb.append('\n').append(pad).append(']');
        return sb.toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}