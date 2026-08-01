import java.util.*;
import java.util.stream.Collectors;
import java.io.*;

public class PETreeEngine {
    PETreeNode root;
    int tw;
    Map<String, LeafNode> leafMap = new HashMap<>();
    StaticBounds bounds;
    private Map<String, Integer> typeCounters = new HashMap<>();
    private Map<String, List<LeafNode>> typeToLeafMap = new HashMap<>();

    private String refLeafUniqueName;
    private String refOriginalType;

    boolean enableDeferred = true;
    boolean enableStaticBounds = true;
    boolean enableDynamicPruning = true;

    long totalEvents = 0, totalMatches = 0;
    long matchTimeNs = 0, partMatchTimeNs = 0, confTimeNs = 0;
    long peakMemoryBytes = 0;
    long curMemoryBytes = 0;
    double totalConfidence = 0;
    long totalPartialMatches = 0;

    public long getPartMatchTimeNs() { return partMatchTimeNs; }
    public long getConfTimeNs() { return confTimeNs; }
    public long getTotalPartialMatches() { return totalPartialMatches; }

    private Map<List<String>, PartialMatch> globalMatches = new HashMap<>();

    public PETreeEngine(PETreeNode root, int tw) {
        this.root = root;
        this.tw = tw;
        collectLeaves(root, false);
        computeStaticBounds();
        for (LeafNode leaf : leafMap.values()) {
            if (!leaf.isNegation) {
                this.refLeafUniqueName = leaf.uniqueName;
                this.refOriginalType = leaf.originalType;
                break;
            }
        }
    }

    public PETreeEngine(PETreeNode root, int tw, String refLeafUniqueName, String refOriginalType) {
        this.root = root;
        this.tw = tw;
        collectLeaves(root, false);
        computeStaticBounds();
        this.refLeafUniqueName = refLeafUniqueName;
        this.refOriginalType = refOriginalType;
    }

    public void setFlags(boolean def, boolean sb, boolean dp) {
        this.enableDeferred = def;
        this.enableStaticBounds = sb;
        this.enableDynamicPruning = dp;
    }

    private void collectLeaves(PETreeNode node, boolean isNegation) {
        if (node instanceof LeafNode) {
            LeafNode leaf = (LeafNode) node;
            leaf.isNegation = isNegation;
            String base = leaf.originalType;
            int idx = typeCounters.getOrDefault(base, 0);
            String unique = base + "_" + idx;
            typeCounters.put(base, idx + 1);
            leaf.uniqueName = unique;
            leafMap.put(unique, leaf);
            typeToLeafMap.computeIfAbsent(base, k -> new ArrayList<>()).add(leaf);
        } else if (node instanceof SeqNode) {
            for (PETreeNode c : ((SeqNode) node).children)
                collectLeaves(c, isNegation);
        } else if (node instanceof AndNode) {
            for (PETreeNode c : ((AndNode) node).children)
                collectLeaves(c, isNegation);
        } else if (node instanceof NseqNode) {
            NseqNode n = (NseqNode) node;
            collectLeaves(n.first, false);
            collectLeaves(n.neg, true);
            collectLeaves(n.third, false);
        }
    }

    private void computeStaticBounds() {
        List<String> leaves = root.leafTypes();
        List<String[]> edges = new ArrayList<>();
        buildEdges(root, edges);
        bounds = new StaticBounds(leaves, edges);
    }

    private void buildEdges(PETreeNode node, List<String[]> edges) {
        if (node instanceof SeqNode) {
            SeqNode sn = (SeqNode) node;
            for (int a = 0; a < sn.children.size(); a++)
                for (int b = a+1; b < sn.children.size(); b++)
                    for (String x : sn.children.get(a).leafTypes())
                        for (String y : sn.children.get(b).leafTypes())
                            edges.add(new String[]{x, y});
            for (PETreeNode c : sn.children) buildEdges(c, edges);
        } else if (node instanceof AndNode) {
            for (PETreeNode c : ((AndNode) node).children) buildEdges(c, edges);
        } else if (node instanceof NseqNode) {
            NseqNode nn = (NseqNode) node;
            edges.add(new String[]{nn.first.uniqueName, nn.third.uniqueName});
        }
    }

