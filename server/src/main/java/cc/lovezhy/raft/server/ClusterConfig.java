package cc.lovezhy.raft.server;

public class ClusterConfig {
    private int nodeCount;

    public int getNodeCount() {
        return nodeCount;
    }

    void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }
}
