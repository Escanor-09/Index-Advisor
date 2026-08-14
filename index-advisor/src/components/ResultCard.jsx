import "../css/ResultCard.css";

function ResultCard({
    bestIndex,
    originalCost,
    bestCost,
    improvement,
}) {
    return (
        <div className="result-card">
            <h2>Best Recommendation</h2>

            <div className="best-index">
                {bestIndex}
            </div>

            <div className="metrics-grid">

                <div className="metric-box">
                    <span className="metric-label">
                        Original Cost
                    </span>

                    <span className="metric-value">
                        {originalCost}
                    </span>
                </div>

                <div className="metric-box">
                    <span className="metric-label">
                        Optimized Cost
                    </span>

                    <span className="metric-value">
                        {bestCost}
                    </span>
                </div>

                <div className="metric-box">
                    <span className="metric-label">
                        Improvement
                    </span>

                    <span className="metric-value improvement">
                        {improvement}%
                    </span>
                </div>

            </div>
        </div>
    );
}

export default ResultCard;