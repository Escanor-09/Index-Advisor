import "../css/WorkloadResultCard.css";

function WorkloadResultCard({
    recommendedIndexes,
    beforeCost,
    afterCost,
    improvement,
}) {
    return (
        <div className="workload-result-card">
            <h2>Workload Summary</h2>

            <div className="recommended-indexes">
                <h3>Top Recommended Indexes</h3>

                <ul>
                    {recommendedIndexes.map((idx, i) => (
                        <li key={i}>
                            {idx.index}
                        </li>
                    ))}
                </ul>
            </div>

            <div className="metrics-grid">

                <div className="metric-box">
                    <span className="metric-label">
                        Avg. Original Time (ms)
                    </span>

                    <span className="metric-value">
                        {beforeCost}
                    </span>
                </div>

                <div className="metric-box">
                    <span className="metric-label">
                        Avg. Optimized Time (ms)
                    </span>

                    <span className="metric-value">
                        {afterCost}
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

export default WorkloadResultCard;