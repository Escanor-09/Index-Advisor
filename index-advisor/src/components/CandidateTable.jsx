import "../css/CandidateTable.css";

function CandidateTable({ candidates }) {
    return (
        <div className="candidate-table-card">
            <h2>Candidate Indexes</h2>

            <table className="candidate-table">
                <thead>
                    <tr>
                        <th>Rank</th>
                        <th>Index</th>
                        <th>Cost</th>
                        <th>Improvement</th>
                    </tr>
                </thead>

                <tbody>
                    {candidates.map((candidate, idx) => (
                        <tr key={idx}>
                            <td>#{idx + 1}</td>

                            <td>
                                <code>{candidate.index}</code>
                            </td>

                            <td>{candidate.cost}</td>

                            <td>
                                {candidate.improvement}%
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

export default CandidateTable;