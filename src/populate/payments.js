const pool = require("../db");

const TOTAL_ROWS = 10000;
const BATCH_SIZE = 1000;

const METHODS = ["credit_card", "debit_card", "upi", "net_banking", "wallet"];

async function main() {
  const client = await pool.connect();

  try {
    const countResult = await client.query(
      "SELECT COUNT(*) FROM public.payments",
    );

    if (Number(countResult.rows[0].count) > 0) {
      throw new Error("payments table is not empty. Aborting.");
    }

    /*
     * orders.total_amount starts out as 0 (see orders.js) and is only
     * backfilled to the real total by order-items.js. payments.amount is
     * copied straight from orders.total_amount below, so if this runs
     * before order-items.js every payment would silently get amount = 0
     * and nothing would ever go back and fix it.
     */
    const orderItemsCheck = await client.query(
      "SELECT COUNT(*)::int AS count FROM public.order_items",
    );

    if (orderItemsCheck.rows[0].count === 0) {
      throw new Error(
        "order_items table is empty. Run order-items.js before payments.js " +
          "so orders.total_amount is backfilled first — otherwise every " +
          "payment would be inserted with amount = 0.",
      );
    }

    const orders = await client.query(`
            SELECT
                id,
                customer_id,
                total_amount,
                created_at,
                payment_status
            FROM public.orders
            ORDER BY id
            LIMIT ${TOTAL_ROWS}
        `);

    if (orders.rows.length < TOTAL_ROWS) {
      throw new Error(`Need ${TOTAL_ROWS} orders before generating payments.`);
    }

    await client.query("BEGIN");

    let inserted = 0;

    while (inserted < TOTAL_ROWS) {
      const size = Math.min(BATCH_SIZE, TOTAL_ROWS - inserted);

      const values = [];
      const placeholders = [];

      let parameter = 1;

      for (let i = 0; i < size; i++) {
        const order = orders.rows[inserted + i];

        let status;

        switch (order.payment_status) {
          case "paid":
            status = "completed";
            break;

          case "failed":
            status = "failed";
            break;

          case "refunded":
            status = "refunded";
            break;

          default:
            status = "pending";
        }

        placeholders.push(
          `($${parameter}, $${parameter + 1}, ` +
            `$${parameter + 2}, $${parameter + 3}, ` +
            `$${parameter + 4}, $${parameter + 5})`,
        );

        values.push(
          order.id,
          order.customer_id,
          order.total_amount,
          random(METHODS),
          status,
          order.created_at,
        );

        parameter += 6;
      }

      await client.query(
        `
                INSERT INTO public.payments
                (
                    order_id,
                    customer_id,
                    amount,
                    method,
                    status,
                    created_at
                )
                VALUES ${placeholders.join(", ")}
                `,
        values,
      );

      inserted += size;

      console.log(`Payments: ${inserted}/${TOTAL_ROWS}`);
    }

    await client.query("COMMIT");

    console.log("\n✓ 10,000 payments inserted.");
  } catch (error) {
    await client.query("ROLLBACK");
    console.error("\n❌", error.message);
    process.exitCode = 1;
  } finally {
    client.release();
    await pool.end();
  }
}

function random(array) {
  return array[Math.floor(Math.random() * array.length)];
}

main();
