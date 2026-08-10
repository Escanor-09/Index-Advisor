function ResultCard({
    recommendedIndex,
    beforeCost,
    afterCost,
    improvement,
}) {
    return (
        <div>
            <h3>Recommendation</h3>

            <p>
                Recommended Index:
                {recommendedIndex}
            </p>

            <p>
                Cost Before:
                {beforeCost}
            </p>

            <p>
                Cost After:
                {afterCost}
            </p>

            <p>
                Improvement:
                {improvement}%
            </p>
        </div>
    );
}

export default ResultCard;