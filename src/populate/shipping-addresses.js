const pool = require("../db");

const TOTAL_ROWS = 10000;
const BATCH_SIZE = 1000;

function randomPostalCode(country) {
  if (country === "India") {
    return String(Math.floor(100000 + Math.random() * 900000));
  }

  return String(Math.floor(10000 + Math.random() * 90000));
}

async function main() {
  const client = await pool.connect();

  try {
    const countResult = await client.query(
      "SELECT COUNT(*) FROM public.shipping_addresses",
    );

    if (Number(countResult.rows[0].count) > 0) {
      throw new Error("shipping_addresses table is not empty. Aborting.");
    }

    const orders = await client.query(`
            SELECT
                id,
                customer_id,
                shipping_city
            FROM public.orders
            ORDER BY id
            LIMIT ${TOTAL_ROWS}
        `);

    const customers = await client.query(`
            SELECT
                id,
                city,
                state,
                country
            FROM public.customers
        `);

    if (orders.rows.length < TOTAL_ROWS) {
      throw new Error(`Need ${TOTAL_ROWS} orders before generating addresses.`);
    }

    const customerById = new Map(
      customers.rows.map((c) => [Number(c.id), c]),
    );

    await client.query("BEGIN");

    let inserted = 0;

    while (inserted < TOTAL_ROWS) {
      const size = Math.min(BATCH_SIZE, TOTAL_ROWS - inserted);

      const values = [];
      const placeholders = [];

      let parameter = 1;

      for (let i = 0; i < size; i++) {
        const order = orders.rows[inserted + i];

        const customer = customerById.get(Number(order.customer_id));

        if (!customer) {
          throw new Error(`Customer ${order.customer_id} not found.`);
        }

        placeholders.push(
          `($${parameter}, $${parameter + 1}, ` +
            `$${parameter + 2}, $${parameter + 3}, ` +
            `$${parameter + 4}, $${parameter + 5})`,
        );

        values.push(
          customer.id,
          order.id,
          order.shipping_city,
          customer.state,
          randomPostalCode(customer.country),
          customer.country,
        );

        parameter += 6;
      }

      await client.query(
        `
                INSERT INTO public.shipping_addresses
                (
                    customer_id,
                    order_id,
                    city,
                    state,
                    postal_code,
                    country
                )
                VALUES ${placeholders.join(", ")}
                `,
        values,
      );

      inserted += size;

      console.log(`Shipping addresses: ${inserted}/${TOTAL_ROWS}`);
    }

    await client.query("COMMIT");

    console.log("\n✓ 10,000 shipping addresses inserted.");
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
