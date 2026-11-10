package org.jackhuang.hmcl.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

public class TwoSatSet<T> {
    private final Map<T, Integer> variableMap = new HashMap<>();
    private final List<T> variableList = new ArrayList<>();
    private final Set<T> underlying;
    private int[] edgeTo = new int[4];
    private int[] edgeNext = new int[4];
    private int edgeCount = 0;
    private int[] vertHead = new int[4];
    private int vertCount = 0;

    public TwoSatSet(Set<T> underlying) {
        this.underlying = underlying;
    }

    private int variableIndex(T variable) {
        return variableMap.computeIfAbsent(variable, v -> {
            if (vertCount == vertHead.length) {
                int[] newVertHead = new int[vertHead.length * 2];
                System.arraycopy(vertHead, 0, newVertHead, 0, vertHead.length);
                vertHead = newVertHead;
            }
            vertHead[vertCount] = -1;
            vertHead[vertCount + 1] = -1;
            vertCount += 2;
            variableList.add(v);
            return variableList.size() - 1;
        });
    }

    public final int present(T variable) {
        return variableIndex(variable) << 1;
    }

    public final int absent(T variable) {
        return present(variable) | 1;
    }

    private void implies(int a, int b) {
        if (edgeCount == edgeTo.length) {
            int[] newEdgeTo = new int[edgeTo.length * 2];
            int[] newEdgeNext = new int[edgeNext.length * 2];
            System.arraycopy(edgeTo, 0, newEdgeTo, 0, edgeTo.length);
            System.arraycopy(edgeNext, 0, newEdgeNext, 0, edgeNext.length);
            edgeTo = newEdgeTo;
            edgeNext = newEdgeNext;
        }
        edgeTo[edgeCount] = b;
        edgeNext[edgeCount] = vertHead[a];
        vertHead[a] = edgeCount;
        edgeCount++;
    }

    public void or(int a, int b) {
        implies(a ^ 1, b);
        implies(b ^ 1, a);
    }

    private boolean dfs(int v, boolean[] visited) {
        if (visited[v])
            return true;
        if (visited[v ^ 1])
            return false;
        visited[v] = true;
        for (int e = vertHead[v]; e != -1; e = edgeNext[e])
            if (!dfs(edgeTo[e], visited))
                return false;
        return true;
    }

    private void undfs(int v, boolean[] visited) {
        if (!visited[v])
            return;
        visited[v] = false;
        for (int e = vertHead[v]; e != -1; e = edgeNext[e])
            if (visited[edgeTo[e]])
                undfs(edgeTo[e], visited);
    }

    public boolean apply(int start) {
        boolean[] visited = new boolean[vertCount];
        if (!dfs(start, visited))
            return false;
        for (int i = 0; i < variableList.size(); i++) {
            if (visited[i << 1] && !underlying.contains(variableList.get(i)))
                underlying.add(variableList.get(i));
            else if (visited[(i << 1) | 1] && underlying.contains(variableList.get(i)))
                underlying.remove(variableList.get(i));
        }
        return true;
    }

    public boolean add(T variable) {
        Integer index = variableMap.get(variable);
        return index == null ? underlying.add(variable) : apply(index << 1);
    }

    public boolean remove(T variable) {
        Integer index = variableMap.get(variable);
        return index == null ? underlying.remove(variable) : apply((index << 1) | 1);
    }

    /// Find the minimal subset of underlying that satisfies the 2-SAT constraints
    public boolean minimize() {
        boolean[] visited = new boolean[vertCount];
        for (int i = 0; i < variableList.size(); i++)
            if (!underlying.contains(variableList.get(i)))
                if (!dfs((i << 1) | 1, visited))
                    return false;
        for (int i = 0; i < variableList.size(); i++)
            if (!visited[i << 1] && !visited[(i << 1) | 1])
                if (!dfs((i << 1) | 1, visited)) { // Try to exclude first
                    undfs((i << 1) | 1, visited);
                    if (!dfs(i << 1, visited))
                        return false; // Neither excluding nor including the variable works, unsatisfiable
                }
        underlying.removeIf(k -> {
            Integer index = variableMap.get(k);
            return index == null || visited[(index << 1) | 1];
        });
        return true;
    }

    public boolean satisfied() {
        for (int i = 0; i < variableList.size(); i++) {
            int u = (i << 1) | (underlying.contains(variableList.get(i)) ? 0 : 1);
            for (int e = vertHead[u]; e != -1; e = edgeNext[e])
                if (underlying.contains(edgeTo[e] >> 1) ^ (edgeTo[e] & 1) == 0)
                    return false;
        }
        return true;
    }
}
