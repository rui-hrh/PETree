import java.util.*;

class Event {
    String id;
    String type;
    int lower, upper;
    Map<String, Object> attrs = new HashMap<>();
    double[] pmf = null;

    Event(String id, String type, int lower, int upper) {
        this.id = id; this.type = type; this.lower = lower; this.upper = upper;
    }
    double prob(int t) {
        if (pmf == null) {
            if (t < lower || t > upper) return 0;
            return 1.0 / (upper - lower + 1);
        }
        int idx = t - lower;
        if (idx < 0 || idx >= pmf.length) return 0;
        return pmf[idx];
    }
}
