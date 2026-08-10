function QuerySelector({
    queries, selectedQuery, onChange,
}) {
    return (
        <>
            <select value={selectedQuery} onChange={(e) => onChange(e.target.value)}>
                <option value={""}>
                    Select Query
                </option>

                {queries.map((query) => (
                    <option key={query.id} value={query.id}>query.name</option>
                ))}
            </select>
        </>
    );
}

export default QuerySelector;