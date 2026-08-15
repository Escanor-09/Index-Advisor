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

    /**
     * Describes what actually changed between two full plan-shape sequences
     * (see getAllNodeTypes()) — not just the root, which is what
     * Recommendation.observedEffect showed before this method existed. Real
     * bug this fixes: for a join query the root is the join STRATEGY, which
     * can legitimately stay identical while a nested child scan changes
     * (Seq Scan -> Index Scan); showing only "Nested Loop -> Nested Loop"
     * in that case is technically accurate about the root and misleading
     * about what the index actually did.
     *
     * Root-changed and no-change are both still handled as a single
     * sentence, matching the old, simpler behavior exactly for the common
     * single-table case (root IS the whole plan there, so this always takes
     * the "root changed" branch when there's any change at all).
     */
    public static String describeChange(List<String> beforeTypes, List<String> afterTypes) {
        if (beforeTypes.equals(afterTypes)) {
            return "No plan change (" + beforeTypes.get(0) + ")";
        }

        if (!beforeTypes.get(0).equals(afterTypes.get(0))) {
            return beforeTypes.get(0) + " -> " + afterTypes.get(0);
        }

        int sharedLength = Math.min(beforeTypes.size(), afterTypes.size());

        for (int i = 1; i < sharedLength; i++) {
            if (!beforeTypes.get(i).equals(afterTypes.get(i))) {
                return beforeTypes.get(i) + " -> " + afterTypes.get(i) + " (nested under " + beforeTypes.get(0) + ")";
            }
        }

        // Root and every shared position matched, but the trees differ in size —
        // a node was added or removed somewhere without changing any shared node's type.
        return beforeTypes.get(0) + " -> " + afterTypes.get(0)
            + " (plan shape changed: " + beforeTypes.size() + " -> " + afterTypes.size() + " nodes)";
    }
}
