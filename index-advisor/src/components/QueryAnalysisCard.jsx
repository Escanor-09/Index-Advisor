import "../css/QueryAnalysisCard.css";

function QueryAnalysisCard({
    tables = [],
    filterColumns = [],
    joinColumns = [],
    orderByColumns = [],
}) {
    return (
        <div className="analysis-card">
            <h2>Query Analysis</h2>

            <div className="analysis-section">
                <h3>Tables</h3>

                {tables.length > 0 ? (
                    <ul>
                        {tables.map((table, idx) => (
                            <li key={idx}>{table}</li>
                        ))}
                    </ul>
                ) : (
                    <p>None</p>
                )}
            </div>

            <div className="analysis-section">
                <h3>Filter Columns</h3>

                <ul>
                    {filterColumns.length > 0 ? (
                        <ul>
                            {filterColumns.map((col, idx) => (
                                <li key={idx}>
                                    <strong>{col.table}</strong> → {col.column}
                                </li>
                            ))}
                        </ul>
                    ) : (
                        <p>None</p>
                    )}
                </ul>
            </div>

            <div className="analysis-section">
                <h3>Join Columns</h3>

                {joinColumns.length > 0 ? (
                    <ul>
                        {joinColumns.map((col, idx) => (
                            <li key={idx}>
                                <strong>{col.table}</strong> → {col.column}
                            </li>
                        ))}
                    </ul>
                ) : (
                    <p>None</p>
                )}
            </div>

            <div className="analysis-section">
                <h3>Order By Columns</h3>

                {orderByColumns.length > 0 ? (
                    <ul>
                        {orderByColumns.length > 0 ? (
                            <ul>
                                {orderByColumns.map((col, idx) => (
                                    <li key={idx}>
                                        <strong>{col.table}</strong> → {col.column}
                                    </li>
                                ))}
                            </ul>
                        ) : (
                            <p>None</p>
                        )}
                    </ul>
                ) : (
                    <p>None</p>
                )}
            </div>
        </div>
    );
}

export default QueryAnalysisCard;