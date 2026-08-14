import { useState } from "react";
import axios from "axios";
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
    const [loading, setLoading] = useState(false);
    const [reasoning, setReasoning] = useState([]);
    const [aiLoading, setAiLoading] = useState(false);

    const validateQuery = (query) => {
        const trimmed = query.trim().toUpperCase();

        return (
            trimmed.startsWith("SELECT") ||
            trimmed.startsWith("WITH") ||
            trimmed.startsWith("EXPLAIN")
        );
    };

    const handleAnalyze = async () => {
        if (!validateQuery(query)) {
            alert(
                "Only SELECT, WITH and EXPLAIN queries are allowed."
            );
            return;
        }

        setLoading(true);
        setReasoning([]);
        setAiLoading(false);
        setResult(null);

        try {
            const response = await axios.post("http://localhost:8080/api/analyze-query", { query: query, });
            setResult(response.data);
            setAiLoading(true);
            setReasoning(["Generating AI explanation...."]);

            axios.post("http://localhost:8080/api/generate-explanation", {
                query: query,
                table: response.data.analysis.table,
                columns: response.data.analysis.filterColumns,
                beforeCost: response.data.originalCost,
                afterCost: response.data.bestCost,
                improvement: response.data.improvement
            }).then((aiRespnse) => {
                setReasoning(aiRespnse.data.reasoning);
                setAiLoading(false);
            }).catch(() => {
                setReasoning(["Failed to generate explanation."]);
                setAiLoading(false);
            });
        } catch (error) {
            console.error(error);
            alert("Failed to analyze query");
        }

        finally {
            setLoading(false);
        }
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
                    loading={loading}
                />
                {result && (
                    <>

                        <ResultCard
                            bestIndex={result.bestIndex}
                            originalCost={result.originalCost}
                            bestCost={result.bestCost}
                            improvement={result.improvement}
                        />
                        <CandidateTable candidates={result.candidates} />

                        <CostComparisonChart data={[
                            {
                                index: "No Index",
                                cost: result.originalCost,
                            },
                            ...result.candidates,
                        ]} />
                        <QueryAnalysisCard tables={result.analysis.tables}
                            filterColumns={result.analysis.filterColumns}
                            joinColumns={result.analysis.joinColumns}
                            orderByColumns={result.analysis.orderByColumns} />

                        {
                            aiLoading ? (
                                <div className="ai-loading-card">
                                    <div className="ai-spinner"></div>
                                    <p>Generating AI explanation...</p>
                                </div>
                            ) : (
                                <Recommendation
                                    index={result.bestIndex}
                                    reasons={reasoning}
                                />
                            )
                        }
                    </>
                )}
            </div>
        </>
    );
}

export default SingleQuery;