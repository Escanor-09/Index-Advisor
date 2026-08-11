/**
 * Weighted rank-based sampler (Zipf-like): item at rank i (0-indexed) gets
 * weight 1/(i+1)^exponent, so lower-index items are picked disproportionately
 * more often than higher-index ones. Used to give a small subset of
 * customers/products most of the activity instead of every row being
 * equally likely — real e-commerce activity is heavy-tailed, not uniform.
 * The specific item at rank 0 isn't meaningfully "special" (rows aren't
 * pre-sorted by any real popularity signal) — what matters is that *some*
 * items end up popular and most don't, which this produces regardless of
 * which physical rows happen to land at low ranks.
 */
function createZipfianSampler(items, exponent) {
  const n = items.length;
  const cumulativeWeights = new Array(n);
  let total = 0;

  for (let i = 0; i < n; i++) {
    total += 1 / Math.pow(i + 1, exponent);
    cumulativeWeights[i] = total;
  }

  return function sample() {
    const target = Math.random() * total;

    let lo = 0;
    let hi = n - 1;

    while (lo < hi) {
      const mid = (lo + hi) >>> 1;

      if (cumulativeWeights[mid] < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }

    return items[lo];
  };
}

/** weightedOptions: [[value, weight], ...] */
function weightedChoice(weightedOptions) {
  const total = weightedOptions.reduce((sum, [, weight]) => sum + weight, 0);
  let target = Math.random() * total;

  for (const [value, weight] of weightedOptions) {
    if (target < weight) {
      return value;
    }

    target -= weight;
  }

  return weightedOptions[weightedOptions.length - 1][0];
}

module.exports = { createZipfianSampler, weightedChoice };
