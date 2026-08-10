import { useState } from "react";
import { queries } from "../mock/mockData";
import QuerySelector from "../components/QuerySelector";
import AnalyzeButton from "../components/AnalyzeButton";
import ResultCard from "../components/ResultCard";
import Navbar from "../components/Navbar";

function SingleQuery() {
    const [selectedQuery, setSelectedQuery] = useState("");
    const [result, setResult] = useState(null);

    const handleAnalyze = () => {
        setResult({
            recommendedIndex: "employee(dept_id)",
            beforeCost: 1200,
            afterCost: 150,
            imporvement: 87.5,
        });
    };

    return (
        <>
            <Navbar />
            <div>
                <h1>Signle Query Optimization</h1>
                <QuerySelector queries={queries} selectedQuery={selectedQuery} onChange={setSelectedQuery} />
                <AnalyzeButton text={"Analyze Query"} onClick={handleAnalyze} />
                {
                    result && (<ResultCard {...result} />)
                }
            </div>
        </>
    );
}

export default SingleQuery;