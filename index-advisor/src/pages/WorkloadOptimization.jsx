import { useState } from "react";
import axios from "axios";
import Navbar from "../components/Navbar";
import AnalyzeButton from "../components/AnalyzeButton";
import WorkloadResultCard from "../components/WorkloadResultCard";
import CostComparisonChart from "../components/CostComparisonChart";
import CandidateTable from "../components/CandidateTable";
import CoverageAnalysisCard from "../components/CoverageAnalysisCard";
import RecommendedIndexSetCard from "../components/RecommendedIndexSetCard";

import "../css/Workload.css";

const PRESET_WORKLOADS = [
    { label: "Customer Account Management", file: "customer-account-management.txt" },
    { label: "Product Catalog Browsing", file: "product-catalog-browsing.txt" },
    { label: "Order Fulfillment Tracking", file: "order-fulfillment-tracking.txt" },
    { label: "Payment and Billing", file: "payment-and-billing.txt" },
    { label: "Customer Support Lookups", file: "customer-support-lookups.txt" },
    { label: "Logistics and Shipping", file: "logistics-and-shipping.txt" },
    { label: "Platform-Wide Peak Traffic (100 queries)", file: "platform-wide-peak-traffic.txt" },
];

const QUICK_ADD = {
    Customers: [
        { label: "Active customers", query: "SELECT * FROM customers WHERE status = 'active';" },
        { label: "Customers in India", query: "SELECT * FROM customers WHERE country = 'India';" },
        { label: "Recently joined customers", query: "SELECT * FROM customers ORDER BY created_at DESC;" },
        { label: "Suspended customers", query: "SELECT * FROM customers WHERE status = 'suspended';" },
        { label: "Customer order history", query: "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE c.status = 'active';" },
    ],
    Products: [
        { label: "Active products", query: "SELECT * FROM products WHERE status = 'active';" },
        { label: "Products by category", query: "SELECT * FROM products WHERE category_id = 1444;" },
        { label: "High-rated products", query: "SELECT * FROM products WHERE rating > 4.5;" },
        { label: "Low stock products", query: "SELECT * FROM products WHERE stock < 10;" },
        { label: "Products sorted by price", query: "SELECT * FROM products ORDER BY price DESC;" },
    ],
    Purchases: [
        { label: "Pending orders", query: "SELECT * FROM orders WHERE status = 'pending';" },
        { label: "Delivered orders", query: "SELECT * FROM orders WHERE status = 'delivered';" },
        { label: "High-value orders", query: "SELECT * FROM orders WHERE total_amount > 50000;" },
        { label: "Recent orders", query: "SELECT * FROM orders ORDER BY created_at DESC;" },
        { label: "Order + payment lookup", query: "SELECT * FROM payments p JOIN orders o ON p.order_id = o.id WHERE o.status = 'delivered';" },
    ],
};

// Mirrors WorkloadReader.readQueries() on the backend exactly: strip "--"
// comment lines, split on ";", so a preset file or a hand-written block of
// SQL both parse the same way the backend would parse queries.sql itself.
function parseWorkloadText(text) {
    const withoutComments = text
        .split("\n")
        .filter((line) => !line.trim().startsWith("--"))
        .join("\n");

    return withoutComments
        .split(";")
        .map((statement) => statement.trim())
        .filter((statement) => statement.length > 0)
        .map((statement) => statement + ";");
}

