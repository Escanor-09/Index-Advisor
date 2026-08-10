// import { BrowserRouter, Routes, Route } from "react-router-dom";

// import Home from "./pages/Home";
// import SingleQuery from "./pages/SingleQuery";
// import WorkloadOptimization from "./pages/WorkloadOptimization";


// function App() {
//   return (
//     <BrowserRouter>
//       <Routes>
//         <Route path="/" element={<Home />} />
//         <Route path="/single-query" element={<SingleQuery />} />
//         <Route path="/workload" element={<WorkloadOptimization />}></Route>
//       </Routes>
//     </BrowserRouter>
//   );
// }

// export default App;
import { BrowserRouter, Routes, Route } from "react-router-dom";

import Home from "./pages/Home";
import SingleQuery from "./pages/SingleQuery";
import WorkloadOptimization from "./pages/WorkloadOptimization";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/single-query" element={<SingleQuery />} />
        <Route path="/workload" element={<WorkloadOptimization />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;