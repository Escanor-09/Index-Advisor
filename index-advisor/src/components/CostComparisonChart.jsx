import {
    BarChart,
    Bar,
    XAxis,
    YAxis,
    Tooltip,
    ResponsiveContainer,
    CartesianGrid,
    Cell,
} from "recharts";

import "../css/CostComparisonChart.css";

function CostComparisonChart({ data }) {
    const minCost = Math.min(
        ...data
            .filter(item => item.index !== "No Index")
            .map(item => item.cost)
    );

    return (
        <div className="chart-card">
            <h2>Cost Comparison</h2>

            <ResponsiveContainer width="100%" height={350}>
                <BarChart
                    data={data}
                    margin={{
                        top: 20,
                        right: 20,
                        left: 20,
                        bottom: 20,
                    }}
                >
                    <CartesianGrid strokeDasharray="3 3" />

                    <XAxis
                        dataKey="index"
                        angle={-15}
                        textAnchor="end"
                        height={70}
                    />

                    <YAxis />

                    <Tooltip
                        formatter={(value) => [
                            value,
                            "Estimated Cost",
                        ]}
                    />

                    <Bar
                        dataKey="cost"
                        radius={[8, 8, 0, 0]}
                    >
                        {data.map((entry, index) => (
                            <Cell
                                key={index}
                                fill={
                                    entry.cost === minCost
                                        ? "#2563eb"
                                        : "#93c5fd"
                                }
                            />
                        ))}
                    </Bar>
                </BarChart>
            </ResponsiveContainer>
        </div>
    );
}

export default CostComparisonChart;