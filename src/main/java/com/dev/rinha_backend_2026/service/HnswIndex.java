package com.dev.rinha_backend_2026.service;

import java.util.*;

/**
 * Single-layer NSW graph (HNSW layer-0 only).
 *
 * With 3M nodes and 165 MB container limit, only a flat layer-0 graph fits:
 *   M=8  → conn int[] = 3M × 8 × 4 = 96 MB  (OOM)
 *   M=4  → conn int[] = 3M × 4 × 4 = 48 MB  (safe)
 *
 * Graph navigability fix: maintain separate fraudEntry/legitEntry points so
 * each class builds internal connections during construction, and search starts
 * from the appropriate cluster entry.
 */
public class HnswIndex {

    public static final int M                = 4;
    public static final int EF_CONSTRUCTION  = 200;
    public static final int EF_SEARCH        = 200;

    private static final int   DIM   = KnnSearchService.DIM;
    private static final int   K     = KnnSearchService.K;
    private static final float FRAUD = 0.6f;

    // Flat layer-0 graph: conn[nodeId * M + j] = j-th neighbor
    private final int[]  conn;
    private final byte[] cnt;   // actual neighbor count per node (max = M)

    private final byte[]    vectors;
    private final boolean[] isFraud;
    private final int       size;

    private int entryPoint = -1;
    private int fraudEntry = -1;  // last inserted fraud node
    private int legitEntry = -1;  // last inserted legit node

    public HnswIndex(byte[] vectors, boolean[] isFraud, int size) {
        this.vectors = vectors;
        this.isFraud = isFraud;
        this.size    = size;
        this.conn    = new int[size * M];
        this.cnt     = new byte[size];
        Arrays.fill(conn, -1);
    }

    // -------------------------------------------------------------------------
    // Distance helpers
    // -------------------------------------------------------------------------

    private int distNN(int a, int b) {
        int ba = a * DIM, bb = b * DIM, s = 0;
        for (int i = 0; i < DIM; i++) {
            int d = vectors[ba + i] - vectors[bb + i];
            s += d * d;
        }
        return s;
    }

    private int distQN(byte[] q, int node) {
        int base = node * DIM, s = 0;
        for (int i = 0; i < DIM; i++) {
            int d = q[i] - vectors[base + i];
            s += d * d;
        }
        return s;
    }

    // (dist, nodeId) packed into one long — avoids Long boxing in PriorityQueue<Long>
    private static long enc(int dist, int node) { return ((long) dist << 32) | (node & 0xFFFFFFFFL); }
    private static int  decDist(long v)         { return (int)(v >>> 32); }
    private static int  decNode(long v)         { return (int) v; }

    // -------------------------------------------------------------------------
    // Beam search — build variant (distances between stored nodes)
    // -------------------------------------------------------------------------

    private long[] beamSearchNN(int qNode, long[] entry, int ef) {
        PriorityQueue<Long> cands  = new PriorityQueue<>();
        PriorityQueue<Long> result = new PriorityQueue<>(Collections.reverseOrder());
        Set<Integer> visited = new HashSet<>(ef * 4);

        for (long e : entry) {
            if (visited.add(decNode(e))) { cands.add(e); result.add(e); }
        }

        while (!cands.isEmpty()) {
            long cur = cands.poll();
            if (result.size() >= ef && decDist(cur) > decDist(result.peek())) break;
            int c = decNode(cur), base = c * M, count = cnt[c] & 0xFF;
            for (int j = 0; j < count; j++) {
                int nb = conn[base + j];
                if (nb < 0 || !visited.add(nb)) continue;
                int d = distNN(qNode, nb);
                if (result.size() < ef || d < decDist(result.peek())) {
                    long e = enc(d, nb); cands.add(e); result.add(e);
                    if (result.size() > ef) result.poll();
                }
            }
        }
        Long[] boxed = result.toArray(new Long[0]);
        long[] arr   = new long[boxed.length];
        for (int i = 0; i < boxed.length; i++) arr[i] = boxed[i];
        Arrays.sort(arr);
        return arr;
    }

    // -------------------------------------------------------------------------
    // Beam search — query variant (distances from byte[] query to stored nodes)
    // -------------------------------------------------------------------------

