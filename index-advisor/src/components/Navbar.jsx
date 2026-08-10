import { Link } from "react-router-dom";

function Navbar() {
    return (
        <nav>
            <h2>
                Index Advisor
            </h2>

            <Link to={"/"}>Home</Link>
            <Link to={"/single-query"}>Single Query</Link>
            <Link to="/workload">Workliad</Link>
        </nav>
    );
}

export default Navbar;