package app.ffalcontrol;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON parser: rejects duplicate keys, trailing input and lenient JSON extensions. */
final class StrictJson {
    private final String source;
    private int position;

    private StrictJson(String source) { this.source = source; }

    static Object parse(String source) {
        return parse(source, 65536);
    }

    static Object parse(String source, int maximumCharacters) {
        if (source == null || source.length() > maximumCharacters)
            throw new IllegalArgumentException("JSON input missing or too large");
        StrictJson parser = new StrictJson(source);
        Object value = parser.value(0);
        parser.space();
        if (parser.position != source.length()) parser.fail("Trailing JSON input");
        return value;
    }

    private Object value(int depth) {
        if (depth > 32) fail("JSON nesting too deep");
        space();
        if (position >= source.length()) fail("Missing JSON value");
        char next = source.charAt(position);
        if (next == '"') return string();
        if (next == '{') {
            position++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            space();
            if (take('}')) return map;
            do {
                space();
                if (position >= source.length() || source.charAt(position) != '"') fail("Object key must be quoted");
                String key = string();
                if (map.containsKey(key)) fail("Duplicate JSON key: " + key);
                space(); need(':');
                map.put(key, value(depth + 1));
                space();
                if (take('}')) return map;
                need(',');
            } while (true);
        }
        if (next == '[') {
            position++;
            List<Object> list = new ArrayList<Object>();
            space();
            if (take(']')) return list;
            do {
                list.add(value(depth + 1));
                space();
                if (take(']')) return list;
                need(',');
            } while (true);
        }
        if (source.startsWith("true", position)) { position += 4; return Boolean.TRUE; }
        if (source.startsWith("false", position)) { position += 5; return Boolean.FALSE; }
        if (source.startsWith("null", position)) { position += 4; return null; }
        int begin = position;
        take('-');
        if (take('0')) {
            if (position < source.length() && digit(source.charAt(position))) fail("Leading zero");
        } else {
            if (position >= source.length() || source.charAt(position) < '1' || source.charAt(position) > '9') fail("Invalid JSON value");
            while (position < source.length() && digit(source.charAt(position))) position++;
        }
        if (take('.')) {
            int digits = position;
            while (position < source.length() && digit(source.charAt(position))) position++;
            if (digits == position) fail("Missing fractional digits");
        }
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            int digits = position;
            while (position < source.length() && digit(source.charAt(position))) position++;
            if (digits == position) fail("Missing exponent digits");
        }
        if (position - begin > 128) fail("Number too long");
        return new BigDecimal(source.substring(begin, position)).stripTrailingZeros();
    }

    private String string() {
        need('"');
        StringBuilder out = new StringBuilder();
        while (position < source.length()) {
            char next = source.charAt(position++);
            if (next == '"') return out.toString();
            if (next < 0x20) fail("Unescaped control character");
            if (next != '\\') { out.append(next); continue; }
            if (position >= source.length()) fail("Incomplete string escape");
            char escaped = source.charAt(position++);
            switch (escaped) {
                case '"': case '\\': case '/': out.append(escaped); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (position + 4 > source.length()) fail("Incomplete Unicode escape");
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        char hex = source.charAt(position++);
                        int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                        if (digit < 0) fail("Invalid Unicode escape");
                        code = code * 16 + digit;
                    }
                    out.append((char) code); break;
                default: fail("Invalid string escape");
            }
        }
        fail("Unterminated string");
        return null;
    }

    private void space() {
        while (position < source.length()) {
            char c = source.charAt(position);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
            position++;
        }
    }
    private boolean take(char c) {
        if (position < source.length() && source.charAt(position) == c) { position++; return true; }
        return false;
    }
    private void need(char c) { if (!take(c)) fail("Expected " + c); }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private void fail(String message) { throw new IllegalArgumentException(message + " at offset " + position); }
}
