package advisor.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pure logic, no database needed. Covers the real bug this method exists to
 * fix (see docs): a join's root node is the join STRATEGY, which can stay
 * identical while a nested child scan changes — the exact scenario a
 * root-only comparison originally got wrong.
 */
class QueryPlanTest {

    @Test
    void identicalPlans_reportsNoChange() {
        List<String> types = List.of("Seq Scan");

        assertThat(QueryPlan.describeChange(types, types)).isEqualTo("No plan change (Seq Scan)");
    }

    @Test
    void singleTablePlan_rootChanged_simpleBeforeAfter() {
        List<String> before = List.of("Seq Scan");
        List<String> after = List.of("Bitmap Heap Scan");

        assertThat(QueryPlan.describeChange(before, after)).isEqualTo("Seq Scan -> Bitmap Heap Scan");
    }

    @Test
    void joinQuery_rootUnchanged_nestedScanChanged_describesTheNestedChangeNotTheRoot() {
        // The real Milestone-14 bug: root (join strategy) stays "Nested Loop" on both
        // sides; the actual improvement is a nested Seq Scan becoming an Index Scan.
        List<String> before = List.of("Nested Loop", "Seq Scan", "Seq Scan");
        List<String> after = List.of("Nested Loop", "Index Scan", "Seq Scan");

        assertThat(QueryPlan.describeChange(before, after))
            .isEqualTo("Seq Scan -> Index Scan (nested under Nested Loop)");
    }

    @Test
    void joinQuery_rootUnchanged_secondChildChanged_findsThatPosition() {
        List<String> before = List.of("Nested Loop", "Index Scan", "Seq Scan");
        List<String> after = List.of("Nested Loop", "Index Scan", "Bitmap Heap Scan");

        assertThat(QueryPlan.describeChange(before, after))
            .isEqualTo("Seq Scan -> Bitmap Heap Scan (nested under Nested Loop)");
    }

    @Test
    void rootChanged_evenWithNestedChildrenPresent_reportsRootOnly() {
        // Root changing is the dominant signal — a composite index turning a
        // Nested Loop into a Hash Join, say — so this stays a simple sentence
        // rather than also trying to describe every nested difference too.
        List<String> before = List.of("Nested Loop", "Seq Scan", "Seq Scan");
        List<String> after = List.of("Hash Join", "Hash", "Seq Scan");

        assertThat(QueryPlan.describeChange(before, after)).isEqualTo("Nested Loop -> Hash Join");
    }

    @Test
    void differentTreeSizes_sameSharedPositions_describesShapeChange() {
        List<String> before = List.of("Seq Scan");
        List<String> after = List.of("Seq Scan", "Index Scan");

        assertThat(QueryPlan.describeChange(before, after))
            .isEqualTo("Seq Scan -> Seq Scan (plan shape changed: 1 -> 2 nodes)");
    }
}
