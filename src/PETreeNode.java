import java.util.*;

abstract class PETreeNode {
    abstract List<PartialMatch> evaluate(int tw, boolean enableDynamicPruning);
    abstract List<PartialMatch> evaluate(int tw);
    abstract int slots();
    abstract List<String> leafTypes();

    protected void applyWindowPruning(PartialMatch pm, int tw) {
        if (pm.events.isEmpty()) return;
        int minVub = pm.minVub();
        int maxVlb = pm.maxVlb();
        for (Event e : pm.events) {
            int newVub = Math.min(pm.vub.get(e), minVub + tw - 1);
            int newVlb = Math.max(pm.vlb.get(e), maxVlb - tw + 1);
            pm.vub.put(e, newVub);
            pm.vlb.put(e, newVlb);
        }
    }
}
