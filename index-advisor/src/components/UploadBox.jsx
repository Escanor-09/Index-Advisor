function UploaderBox({
    onFileSelect,
}) {
    return (
        <input
            type="file"
            accept=".txt,.log"
            onChange={(e) =>
                onFileSelect(
                    e.target.files[0]
                )
            }
        />
    );
}

export default UploaderBox;