import { cookies } from "next/headers";
import Link from "next/link";
import { BACKEND_API_URL } from "@/lib/config";
import type { User } from "./types";

async function getUsers(): Promise<User[]> {
  const cookieStore = await cookies();

  const res = await fetch(`${BACKEND_API_URL}/api/users`, {
    cache: "no-store",
    headers: {
      cookie: cookieStore.toString(),
    },
  });

  if (res.status === 401) {
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    throw new Error("Could not load users");
  }

  return res.json() as Promise<User[]>;
}

export default async function UsersPage() {
  let users: User[];

  try {
    users = await getUsers();
  } catch (error) {
    if (error instanceof Error && error.message === "Unauthorized") {
      return (
        <section>
          <h1>Users</h1>
          <p>You need to be logged in to view this page.</p>
          <Link href="/login">Sign in</Link>
        </section>
      );
    }

    return (
      <section>
        <h1>Users</h1>
        <p>Could not load users.</p>
      </section>
    );
  }

  if (users.length === 0) {
    return (
      <section>
        <h1>Users</h1>
        <p>No users found.</p>
      </section>
    );
  }

  return (
    <section>
      <h1>Users</h1>

      <ul>
        {users.map((user) => (
          <li key={user.id}>{user.email}</li>
        ))}
      </ul>

      <Link href="/">Back to home</Link>
    </section>
  );
}