    private PriorityQueue<ActiveRef> activeRefs = new PriorityQueue<>(Comparator.comparingInt(r -> r.matPoint));
    private int currentTime = 0;

    private static class ActiveRef {
        Event ref;
        String refLeafUniqueName;
        int matPoint;
        ActiveRef(Event ref, String leafName, int tw) {
            this.ref = ref;
            this.refLeafUniqueName = leafName;
            this.matPoint = ref.upper + tw - 1;
        }
    }

    public void ingest(Event e) {
        long t0 = System.nanoTime();
        totalEvents++;

        List<LeafNode> leaves = typeToLeafMap.get(e.type);
        if (leaves != null) {
            for (LeafNode leaf : leaves) {
                leaf.rawBuffer.add(e);
            }
        }

        if (e.type.equals(refOriginalType)) {
            activeRefs.add(new ActiveRef(e, refLeafUniqueName, tw));
        }

        if (e.lower > currentTime) currentTime = e.lower;

        if (enableDeferred) {
            while (!activeRefs.isEmpty() && activeRefs.peek().matPoint < currentTime) {
                ActiveRef ar = activeRefs.poll();
                executeMatchForRef(ar);
            }
        } else {
            for (ActiveRef ar : activeRefs) {
                executeMatchForRef(ar);
            }
            while (!activeRefs.isEmpty() && activeRefs.peek().matPoint < currentTime) {
                activeRefs.poll();
            }
        }

        matchTimeNs += System.nanoTime() - t0;
        long mem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        curMemoryBytes = mem;
        if (mem > peakMemoryBytes) peakMemoryBytes = mem;
    }

    public void flush() {
        long t0 = System.nanoTime();
        if (!enableDeferred) {
            totalMatches = globalMatches.size();
            totalConfidence = globalMatches.values().stream().mapToDouble(pm -> pm.confidence).sum();
            matchTimeNs += System.nanoTime() - t0;
            return;
        }
        while (!activeRefs.isEmpty()) {
            ActiveRef ar = activeRefs.poll();
            executeMatchForRef(ar);
        }
        matchTimeNs += System.nanoTime() - t0;
    }

    private void executeMatchForRef(ActiveRef ar) {
        long t0 = System.nanoTime();

        boolean exist_Flag = true;
        for (LeafNode leaf : leafMap.values()) {
            leaf.getFilteredEvents(bounds, ar.refLeafUniqueName, ar.ref, tw, enableStaticBounds);
            if (leaf.buffer.size() == 0 && !leaf.isNegation ) {
                exist_Flag = false;
                break;
            }
        }

        if (exist_Flag) {
            PartialMatch.resetCounter();
            List<PartialMatch> candidates = root.evaluate(tw, enableDynamicPruning);
            totalPartialMatches += PartialMatch.getTotalCreated();
            partMatchTimeNs += System.nanoTime() - t0;

            long t1 = System.nanoTime();
            List<PartialMatch> validMatches = new ArrayList<>();
            for (PartialMatch pm : candidates) {
                double conf = computeConfidence(pm);
                if (conf > 0) {
                    pm.confidence = conf;
                    if (enableDeferred) {
                        validMatches.add(pm);
                        totalConfidence += conf;
                    } else {
                        List<String> sig = pm.events.stream().map(ev -> ev.id).sorted().collect(Collectors.toList());
                        PartialMatch existing = globalMatches.get(sig);
                        if (existing == null || existing.confidence != conf) {
                            globalMatches.put(sig, pm);
                        }
                    }
                }
            }

            confTimeNs += System.nanoTime() - t1;
            if (enableDeferred) {
                totalMatches += validMatches.size();
            }
        }

        int minActiveLower = activeRefs.stream()
            .mapToInt(r -> r.ref.lower - tw + 1)
            .min().orElse(Integer.MAX_VALUE);
        if (!activeRefs.isEmpty()) {
            for (LeafNode leaf : leafMap.values()) {
                leaf.rawBuffer.removeIf(e -> e.upper < minActiveLower);
            }
        } else {
            for (LeafNode leaf : leafMap.values()) {
                leaf.rawBuffer.removeIf(event -> event.upper < (ar.ref.lower - tw + 1));
            }
        }
    }

