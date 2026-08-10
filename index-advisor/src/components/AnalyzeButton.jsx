function AnalyzeButton({
    text,
    onClick,
}) {
    return (
        <button onClick={onClick}>
            {text}
        </button>
    );
}

export default AnalyzeButton;