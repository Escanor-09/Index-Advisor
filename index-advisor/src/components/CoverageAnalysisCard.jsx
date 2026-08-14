import "../css/CoverageAnalysisCard.css";

function CoverageAnalysisCard({
    indexes,
}) {
    return (
        <div className="coverage-card">
            <h2>Coverage Analysis</h2>

            <table className="coverage-table">
                <thead>
                    <tr>
                        <th>Index</th>
                        <th>Queries Helped (%)</th>
                    </tr>
                </thead>

                <tbody>
                    {indexes.map((idx, i) => (
                        <tr key={i}>
                            <td>
                                <code>{idx.index}</code>
                            </td>

                            <td>
                                {idx.coverage}%
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

export default CoverageAnalysisCard;