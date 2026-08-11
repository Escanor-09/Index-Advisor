const pool = require("../db");

const TOTAL_ROWS = 10000;
const BATCH_SIZE = 1000;

const FIRST_NAMES = [
  "Aarav",
  "Vivaan",
  "Aditya",
  "Arjun",
  "Rohan",
  "Kabir",
  "Rahul",
  "Karan",
  "Vikram",
  "Nikhil",
  "Ananya",
  "Aanya",
  "Diya",
  "Isha",
  "Meera",
  "Priya",
  "Riya",
  "Kavya",
  "Sneha",
  "Nisha",
];

const LAST_NAMES = [
  "Sharma",
  "Patel",
  "Verma",
  "Gupta",
  "Mehta",
  "Reddy",
  "Iyer",
  "Nair",
  "Kapoor",
  "Singh",
  "Malhotra",
  "Chopra",
  "Joshi",
  "Mishra",
  "Bansal",
];

const LOCATIONS = [
  ["Mumbai", "Maharashtra", "India"],
  ["Delhi", "Delhi", "India"],
  ["Bengaluru", "Karnataka", "India"],
  ["Hyderabad", "Telangana", "India"],
  ["Chennai", "Tamil Nadu", "India"],
  ["Pune", "Maharashtra", "India"],
  ["Kolkata", "West Bengal", "India"],
  ["Ahmedabad", "Gujarat", "India"],
  ["Jaipur", "Rajasthan", "India"],
  ["Lucknow", "Uttar Pradesh", "India"],
  ["New York", "New York", "USA"],
  ["London", "England", "UK"],
  ["Toronto", "Ontario", "Canada"],
  ["Singapore", "Singapore", "Singapore"],
  ["Dubai", "Dubai", "UAE"],
];

const STATUSES = ["active", "active", "active", "inactive", "suspended"];

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
      "SELECT COUNT(*) FROM public.customers",
    );

    if (Number(countResult.rows[0].count) > 0) {
      throw new Error(
        "customers table is not empty. Aborting to prevent duplicates.",
      );
    }

    await client.query("BEGIN");

    let inserted = 0;

    while (inserted < TOTAL_ROWS) {
      const size = Math.min(BATCH_SIZE, TOTAL_ROWS - inserted);

      const values = [];
      const placeholders = [];

      let parameter = 1;

      for (let i = 0; i < size; i++) {
        const firstName = random(FIRST_NAMES);
        const lastName = random(LAST_NAMES);
        const name = `${firstName} ${lastName}`;

        const uniqueNumber = inserted + i + 1;

        const email =
          `${firstName.toLowerCase()}.${lastName.toLowerCase()}` +
          `${uniqueNumber}@example.com`;

        const [city, state, country] = random(LOCATIONS);

        const createdAt = randomDate(
          new Date("2021-01-01"),
          new Date("2026-06-30"),
        );

        placeholders.push(
          `($${parameter}, $${parameter + 1}, $${parameter + 2}, ` +
            `$${parameter + 3}, $${parameter + 4}, $${parameter + 5}, ` +
            `$${parameter + 6})`,
        );

        values.push(
          name,
          email,
          city,
          state,
          country,
          createdAt,
          random(STATUSES),
        );

        parameter += 7;
      }

      await client.query(
        `
                INSERT INTO public.customers
                (name, email, city, state, country, created_at, status)
                VALUES ${placeholders.join(", ")}
                `,
        values,
      );

      inserted += size;

      console.log(`Customers: ${inserted}/${TOTAL_ROWS}`);
    }

    await client.query("COMMIT");

    console.log("\n✓ 10,000 customers inserted.");
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
