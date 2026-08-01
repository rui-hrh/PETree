import java.util.*;

class LeafNode extends PETreeNode {
    String originalType;
    String uniqueName;
    boolean isNegation = false;
    List<Event> rawBuffer = new ArrayList<>();

    static class EventEntry {
        Event event;
        int lb, ub;
        EventEntry(Event e, int lb, int ub) { this.event = e; this.lb = lb; this.ub = ub; }
    }
    List<EventEntry> buffer = new ArrayList<>();

    LeafNode(String type) {
        this.originalType = type;
    }

    void setUniqueName(String name) { this.uniqueName = name; }

    @Override
    int slots() { return 1; }

    @Override
    List<String> leafTypes() {
        return Collections.singletonList(uniqueName);
    }

    @Override
    List<PartialMatch> evaluate(int tw) {
        return evaluate(tw, true);
    }

    @Override
    List<PartialMatch> evaluate(int tw, boolean enableDynamicPruning) {

        List<PartialMatch> res = new ArrayList<>();
        for (EventEntry entry : buffer) {
            PartialMatch np = new PartialMatch();
            np.add(entry.event, entry.lb, entry.ub);
            res.add(np);
        }
        return res;
    }

    List<EventEntry> getFilteredEvents(StaticBounds bounds, String refLeafUniqueName,
                                       Event refEvent, int tw, boolean enableStaticBounds) {
        List<EventEntry> filtered = new ArrayList<>();
        boolean isRefLeaf = uniqueName.equals(refLeafUniqueName);
        for (Event e : rawBuffer) {
            if (isRefLeaf) {
                if (e == refEvent) {
                    filtered.add(new EventEntry(e, e.lower, e.upper));
                }
                continue;
            }
            if (!enableStaticBounds) {
                filtered.add(new EventEntry(e, e.lower, e.upper));
                continue;
            }
            if (isNegation) {
                filtered.add(new EventEntry(e, e.lower, e.upper));
            } else {
                int dMin = bounds.deltaMin(refLeafUniqueName, uniqueName, tw);
                int dMax = bounds.deltaMax(refLeafUniqueName, uniqueName, tw);
                int minB = Math.max(e.lower, refEvent.lower + dMin);
                int maxB = Math.min(e.upper, refEvent.upper + dMax);
                if (e.upper < minB || e.lower > maxB) continue;
                filtered.add(new EventEntry(e, minB, maxB));
            }
        }
        buffer = filtered;
        return filtered;
    }
}
