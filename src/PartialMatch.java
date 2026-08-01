import java.util.*;
import java.util.stream.*;

class PartialMatch {
    static long totalCreated = 0;

    List<Event> events = new ArrayList<>();
    Map<Event, Integer> vlb = new HashMap<>();
    Map<Event, Integer> vub = new HashMap<>();
    double confidence = 0.0;

    public static class OrderPair {
        Event before, after;
        public OrderPair(Event before, Event after) {
            this.before = before;
            this.after = after;
        }
    }

    public static class NegConstraint {
        Event first, third;
        List<Event> negEvents;
        public NegConstraint(Event first, Event third, List<Event> negEvents) {
            this.first = first;
            this.third = third;
            this.negEvents = negEvents;
        }
    }

    private List<OrderPair> orderPairs = new ArrayList<>();
    private List<NegConstraint> negConstraints = new ArrayList<>();

    PartialMatch() {
        totalCreated++;
    }

    PartialMatch(PartialMatch other) {
        totalCreated++;
        events.addAll(other.events);
        vlb.putAll(other.vlb);
        vub.putAll(other.vub);
        orderPairs.addAll(other.orderPairs);
        negConstraints.addAll(other.negConstraints);
    }

    public static long getTotalCreated() { return totalCreated; }
    public static void resetCounter() { totalCreated = 0; }

    void add(Event e, int lb, int ub) {
        events.add(e);
        vlb.put(e, lb);
        vub.put(e, ub);
    }

    public void addOrder(Event before, Event after) {
        orderPairs.add(new OrderPair(before, after));
    }

    public void addNegConstraint(Event first, Event third, List<Event> negEvents) {
        negConstraints.add(new NegConstraint(first, third, negEvents));
    }

    public List<OrderPair> getOrderPairs() { return orderPairs; }
    public List<NegConstraint> getNegConstraints() { return negConstraints; }

    void merge(PartialMatch other) {
        for (Event e : other.events) {
            add(e, other.vlb.get(e), other.vub.get(e));
        }
        for (OrderPair op : other.orderPairs) {
            addOrder(op.before, op.after);
        }
        for (NegConstraint nc : other.negConstraints) {
            addNegConstraint(nc.first, nc.third, new ArrayList<>(nc.negEvents));
        }
    }

    boolean isValid() {
        for (Event e : events)
            if (vlb.get(e) > vub.get(e)) return false;
        return true;
    }

    public int getVlb(int index) {
        return vlb.get(events.get(index));
    }

    public int getVub(int index) {
        return vub.get(events.get(index));
    }

    int minVlb() { return events.stream().mapToInt(vlb::get).min().orElse(Integer.MAX_VALUE); }
    int maxVlb() { return events.stream().mapToInt(vlb::get).max().orElse(Integer.MIN_VALUE); }
    int minVub() { return events.stream().mapToInt(vub::get).min().orElse(Integer.MAX_VALUE); }
    int maxVub() { return events.stream().mapToInt(vub::get).max().orElse(Integer.MIN_VALUE); }
}
