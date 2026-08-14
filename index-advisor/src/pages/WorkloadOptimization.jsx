import { useState } from "react";
import Navbar from "../components/Navbar";
import AnalyzeButton from "../components/AnalyzeButton";
import UploadBox from "../components/UploadBox";
import WorkloadResultCard from "../components/WorkloadResultCard";
import CostComparisonChart from "../components/CostComparisonChart";
import CandidateTable from "../components/CandidateTable";
import CoverageAnalysisCard from "../components/CoverageAnalysisCard";
import RecommendedIndexSetCard from "../components/RecommendedIndexSetCard";

import "../css/Workload.css";

function WorkloadOptimization() {
    const [result, setResult] = useState(null);
    const [file, setFile] = useState(null);

    const handleAnalyze = () => {
        if (!file) {
            alert("Please upload a workload file.");
            return;
        }

        setResult({
            recommendedIndexes: [
                {
                    index: "(customer_id, order_date)",
                    cost: 18000,
                    improvement: 64,
                    coverage: 42,
                },
                {
                    index: "(category_id)",
                    cost: 22000,
                    improvement: 56,
                    coverage: 31,
                },
                {
                    index: "(product_id)",
                    cost: 29000,
                    improvement: 42,
                    coverage: 18,
                },
                {
                    index: "(order_date)",
                    cost: 34000,
                    improvement: 32,
                    coverage: 9,
                },
            ],

            beforeCost: 50000,
            afterCost: 18000,
            improvement: 64,
        });
    };

    return (
        <>
            <Navbar />

            <div className="workload-page">
                <h1>Workload Optimization</h1>

                <p>
                    Upload a workload log and receive index recommendations.
                </p>

                <UploadBox onFileSelect={setFile} />

                {file && (
                    <p className="selected-file">
                        Selected File: {file.name}
                    </p>
                )}

                <AnalyzeButton
                    text="Analyze Workload"
                    onClick={handleAnalyze}
                />

                {
                    result && (
                        <>
                            <WorkloadResultCard
                                recommendedIndexes={result.recommendedIndexes}
                                beforeCost={result.beforeCost}
                                afterCost={result.afterCost}
                                improvement={result.improvement}
                            />

                            <CostComparisonChart
                                data={[
                                    {
                                        index: "No Index",
                                        cost: result.beforeCost,
                                    },
                                    ...result.recommendedIndexes.map(
                                        (idx) => ({
                                            index: idx.index,
                                            cost: idx.cost,
                                        })
                                    ),
                                ]}
                            />

                            <CandidateTable
                                candidates={result.recommendedIndexes.map(
                                    (idx) => ({
                                        index: idx.index,
                                        cost: idx.cost,
                                        improvement: idx.improvement,
                                    })
                                )}
                            />

                            <CoverageAnalysisCard
                                indexes={result.recommendedIndexes}
                            />

                            <RecommendedIndexSetCard
                                indexes={result.recommendedIndexes}
                            />
                        </>
                    )
                }
            </div>
        </>
    );
}

export default WorkloadOptimization;