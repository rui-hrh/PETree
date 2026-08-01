import java.util.*;
import java.io.*;

public class Utils {

    public static PETreeNode parsePattern(String s) {
        s = s.trim();
        if (s.startsWith("SEQ(")) {
            String content = s.substring(4, s.length() - 1);
            List<String> args = splitArgs(content);
            List<PETreeNode> children = new ArrayList<>();
            for (String arg : args) {
                children.add(parsePattern(arg));
            }
            return new SeqNode(children);
        } else if (s.startsWith("AND(")) {
            String content = s.substring(4, s.length() - 1);
            List<String> args = splitArgs(content);
            List<PETreeNode> children = new ArrayList<>();
            for (String arg : args) {
                children.add(parsePattern(arg));
            }
            return new AndNode(children);
        } else if (s.startsWith("NSEQ(")) {
            String content = s.substring(5, s.length() - 1);
            List<String> args = splitArgs(content);
            if (args.size() != 3) {
                throw new IllegalArgumentException("NSEQ requires exactly 3 arguments: " + s);
            }
            PETreeNode first = parsePattern(args.get(0));
            PETreeNode neg = parsePattern(args.get(1));
            PETreeNode third = parsePattern(args.get(2));
            if (!(first instanceof LeafNode) || !(neg instanceof LeafNode) || !(third instanceof LeafNode)) {
                throw new IllegalArgumentException("NSEQ arguments must be leaf nodes: " + s);
            }
            ((LeafNode) neg).isNegation = true;
            return new NseqNode((LeafNode) first, (LeafNode) neg, (LeafNode) third);
        } else {
            return new LeafNode(s);
        }
    }

    private static List<String> splitArgs(String s) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString().trim());
        }
        return args;
    }
}
