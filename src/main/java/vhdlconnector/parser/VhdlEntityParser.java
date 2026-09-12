package vhdlconnector.parser;

import vhdlconnector.model.Direction;
import vhdlconnector.model.GenericParam;
import vhdlconnector.model.Port;
import vhdlconnector.model.VhdlEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts entity name / generics / ports from a VHDL source file. Only the
 * entity declaration is parsed (architecture bodies are ignored); this is
 * enough to instantiate and wire the entity elsewhere.
 */
public class VhdlEntityParser {

    private static final Pattern ENTITY_START =
            Pattern.compile("\\bentity\\s+(\\w+)\\s+is\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENTITY_END =
            Pattern.compile("\\bend\\b(\\s+entity)?(\\s+\\w+)?\\s*;", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_KW = Pattern.compile("\\bgeneric\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern PORT_KW = Pattern.compile("\\bport\\s*\\(", Pattern.CASE_INSENSITIVE);

    public List<VhdlEntity> parseFile(java.io.File file) throws IOException {
        String content = new String(Files.readAllBytes(file.toPath()));
        List<VhdlEntity> entities = parseSource(content);
        for (VhdlEntity e : entities) e.sourceFile = file.getAbsolutePath();
        return entities;
    }

    public List<VhdlEntity> parseSource(String rawSource) {
        String source = stripComments(rawSource);
        List<VhdlEntity> result = new ArrayList<>();

        Matcher startMatcher = ENTITY_START.matcher(source);
        int searchFrom = 0;
        while (startMatcher.find(searchFrom)) {
            String name = startMatcher.group(1);
            int bodyStart = startMatcher.end();

            Matcher endMatcher = ENTITY_END.matcher(source);
            if (!endMatcher.find(bodyStart)) break; // malformed, stop
            int bodyEnd = endMatcher.start();

            String body = source.substring(bodyStart, bodyEnd);
            VhdlEntity entity = new VhdlEntity(name);

            String genericClause = extractParenClause(body, GENERIC_KW);
            if (genericClause != null) {
                for (String decl : splitTopLevel(genericClause, ';')) {
                    parseGenericDecl(decl, entity);
                }
            }

            String portClause = extractParenClause(body, PORT_KW);
            if (portClause != null) {
                for (String decl : splitTopLevel(portClause, ';')) {
                    parsePortDecl(decl, entity);
                }
            }

            result.add(entity);
            searchFrom = endMatcher.end();
        }
        return result;
    }

    private void parseGenericDecl(String decl, VhdlEntity entity) {
        decl = decl.trim();
        if (decl.isEmpty()) return;
        int colon = topLevelIndexOf(decl, ':');
        if (colon < 0) return;
        String namesPart = decl.substring(0, colon).trim();
        String rest = decl.substring(colon + 1).trim();

        String defaultValue = null;
        int assignIdx = topLevelIndexOf(rest, ":=");
        String type = rest;
        if (assignIdx >= 0) {
            type = rest.substring(0, assignIdx).trim();
            defaultValue = rest.substring(assignIdx + 2).trim();
        }

        for (String n : splitTopLevel(namesPart, ',')) {
            n = n.trim();
            if (!n.isEmpty()) entity.generics.add(new GenericParam(n, type, defaultValue));
        }
    }

    private void parsePortDecl(String decl, VhdlEntity entity) {
        decl = decl.trim();
        if (decl.isEmpty()) return;
        int colon = topLevelIndexOf(decl, ':');
        if (colon < 0) return;
        String namesPart = decl.substring(0, colon).trim();
        String rest = decl.substring(colon + 1).trim();

        // strip a trailing default value (:= ...), not needed for ports but may be present
        int assignIdx = topLevelIndexOf(rest, ":=");
        if (assignIdx >= 0) rest = rest.substring(0, assignIdx).trim();

        // first token is the direction
        int sp = firstWhitespace(rest);
        String dirToken;
        String type;
        if (sp < 0) {
            dirToken = rest;
            type = "std_logic";
        } else {
            dirToken = rest.substring(0, sp);
            type = rest.substring(sp + 1).trim();
        }
        Direction direction;
        switch (dirToken.trim().toLowerCase()) {
            case "in": direction = Direction.IN; break;
            case "out": direction = Direction.OUT; break;
            case "inout": direction = Direction.INOUT; break;
            case "buffer": direction = Direction.BUFFER; break;
            case "linkage": direction = Direction.LINKAGE; break;
            default:
                // no explicit direction token found (shouldn't normally happen for ports)
                direction = Direction.IN;
                type = rest;
        }

        for (String n : splitTopLevel(namesPart, ',')) {
            n = n.trim();
            if (!n.isEmpty()) entity.ports.add(new Port(n, direction, type));
        }
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    /** Finds "generic (" / "port (" via kwPattern and returns the balanced-paren content, or null. */
    private String extractParenClause(String body, Pattern kwPattern) {
        Matcher m = kwPattern.matcher(body);
        if (!m.find()) return null;
        int openParen = m.end() - 1; // position of '('
        int closeParen = findMatchingParen(body, openParen);
        if (closeParen < 0) return null;
        return body.substring(openParen + 1, closeParen);
    }

    private static int findMatchingParen(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** Splits on delim at paren-depth 0 only. */
    private static List<String> splitTopLevel(String s, char delim) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == delim && depth == 0) {
                parts.add(s.substring(last, i));
                last = i + 1;
            }
        }
        parts.add(s.substring(last));
        return parts;
    }

    private static int topLevelIndexOf(String s, char ch) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ch && depth == 0) return i;
        }
        return -1;
    }

    private static int topLevelIndexOf(String s, String token) {
        int depth = 0;
        for (int i = 0; i <= s.length() - token.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && s.startsWith(token, i)) return i;
        }
        return -1;
    }

    private static String stripComments(String source) {
        StringBuilder sb = new StringBuilder();
        int n = source.length();
        for (int i = 0; i < n; i++) {
            char c = source.charAt(i);
            if (c == '-' && i + 1 < n && source.charAt(i + 1) == '-') {
                while (i < n && source.charAt(i) != '\n') i++;
                if (i < n) sb.append('\n');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