    public List<PartialMatch> runMatching() {
        long t0 = System.nanoTime();
        List<PartialMatch> matches = root.evaluate(tw);
        matchTimeNs += System.nanoTime() - t0;
        totalMatches += matches.size();
        return matches;
    }

    public double computeConfidence(PartialMatch pm) {
        List<Event> events = pm.events;
        int n = events.size();

        int[] topoOrder = topologicalSort(pm);
        int[] assigned = new int[n];
        Arrays.fill(assigned, -1);
        Set<Integer> occupied = new HashSet<>();

        Map<Event, Integer> idxMap = new HashMap<>();
        for (int i = 0; i < n; i++) idxMap.put(events.get(i), i);

        return enumerateTopo(events, topoOrder, 0, assigned, occupied, pm,
                            Integer.MAX_VALUE, Integer.MIN_VALUE, idxMap);
    }

    private int[] topologicalSort(PartialMatch pm) {
        List<Event> events = pm.events;
        int n = events.size();
        int[] indegree = new int[n];
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());

        Map<Event, Integer> idxMap = new HashMap<>();
        for (int i = 0; i < n; i++) idxMap.put(events.get(i), i);

        for (PartialMatch.OrderPair op : pm.getOrderPairs()) {
            int from = idxMap.getOrDefault(op.before, -1);
            int to = idxMap.getOrDefault(op.after, -1);
            if (from >= 0 && to >= 0) {
                adj.get(from).add(to);
                indegree[to]++;
            }
        }

        Queue<Integer> queue = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            if (indegree[i] == 0) queue.add(i);
        }

        int[] order = new int[n];
        int idx = 0;
        while (!queue.isEmpty()) {
            int v = queue.poll();
            order[idx++] = v;
            for (int u : adj.get(v)) {
                indegree[u]--;
                if (indegree[u] == 0) queue.add(u);
            }
        }
        if (idx != n) {
            for (int i = 0; i < n; i++) order[i] = i;
        }
        return order;
    }

    private double enumerateTopo(List<Event> events, int[] topoOrder, int pos,
                                int[] assigned, Set<Integer> occupied, PartialMatch pm,
                                int globalMin, int globalMax, Map<Event, Integer> idxMap) {
        int n = events.size();
        if (pos == n) {
            if (globalMax - globalMin >= tw) return 0.0;
            double prob = 1.0;
            for (int i = 0; i < n; i++) {
                prob *= events.get(i).prob(assigned[i]);
            }
            for (PartialMatch.NegConstraint nc : pm.getNegConstraints()) {
                int firstIdx = idxMap.getOrDefault(nc.first, -1);
                int thirdIdx = idxMap.getOrDefault(nc.third, -1);
                if (firstIdx < 0 || thirdIdx < 0) continue;
                int tF = assigned[firstIdx];
                int tT = assigned[thirdIdx];
                double negProb = 1.0;
                for (Event neg : nc.negEvents) {
                    double pInside = 0.0;
                    for (int t = tF + 1; t < tT; t++) {
                        pInside += neg.prob(t);
                    }
                    if (pInside >= 1.0) return 0.0;
                    negProb *= (1.0 - pInside);
                }
                prob *= negProb;
            }
            return prob;
        }

        int ei = topoOrder[pos];
        Event e = events.get(ei);
        int lower = pm.vlb.get(e);
        for (PartialMatch.OrderPair op : pm.getOrderPairs()) {
            if (op.after == e) {
                int beforeIdx = idxMap.getOrDefault(op.before, -1);
                if (beforeIdx >= 0 && assigned[beforeIdx] != -1) {
                    lower = Math.max(lower, assigned[beforeIdx] + 1);
                }
            }
        }
        int upper = pm.vub.get(e);

        double sum = 0.0;
        for (int t = lower; t <= upper; t++) {
            if (occupied.contains(t)) continue;
            int newMin = Math.min(globalMin, t);
            int newMax = Math.max(globalMax, t);
            if (newMax - newMin >= tw) continue;

            assigned[ei] = t;
            occupied.add(t);
            sum += enumerateTopo(events, topoOrder, pos + 1, assigned, occupied, pm, newMin, newMax, idxMap);
            occupied.remove(t);
            assigned[ei] = -1;
        }
        return sum;
    }

}
