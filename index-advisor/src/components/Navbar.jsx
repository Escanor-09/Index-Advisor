import { Link } from "react-router-dom";
import "../css/Navbar.css"
function Navbar() {
    return (
        <nav className="navbar">
            <h2 className="logo">Index Advisor</h2>

            <div className="nav-links">
                <Link to="/">Home</Link>
                <Link to="/single-query">Single Query</Link>
                <Link to="/workload">Workload</Link>
            </div>
        </nav>
    );
}

export default Navbar;