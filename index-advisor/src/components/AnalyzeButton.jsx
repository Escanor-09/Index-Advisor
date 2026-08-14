import "../css/AnalyzeButton.css";

function AnalyzeButton({
    text,
    onClick,
    loading,
}) {
    return (
        <button className="analyze-button" onClick={onClick} disabled={loading}>
            {loading && (
                <span className="button-spinner"></span>
            )}

            {loading ? "Analyzing..." : text}
        </button>
    );
}

export default AnalyzeButton;