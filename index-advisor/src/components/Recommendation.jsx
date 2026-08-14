import "../css/Recommendation.css";

function Recommendation({
    index,
    reasons,
}) {
    return (
        <div className="recommendation-card">
            <h2>Recommendation</h2>

            <div className="recommended-index">
                {index}
            </div>

            <h3>Why this index?</h3>

            <ul>
                {reasons.map((reason, idx) => (
                    <li key={idx}>
                        {reason}
                    </li>
                ))}
            </ul>
        </div>
    );
}

export default Recommendation;