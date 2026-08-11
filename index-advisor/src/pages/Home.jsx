import { Link } from "react-router-dom";
import Navbar from "../components/Navbar";
import "../css/Home.css"
import AnalyzeButton from "../components/AnalyzeButton";
import "../css/AnalyzeButton.css"
function Home() {
    return (
        <>
            <Navbar />
            <div className="hero">
                <h1>Index Advisor</h1>
                <p>Analyze SQL workloads and recommend optimal indexes</p>

                <div className="hero-buttons">
                    <Link to={"/single-query"}>
                        <AnalyzeButton text="Single Query Optimization" onClick={() => navigate("/single-query")} />
                    </Link>
                    <Link to={"/workload"}>
                        <AnalyzeButton text="Workload Optimization" onClick={() => navigate("/workload")} />
                    </Link>
                </div>
            </div>
        </>
    );
}

export default Home;