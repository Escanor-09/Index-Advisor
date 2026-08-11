import { useState } from "react";

import Navbar from "../components/Navbar";
import QueryEditor from "../components/QueryEditor";
import AnalyzeButton from "../components/AnalyzeButton";

import ResultCard from "../components/ResultCard";
import CostComparisonChart from "../components/CostComparisonChart";
import CandidateTable from "../components/CandidateTable";
import QueryAnalysisCard from "../components/QueryAnalysisCard";
import Recommendation from "../components/Recommendation";

import "../css/Single_Query.css";

function SingleQuery() {
    const [query, setQuery] = useState(
        `SELECT * FROM products WHERE category_id = 5;`
    );

    const [result, setResult] = useState(null);

    const validateQuery = (query) => {
        const trimmed = query.trim().toUpperCase();

        return (
            trimmed.startsWith("SELECT") ||
            trimmed.startsWith("WITH") ||
            trimmed.startsWith("EXPLAIN")
        );
    };

    const handleAnalyze = () => {
        if (!validateQuery(query)) {
            alert(
                "Only SELECT, WITH and EXPLAIN queries are allowed."
            );
            return;
        }

        setResult({
            bestIndex: "(category_id, brand_id)",

            originalCost: 1200,

            candidates: [
                {
                    index: "(category_id, brand_id)",
                    cost: 120,
                    improvement: 90,
                },
                {
                    index: "(category_id, price)",
                    cost: 180,
                    improvement: 85,
                },
                {
                    index: "(brand_id, price)",
                    cost: 250,
                    improvement: 79,
                },
                {
                    index: "(category_id)",
                    cost: 400,
                    improvement: 66,
                },
            ],

            analysis: {
                table: "products",

                filterColumns: [
                    "category_id",
                ],

                joinColumns: [],

                orderByColumns: [],
            },

            reasoning: [
                "category_id appears in WHERE clause",
                "brand_id increases selectivity",
                "lowest estimated cost among candidates",
                "90% reduction in estimated cost",
            ],
        });
    };

    return (
        <>
            <Navbar />

            <div className="single-query-page">

                <h1>Single Query Optimization</h1>

                <QueryEditor
                    query={query}
                    onChange={setQuery}
                />

                <AnalyzeButton
                    text="Analyze Query"
                    onClick={handleAnalyze}
                />

                {result && (
                    <>
                        <ResultCard
                            bestIndex={result.bestIndex}
                            originalCost={result.originalCost}
                            bestCost={
                                result.candidates[0].cost
                            }
                            improvement={
                                result.candidates[0].improvement
                            }
                        />

                        <CostComparisonChart
                            data={[
                                {
                                    index: "No Index",
                                    cost: result.originalCost,
                                },
                                ...result.candidates,
                            ]}
                        />

                        <CandidateTable
                            candidates={result.candidates}
                        />

                        <QueryAnalysisCard
                            table={result.analysis.table}
                            filterColumns={
                                result.analysis.filterColumns
                            }
                            joinColumns={
                                result.analysis.joinColumns
                            }
                            orderByColumns={
                                result.analysis.orderByColumns
                            }
                        />

                        <Recommendation
                            index={result.bestIndex}
                            reasons={result.reasoning}
                        />
                    </>
                )}
            </div>
        </>
    );
}

export default SingleQuery;