function WorkloadOptimization() {
    const [mode, setMode] = useState("preset");
    const [selectedPreset, setSelectedPreset] = useState(PRESET_WORKLOADS[0].file);
    const [presetLoading, setPresetLoading] = useState(false);
    const [customText, setCustomText] = useState("");
    const [queries, setQueries] = useState([]);
    const [loading, setLoading] = useState(false);
    const [result, setResult] = useState(null);

    const loadPreset = async (file) => {
        setPresetLoading(true);

        try {
            const response = await fetch(`/workload-samples/${file}`);
            const text = await response.text();
            setQueries(parseWorkloadText(text));
        } catch (error) {
            console.error(error);
            alert("Failed to load preset workload.");
        } finally {
            setPresetLoading(false);
        }
    };

    const handlePresetChange = (e) => {
        const file = e.target.value;
        setSelectedPreset(file);
        loadPreset(file);
    };

    const addCustomText = () => {
        const parsed = parseWorkloadText(customText);

        if (parsed.length === 0) {
            alert("Write at least one SELECT query first.");
            return;
        }

        setQueries((prev) => [...prev, ...parsed]);
        setCustomText("");
    };

    // Deliberately no dedup: clicking a chip repeatedly is the fast path for
    // building a large workload (e.g. up to 100 queries) without needing that
    // many distinct pre-written queries.
    const addQuickQuery = (query) => {
        setQueries((prev) => [...prev, query]);
    };

    const removeQuery = (index) => {
        setQueries((prev) => prev.filter((_, i) => i !== index));
    };

    const clearQueries = () => setQueries([]);

    const handleAnalyze = async () => {
        if (queries.length === 0) {
            alert("Add at least one query to the workload first.");
            return;
        }

        setLoading(true);
        setResult(null);

        try {
            const response = await axios.post(`${import.meta.env.VITE_API_URL}/analyze-workload`, { queries });
            setResult(response.data);
        } catch (error) {
            console.error(error);
            alert("Failed to analyze workload.");
        } finally {
            setLoading(false);
        }
    };

    return (
        <>
            <Navbar />

            <div className="workload-page">
                <h1>Workload Optimization</h1>

                <p>
                    Build a workload — pick a preset, write your own queries, or
                    quick-add common lookups — then get a recommended index set.
                </p>

                <div className="source-tabs">
                    <button
                        className={mode === "preset" ? "source-tab active" : "source-tab"}
                        onClick={() => setMode("preset")}
                    >
                        Preset Workload
                    </button>

                    <button
                        className={mode === "custom" ? "source-tab active" : "source-tab"}
                        onClick={() => setMode("custom")}
                    >
                        Write Your Own
                    </button>

                    <button
                        className={mode === "quick-add" ? "source-tab active" : "source-tab"}
                        onClick={() => setMode("quick-add")}
                    >
                        Quick Add
                    </button>
                </div>

                {mode === "preset" && (
                    <div className="source-panel">
                        <label className="source-label" htmlFor="preset-select">
                            Choose a themed sample workload
                        </label>

                        <div className="preset-row">
                            <select
                                id="preset-select"
                                value={selectedPreset}
                                onChange={handlePresetChange}
                            >
                                {PRESET_WORKLOADS.map((preset) => (
                                    <option key={preset.file} value={preset.file}>
                                        {preset.label}
                                    </option>
                                ))}
                            </select>

                            {presetLoading && <span className="inline-spinner"></span>}
                        </div>
                    </div>
                )}

                {mode === "custom" && (
                    <div className="source-panel">
                        <label className="source-label" htmlFor="custom-textarea">
                            Write SELECT queries (one per line, semicolon-terminated)
                        </label>

                        <textarea
                            id="custom-textarea"
                            value={customText}
                            onChange={(e) => setCustomText(e.target.value)}
                            placeholder={"SELECT * FROM orders WHERE status = 'pending';"}
                        />

                        <button className="secondary-button" onClick={addCustomText}>
                            Add to Workload
                        </button>
                    </div>
                )}

                {mode === "quick-add" && (
                    <div className="source-panel">
                        {Object.entries(QUICK_ADD).map(([category, items]) => (
                            <div key={category} className="quick-add-group">
                                <h3>{category}</h3>

                                <div className="quick-add-chips">
                                    {items.map((item) => (
                                        <button
                                            key={item.label}
                                            className="quick-add-chip"
                                            onClick={() => addQuickQuery(item.query)}
                                        >
                                            + {item.label}
                                        </button>
                                    ))}
                                </div>
                            </div>
                        ))}
                    </div>
                )}

                <div className="queries-panel">
                    <div className="queries-panel-header">
                        <h3>Queries in this workload ({queries.length})</h3>

                        {queries.length > 0 && (
                            <button className="clear-queries-button" onClick={clearQueries}>
                                Clear all
                            </button>
                        )}
                    </div>

                    {queries.length === 0 ? (
                        <p className="empty-queries-note">
                            No queries yet — pick a preset, write your own, or quick-add some above.
                        </p>
                    ) : (
                        <ul className="queries-list">
                            {queries.map((query, i) => (
                                <li key={i}>
                                    <code>{query}</code>

                                    <button
                                        className="remove-query-button"
                                        onClick={() => removeQuery(i)}
                                        aria-label="Remove query"
                                    >
                                        ×
                                    </button>
                                </li>
                            ))}
                        </ul>
                    )}
                </div>

                <AnalyzeButton
                    text="Analyze Workload"
                    onClick={handleAnalyze}
                    loading={loading}
                />

                {loading && (
                    <div className="ai-loading-card">
                        <div className="ai-spinner"></div>

                        <p>
                            Evaluating candidate indexes against the real database —
                            larger workloads (more distinct filter/join/sort columns)
                            take longer, since every candidate is a real CREATE INDEX
                            / measure / DROP cycle.
                        </p>
                    </div>
                )}

                {result && !loading && (
                    <>
                        {result.recommendedIndexes.length === 0 ? (
                            <div className="workload-empty-result">
                                No index recommendations cleared the improvement threshold for this workload.
                            </div>
                        ) : (
                            <>
                                <WorkloadResultCard
                                    recommendedIndexes={result.recommendedIndexes}
                                    beforeCost={result.beforeCost}
                                    afterCost={result.afterCost}
                                    improvement={result.improvement}
                                />

                                <CostComparisonChart
                                    data={[
                                        { index: "No Index", cost: result.beforeCost },
                                        ...result.recommendedIndexes.map((idx) => ({
                                            index: idx.index,
                                            cost: idx.cost,
                                        })),
                                    ]}
                                />

                                <CandidateTable
                                    candidates={result.recommendedIndexes.map((idx) => ({
                                        index: idx.index,
                                        cost: idx.cost,
                                        improvement: idx.improvement,
                                    }))}
                                />

                                <CoverageAnalysisCard indexes={result.recommendedIndexes} />

                                <RecommendedIndexSetCard indexes={result.recommendedIndexes} />
                            </>
                        )}
                    </>
                )}
            </div>
        </>
    );
}

export default WorkloadOptimization;
