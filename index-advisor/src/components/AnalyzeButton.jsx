import "../css/AnalyzeButton.css";

function AnalyzeButton({ text, onClick, }) {
    return (
        <button className="analyze-button" onClick={onClick}>
            {text}
        </button>
    );
}

export default AnalyzeButton;