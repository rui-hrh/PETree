import java.util.*;
import java.util.stream.*;

class StaticBounds {
    Map<String, Integer> preSlots = new HashMap<>();
    Map<String, Integer> postSlots = new HashMap<>();
    Map<String, Map<String, Integer>> midSlots = new HashMap<>();

    StaticBounds(List<String> leaves, List<String[]> edges) {
        int n = leaves.size();
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) idx.put(leaves.get(i), i);
        boolean[][] reach = new boolean[n][n];
        for (String[] e : edges) reach[idx.get(e[0])][idx.get(e[1])] = true;
        for (int k = 0; k < n; k++)
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++)
                    reach[i][j] |= reach[i][k] && reach[k][j];
        for (String x : leaves) {
            int xi = idx.get(x);
            preSlots.put(x, (int) IntStream.range(0,n).filter(i->i!=xi && reach[i][xi]).count());
            postSlots.put(x, (int) IntStream.range(0,n).filter(j->j!=xi && reach[xi][j]).count());
            midSlots.put(x, new HashMap<>());
        }
        for (String x : leaves)
            for (String y : leaves)
                if (reach[idx.get(x)][idx.get(y)]) {
                    int cnt = 0;
                    for (String z : leaves) {
                        int zi = idx.get(z);
                        if (reach[idx.get(x)][zi] && reach[zi][idx.get(y)] && zi != idx.get(x) && zi != idx.get(y))
                            cnt++;
                    }
                    midSlots.get(x).put(y, cnt);
                }
    }

    int deltaMin(String refType, String vType, int tw) {
        boolean refToV = midSlots.get(refType).containsKey(vType);
        boolean vToRef = midSlots.get(vType).containsKey(refType);
        if (refToV) return midSlots.get(refType).get(vType) + 1;
        else if (vToRef) return -(tw - 1) + postSlots.get(refType) + preSlots.get(vType);
        else return -(tw - 1) + postSlots.get(refType) + preSlots.get(vType);

    }
    int deltaMax(String refType, String vType, int tw) {
        boolean refToV = midSlots.get(refType).containsKey(vType);
        boolean vToRef = midSlots.get(vType).containsKey(refType);
        if (refToV) return (tw - 1) - preSlots.get(refType) - postSlots.get(vType);
        else if (vToRef) return -midSlots.get(vType).get(refType) - 1;
        else return (tw - 1) - preSlots.get(refType) - postSlots.get(vType);

    }
}
