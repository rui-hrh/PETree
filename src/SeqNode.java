import java.util.*;
import java.util.stream.*;

class SeqNode extends PETreeNode {
    List<PETreeNode> children;
    SeqNode(List<PETreeNode> children) { this.children = children; }

    @Override int slots() { return children.stream().mapToInt(PETreeNode::slots).sum(); }
    @Override List<String> leafTypes() {
        return children.stream().flatMap(c -> c.leafTypes().stream()).collect(Collectors.toList());
    }

    @Override
    List<PartialMatch> evaluate(int tw) {
        return evaluate(tw, true);
    }

    @Override
    List<PartialMatch> evaluate(int tw, boolean enableDynamicPruning) {
        List<PartialMatch> cur = new ArrayList<>();
        cur.add(new PartialMatch());
        for (PETreeNode child : children) {
            List<PartialMatch> childRes = child.evaluate(tw, enableDynamicPruning);
            List<PartialMatch> next = new ArrayList<>();
            for (PartialMatch pm : cur) {
                for (PartialMatch cand : childRes) {
                    if (pm.maxVlb() < cand.minVub()) {
                        PartialMatch merged = new PartialMatch(pm);
                        merged.merge(cand);
                        for (Event a : pm.events) {
                            for (Event b : cand.events) {
                                merged.addOrder(a, b);
                            }
                        }
                        if (enableDynamicPruning) {
                            pruneSeqAcross(merged, pm.events, cand.events);
                            applyWindowPruning(merged, tw);
                        }
                        if (merged.isValid())
                            next.add(merged);
                    }
                }
            }
            cur = next;
        }
        return cur;
    }

    private void pruneSeqAcross(PartialMatch merged, List<Event> left, List<Event> right) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Event a : left) {
                for (Event b : right) {
                    int oldVubA = merged.vub.get(a);
                    merged.vub.put(a, Math.min(merged.vub.get(a), merged.vub.get(b) - 1));
                    if (merged.vub.get(a) != oldVubA) changed = true;

                    int oldVlbB = merged.vlb.get(b);
                    merged.vlb.put(b, Math.max(merged.vlb.get(b), merged.vlb.get(a) + 1));
                    if (merged.vlb.get(b) != oldVlbB) changed = true;
                }
            }
        }
    }
}
