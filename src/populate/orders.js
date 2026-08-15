const pool = require("../db");
const { createZipfianSampler } = require("./random-utils");

const TOTAL_ROWS = 10000;
const BATCH_SIZE = 1000;

// Real customer activity is heavy-tailed, not uniform — a small share of
// customers place a disproportionate share of orders. Calibrated
// empirically (checked actual observed concentration after generation,
// not just trusted the math): 1.1 was far too aggressive — one customer
// alone got ~15% of all 10,000 orders, an unrealistic single monopolizing
// outlier, not "skew." 0.5 produces a moderate, defensible concentration;
// see docs for the actual observed numbers.
const CUSTOMER_ACTIVITY_SKEW = 0.5;

const ORDER_STATUSES = [
  "pending",
  "processing",
  "shipped",
  "delivered",
  "cancelled",
];

const PAYMENT_STATUSES = [
  "pending",
  "paid",
  "paid",
  "paid",
  "failed",
  "refunded",
];

function random(array) {
  return array[Math.floor(Math.random() * array.length)];
}

function randomDate(start, end) {
  return new Date(
    start.getTime() + Math.random() * (end.getTime() - start.getTime()),
  );
}

async function main() {
  const client = await pool.connect();

  try {
    const countResult = await client.query(
      "SELECT COUNT(*) FROM public.orders",
    );

    if (Number(countResult.rows[0].count) > 0) {
      throw new Error("orders table is not empty. Aborting.");
    }

    const customers = await client.query(`
            SELECT id, city
            FROM public.customers
            ORDER BY id
        `);

    if (customers.rows.length === 0) {
      throw new Error("No customers found. Populate customers first.");
    }

    const pickCustomer = createZipfianSampler(customers.rows, CUSTOMER_ACTIVITY_SKEW);

    await client.query("BEGIN");

    let inserted = 0;

    while (inserted < TOTAL_ROWS) {
      const size = Math.min(BATCH_SIZE, TOTAL_ROWS - inserted);

      const values = [];
      const placeholders = [];

      let parameter = 1;

      for (let i = 0; i < size; i++) {
        const customer = pickCustomer();

        const status = random(ORDER_STATUSES);

        let paymentStatus;

        if (status === "cancelled") {
          paymentStatus = random(["pending", "failed", "refunded"]);
        } else {
          paymentStatus = random(PAYMENT_STATUSES);
        }

        const createdAt = randomDate(
          new Date("2022-01-01"),
          new Date("2026-07-31"),
        );

        const updatedAt = new Date(
          createdAt.getTime() +
            Math.random() * (new Date().getTime() - createdAt.getTime()),
        );

        const city = customer.city;

        placeholders.push(
          `($${parameter}, $${parameter + 1}, $${parameter + 2}, ` +
            `$${parameter + 3}, $${parameter + 4}, $${parameter + 5}, ` +
            `$${parameter + 6})`,
        );

        values.push(
          customer.id,
          status,
          0,
          paymentStatus,
          createdAt,
          updatedAt,
          city,
        );

        parameter += 7;
      }

      await client.query(
        `
                INSERT INTO public.orders
                (
                    customer_id,
                    status,
                    total_amount,
                    payment_status,
                    created_at,
                    updated_at,
                    shipping_city
                )
                VALUES ${placeholders.join(", ")}
                `,
        values,
      );

      inserted += size;

      console.log(`Orders: ${inserted}/${TOTAL_ROWS}`);
    }

    await client.query("COMMIT");

    console.log("\n✓ 10,000 orders inserted.");
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
