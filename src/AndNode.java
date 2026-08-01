import java.util.*;
import java.util.stream.*;

class AndNode extends PETreeNode {
    List<PETreeNode> children;
    AndNode(List<PETreeNode> children) { this.children = children; }

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
        List<PartialMatch> res = new ArrayList<>();
        List<List<PartialMatch>> childRes = new ArrayList<>();
        for (PETreeNode child : children)
            childRes.add(child.evaluate(tw, enableDynamicPruning));
        for (List<PartialMatch> combo : cartesian(childRes)) {
            if (allDistinctFeasible(combo)) {
                PartialMatch merged = new PartialMatch();
                for (PartialMatch cm : combo) {
                    merged.merge(cm);
                }
                if (enableDynamicPruning) {
                    pruneAnd(merged, tw);
                }
                if (merged.isValid()) res.add(merged);
            }
        }
        return res;
    }

    private boolean allDistinctFeasible(List<PartialMatch> combo) {
        List<Event> all = combo.stream().flatMap(pm -> pm.events.stream()).collect(Collectors.toList());
        all.sort(Comparator.comparingInt(e -> vub(e, combo)));
        Set<Integer> used = new HashSet<>();
        for (Event e : all) {
            int t = vlb(e, combo);
            while (t <= vub(e, combo) && used.contains(t)) t++;
            if (t > vub(e, combo)) return false;
            used.add(t);
        }
        return true;
    }
    private int vlb(Event e, List<PartialMatch> combo) {
        for (PartialMatch pm : combo) if (pm.events.contains(e)) return pm.vlb.get(e);
        return 0;
    }
    private int vub(Event e, List<PartialMatch> combo) {
        for (PartialMatch pm : combo) if (pm.events.contains(e)) return pm.vub.get(e);
        return 0;
    }

    private void pruneAnd(PartialMatch pm, int tw) {
        int minVub = pm.minVub();
        int maxVlb = pm.maxVlb();
        for (Event e : pm.events) {
            int newVub = Math.min(pm.vub.get(e), minVub + tw - 1);
            pm.vub.put(e, newVub);
            int newVlb = Math.max(pm.vlb.get(e), maxVlb - tw + 1);
            pm.vlb.put(e, newVlb);
        }
    }

    private static <T> List<List<T>> cartesian(List<List<T>> lists) {
        List<List<T>> res = new ArrayList<>();
        cartHelper(lists, 0, new ArrayList<>(), res);
        return res;
    }
    private static <T> void cartHelper(List<List<T>> lists, int idx, List<T> cur, List<List<T>> res) {
        if (idx == lists.size()) { res.add(new ArrayList<>(cur)); return; }
        for (T item : lists.get(idx)) {
            cur.add(item);
            cartHelper(lists, idx+1, cur, res);
            cur.remove(cur.size()-1);
        }
    }
}
