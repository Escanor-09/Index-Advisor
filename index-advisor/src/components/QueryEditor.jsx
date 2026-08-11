import "../css/QueryEditor.css";

function QueryEditor({
    query,
    onChange,
}) {
    return (
        <div className="query-editor-card">
            <div className="query-editor-header">
                <h2>SQL Query</h2>
                <span>
                    Only SELECT, WITH and EXPLAIN queries are allowed
                </span>
            </div>

            <textarea
                className="query-editor"
                value={query}
                onChange={(e) => onChange(e.target.value)}
                placeholder="Enter your SQL query..."
            />
        </div>
    );
}

export default QueryEditor;