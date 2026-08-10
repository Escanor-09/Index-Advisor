import { useState } from "react";
import Navbar from "../components/Navbar";
import AnalyzeButton from "../components/AnalyzeButton";
import ResultCard from "../components/ResultCard";
import UploaderBox from "../components/UploadBox";

function WorkloadOptimization() {
    const [result, setResult] = useState(null);

    const handleAnalyze = () => {
        setResult({
            recommendedIndex: "abc",
            beforeCost: 150,
            afterCost: 75,
            improvement: 50.7
        })
    }
    return (
        <>
            <Navbar />
            <div>
                <UploaderBox />
                <AnalyzeButton text={"Analyze"} onClick={handleAnalyze} />
                {
                    result && <ResultCard {...result} />
                }
            </div>
        </>
    );
}

export default WorkloadOptimization;