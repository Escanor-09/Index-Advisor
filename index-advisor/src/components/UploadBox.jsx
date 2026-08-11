import "../css/UploadBox.css";

function UploadBox({
    onFileSelect,
}) {
    return (
        <div className="upload-box">
            <label className="upload-label">
                Choose Workload File

                <input
                    type="file"
                    accept=".txt,.log"
                    onChange={(e) =>
                        onFileSelect(
                            e.target.files[0]
                        )
                    }
                />
            </label>

            <p>
                Supported formats: .txt, .log
            </p>
        </div>
    );
}

export default UploadBox;