    private long[] beamSearchQN(byte[] q, long[] entry, int ef) {
        PriorityQueue<Long> cands  = new PriorityQueue<>();
        PriorityQueue<Long> result = new PriorityQueue<>(Collections.reverseOrder());
        Set<Integer> visited = new HashSet<>(ef * 4);

        for (long e : entry) {
            if (visited.add(decNode(e))) { cands.add(e); result.add(e); }
        }

        while (!cands.isEmpty()) {
            long cur = cands.poll();
            if (result.size() >= ef && decDist(cur) > decDist(result.peek())) break;
            int c = decNode(cur), base = c * M, count = cnt[c] & 0xFF;
            for (int j = 0; j < count; j++) {
                int nb = conn[base + j];
                if (nb < 0 || !visited.add(nb)) continue;
                int d = distQN(q, nb);
                if (result.size() < ef || d < decDist(result.peek())) {
                    long e = enc(d, nb); cands.add(e); result.add(e);
                    if (result.size() > ef) result.poll();
                }
            }
        }
        Long[] boxed = result.toArray(new Long[0]);
        long[] arr   = new long[boxed.length];
        for (int i = 0; i < boxed.length; i++) arr[i] = boxed[i];
        Arrays.sort(arr);
        return arr;
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

    private void addLink(int src, int dst) {
        int base  = src * M;
        int count = cnt[src] & 0xFF;
        if (count < M) {
            conn[base + count] = dst;
            cnt[src] = (byte)(count + 1);
        } else {
            int wi = 0, wd = distNN(src, conn[base]);
            for (int j = 1; j < M; j++) {
                int d = distNN(src, conn[base + j]);
                if (d > wd) { wd = d; wi = j; }
            }
            if (distNN(src, dst) < wd) conn[base + wi] = dst;
        }
    }

    // -------------------------------------------------------------------------
    // Build — call sequentially from DatasetLoader
    // -------------------------------------------------------------------------

    public void insert(int nodeId) {
        boolean isFraudNode = isFraud[nodeId];

        if (entryPoint < 0) {
            entryPoint = nodeId;
            if (isFraudNode) fraudEntry = nodeId;
            else             legitEntry = nodeId;
            return;
        }

        // Build entry point list: global entry + same-class entry (creates
        // intra-class edges so fraud↔fraud and legit↔legit clusters connect).
        int classEntry = isFraudNode ? fraudEntry : legitEntry;
        long[] eps;
        if (classEntry >= 0 && classEntry != entryPoint) {
            eps = new long[]{
                enc(distNN(nodeId, entryPoint), entryPoint),
                enc(distNN(nodeId, classEntry), classEntry)
            };
        } else {
            eps = new long[]{ enc(distNN(nodeId, entryPoint), entryPoint) };
        }

        long[] neighbors = beamSearchNN(nodeId, eps, EF_CONSTRUCTION);

        int toConnect = Math.min(neighbors.length, M);
        for (int j = 0; j < toConnect; j++) {
            int nb = decNode(neighbors[j]);
            addLink(nodeId, nb);
            addLink(nb, nodeId);
        }

        // Update class entry point to the most recently inserted node of each class
        if (isFraudNode) fraudEntry = nodeId;
        else             legitEntry = nodeId;
    }

    // -------------------------------------------------------------------------
    // Search — concurrent-safe after build completes
    // -------------------------------------------------------------------------

    public KnnSearchService.FraudResult search(byte[] query) {
        if (entryPoint < 0) throw new IllegalStateException("HNSW index not built");

        // 10 evenly-spaced probes across the full dataset + class entry points.
        // Since the graph has fraud↔fraud edges (built via fraudEntry), starting
        // near a fraud node navigates directly to the fraud cluster.
        long[] entry = new long[]{
            enc(distQN(query, entryPoint),         entryPoint),
            enc(distQN(query, size / 19),          size / 19),
            enc(distQN(query, 2 * size / 19),      2 * size / 19),
            enc(distQN(query, 3 * size / 19),      3 * size / 19),
            enc(distQN(query, 4 * size / 19),      4 * size / 19),
            enc(distQN(query, 5 * size / 19),      5 * size / 19),
            enc(distQN(query, 6 * size / 19),      6 * size / 19),
            enc(distQN(query, 7 * size / 19),      7 * size / 19),
            enc(distQN(query, 8 * size / 19),      8 * size / 19),
            enc(distQN(query, 9 * size / 19),      9 * size / 19),
            enc(distQN(query, 10 * size / 19),     10 * size / 19),
            enc(distQN(query, 11 * size / 19),     11 * size / 19),
            enc(distQN(query, 12 * size / 19),     12 * size / 19),
            enc(distQN(query, 13 * size / 19),     13 * size / 19),
            enc(distQN(query, 14 * size / 19),     14 * size / 19),
            enc(distQN(query, 15 * size / 19),     15 * size / 19),
            enc(distQN(query, 16 * size / 19),     16 * size / 19),
            enc(distQN(query, 17 * size / 19),     17 * size / 19),
            enc(distQN(query, 18 * size / 19),     18 * size / 19),
            enc(distQN(query, size - 1),           size - 1),
            enc(distQN(query, fraudEntry),         fraudEntry),
            enc(distQN(query, legitEntry),         legitEntry),
        };
        long[] cands = beamSearchQN(query, entry, EF_SEARCH);

        int fraudCount = 0, kk = Math.min(K, cands.length);
        for (int i = 0; i < kk; i++) {
            if (isFraud[decNode(cands[i])]) fraudCount++;
        }
        double score = (double) fraudCount / K;
        return new KnnSearchService.FraudResult(score < FRAUD, score);
    }
}
