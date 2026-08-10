import { Link } from "react-router-dom";
import Navbar from "../components/Navbar";

function Home() {
    return (
        <>
            <Navbar />
            <div>
                <h1>Index Advisor</h1>
                <p>
                    Analyze SQL workloads and recommend optimal indexes
                </p>

                <Link to={"/single-query"}>
                    <button>
                        Single Query Optimization
                    </button>
                </Link>

                <Link to={"/workload"}>
                    <button>
                        Workload Optimization
                    </button>
                </Link>
            </div>
        </>
    );
}

export default Home;