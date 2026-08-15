const pool = require("../db");
const { createZipfianSampler, weightedChoice } = require("./random-utils");

const BATCH_SIZE = 1000;

// Real product popularity is heavy-tailed, not uniform — a small share of
// products drive a disproportionate share of order volume. Calibrated
// empirically: 1.2 was far too aggressive — the single top product alone
// appeared in ~17% of all order_items, an unrealistic monopolizing
// outlier. 0.6 produces a moderate concentration, somewhat sharper than
// customer activity (real "hit products" usually dominate more than any
// single "whale" customer does) without being absurd; see docs for the
// actual observed numbers.
const PRODUCT_POPULARITY_SKEW = 0.6;

// Real orders rarely have exactly one line item. Distribution deliberately
// decays (most orders are small, few are large) rather than being uniform
// across 1-5 — matches typical e-commerce basket-size shape.
const ITEMS_PER_ORDER_DISTRIBUTION = [
  [1, 30],
  [2, 30],
  [3, 20],
  [4, 12],
  [5, 8],
];

const MAX_DUPLICATE_PRODUCT_RETRIES = 10;

async function insertBatch(client, rows) {
  const values = [];
  const placeholders = [];

  let parameter = 1;

  for (const row of rows) {
    placeholders.push(
      `($${parameter}, $${parameter + 1}, $${parameter + 2}, $${parameter + 3})`,
    );

    values.push(row.orderId, row.productId, row.quantity, row.unitPrice);

    parameter += 4;
  }

  await client.query(
    `
        INSERT INTO public.order_items
        (order_id, product_id, quantity, unit_price)
        VALUES ${placeholders.join(", ")}
        `,
    values,
  );

  return rows.length;
}

async function main() {
  const client = await pool.connect();

  try {
    const countResult = await client.query(
      "SELECT COUNT(*) FROM public.order_items",
    );

    if (Number(countResult.rows[0].count) > 0) {
      throw new Error("order_items table is not empty. Aborting.");
    }

    const orders = await client.query(`
            SELECT id
            FROM public.orders
            ORDER BY id
        `);

    const products = await client.query(`
            SELECT id, price
            FROM public.products
        `);

    if (orders.rows.length === 0) {
      throw new Error("No orders found. Populate orders first.");
    }

    if (products.rows.length === 0) {
      throw new Error("No products found. Populate products first.");
    }

    const pickProduct = createZipfianSampler(products.rows, PRODUCT_POPULARITY_SKEW);

    await client.query("BEGIN");

    let totalInserted = 0;
    let batchNumber = 1;
    let pendingRows = [];

    for (const order of orders.rows) {
      const itemCount = weightedChoice(ITEMS_PER_ORDER_DISTRIBUTION);
      const usedProductIds = new Set();

      for (let i = 0; i < itemCount; i++) {
        let product = pickProduct();
        let attempts = 0;

        // Avoid the same product appearing twice in one order — real orders
        // don't have duplicate line items. Bounded retry, not a hard
        // guarantee: with up to 5 items drawn from 10,000 products this
        // essentially never exhausts the retry budget.
        while (usedProductIds.has(product.id) && attempts < MAX_DUPLICATE_PRODUCT_RETRIES) {
          product = pickProduct();
          attempts += 1;
        }

        usedProductIds.add(product.id);

        const quantity = Math.floor(Math.random() * 4) + 1;
        const unitPrice = Number(product.price);

        pendingRows.push({
          orderId: order.id,
          productId: product.id,
          quantity,
          unitPrice,
        });
      }

      if (pendingRows.length >= BATCH_SIZE) {
        totalInserted += await insertBatch(client, pendingRows);
        console.log(`Order items: ${totalInserted} inserted (batch ${batchNumber})`);
        pendingRows = [];
        batchNumber += 1;
      }
    }

    if (pendingRows.length > 0) {
      totalInserted += await insertBatch(client, pendingRows);
      console.log(`Order items: ${totalInserted} inserted (final batch)`);
    }

    /*
     * Calculate real order totals from the generated
     * order items.
     */
    await client.query(`
            UPDATE public.orders o
            SET total_amount = totals.total
            FROM (
                SELECT
                    order_id,
                    ROUND(
                        SUM(quantity * unit_price),
                        2
                    ) AS total
                FROM public.order_items
                GROUP BY order_id
            ) totals
            WHERE o.id = totals.order_id;
        `);

    await client.query("COMMIT");

    console.log(`\n✓ ${totalInserted} order items inserted across ${orders.rows.length} orders.`);
    console.log("✓ Order total_amount values updated.");
  } catch (error) {
    await client.query("ROLLBACK");
    console.error("\n❌", error.message);
    process.exitCode = 1;
  } finally {
    client.release();
    await pool.end();
  }
}

main();
