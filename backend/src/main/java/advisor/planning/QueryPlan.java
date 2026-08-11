package advisor.planning;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Wraps the JSON produced by EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) so
 * callers don't have to repeatedly index into the raw tree, e.g.
 * plan[0]["Plan"]["Total Cost"].
 */
public class QueryPlan {

    private final JsonNode root;
    private final JsonNode planNode;

    public QueryPlan(String explainJson) {
        try {
            JsonNode parsed = new ObjectMapper().readTree(explainJson);
            this.root = parsed.isArray() ? parsed.get(0) : parsed;
            this.planNode = root.path("Plan");
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse EXPLAIN JSON: " + explainJson, e);
        }
    }

    public double getTotalCost() {
        return planNode.path("Total Cost").asDouble();
    }

    public double getExecutionTime() {
        return root.path("Execution Time").asDouble();
    }

    public double getPlanningTime() {
        return root.path("Planning Time").asDouble();
    }

    public String getNodeType() {
        return planNode.path("Node Type").asText();
    }

    public String getRelationName() {
        return planNode.path("Relation Name").asText();
    }

    /**
     * Every "Node Type" in the plan tree, depth-first — not just the root.
     * For a single-table query the root IS the whole plan, so this is just
     * [getNodeType()]. For a join, the root is the join STRATEGY (Nested
     * Loop / Hash Join / Merge Join) and can legitimately stay the same
     * while a nested child scan changes (e.g. an inner Seq Scan becoming
     * an Index Scan) — comparing only the root, as an earlier version of
     * IndexEvaluator's improvement clamp did, would wrongly treat that as
     * "the plan didn't change" and suppress a real improvement. See
     * IndexEvaluator.computeImprovement.
     */
    public List<String> getAllNodeTypes() {
        List<String> nodeTypes = new ArrayList<>();
        collectNodeTypes(planNode, nodeTypes);
        return nodeTypes;
    }

    private static void collectNodeTypes(JsonNode node, List<String> out) {
        if (node == null || node.isMissingNode()) {
            return;
        }

        out.add(node.path("Node Type").asText());

        JsonNode children = node.path("Plans");

        if (children.isArray()) {
            for (JsonNode child : children) {
                collectNodeTypes(child, out);
            }
        }
    }
}
