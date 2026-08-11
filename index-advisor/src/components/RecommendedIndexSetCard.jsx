import "../css/RecommendedIndexSetCard.css";

function RecommendedIndexSetCard({
    indexes,
}) {
    return (
        <div className="recommended-set-card">
            <h2>Recommended Index Set</h2>

            <p className="recommended-description">
                Creating the following indexes provides the
                best overall workload improvement.
            </p>

            <div className="recommended-list">
                {indexes.map((idx, i) => (
                    <div
                        key={i}
                        className="recommended-item"
                    >
                        {idx.index}
                    </div>
                ))}
            </div>
        </div>
    );
}

export default RecommendedIndexSetCard;