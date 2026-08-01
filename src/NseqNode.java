import java.util.*;

class NseqNode extends PETreeNode {
    LeafNode first, neg, third;
    NseqNode(LeafNode first, LeafNode neg, LeafNode third) {
        this.first = first; this.neg = neg; this.third = third;
    }
    @Override int slots() { return 2; }
    @Override List<String> leafTypes() { return Arrays.asList(first.uniqueName, third.uniqueName); }

    @Override
    List<PartialMatch> evaluate(int tw) {
        return evaluate(tw, true);
    }

    @Override
    List<PartialMatch> evaluate(int tw, boolean enableDynamicPruning) {
        List<PartialMatch> res = new ArrayList<>();

        List<PartialMatch> fCands = first.evaluate(tw, enableDynamicPruning);
        List<PartialMatch> tCands = third.evaluate(tw, enableDynamicPruning);
        for (PartialMatch fm : fCands) {
            Event fe = fm.events.get(0);
            for (PartialMatch tm : tCands) {
                Event te = tm.events.get(0);
                if (fm.vlb.get(fe) < tm.vub.get(te)) {
                    boolean impossible = false;
                    for (LeafNode.EventEntry ne : neg.buffer) {
                        if (ne.lb > fm.vub.get(fe) && ne.ub < tm.vlb.get(te)) {
                            impossible = true;
                            break;
                        }
                    }
                    if (impossible) continue;

                    List<Event> relevantNeg = new ArrayList<>();
                    for (LeafNode.EventEntry ne : neg.buffer) {
                        if (ne.lb < tm.vub.get(te) && ne.ub > fm.vlb.get(fe)) {
                            relevantNeg.add(ne.event);
                        }
                    }

                    PartialMatch merged = new PartialMatch();
                    merged.merge(fm);
                    merged.merge(tm);
                    merged.addOrder(fe, te);
                    merged.addNegConstraint(fe, te, relevantNeg);
                    if (enableDynamicPruning) {
                        pruneNseq(merged, fe, te);
                        applyWindowPruning(merged, tw);
                    }
                    if (merged.isValid()) res.add(merged);
                }
            }
        }

        return res;
    }

    private void pruneNseq(PartialMatch pm, Event fe, Event te) {
        pm.vub.put(fe, Math.min(pm.vub.get(fe), pm.vub.get(te) - 1));
        pm.vlb.put(te, Math.max(pm.vlb.get(te), pm.vlb.get(fe) + 1));
    }
}
