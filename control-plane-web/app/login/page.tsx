"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, ApiError } from "@/lib/api";
import { setToken } from "@/lib/auth";
import { Panel } from "@/components/Panel";
import { Button } from "@/components/Button";
import { inputClass, labelClass, fieldClass } from "@/components/Input";

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(null);
    setPending(true);
    try {
      const token = await api.login(username, password);
      setToken(token.accessToken, token.expiresAt);
      router.replace("/");
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Login failed");
      setPending(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <Panel className="w-full max-w-sm p-8 shadow-sm">
        <div className="mb-8 flex items-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-cyan-600 text-sm font-bold text-white dark:bg-cyan-500 dark:text-slate-950">
            F
          </span>
          <div>
            <h1 className="text-lg font-semibold tracking-tight text-foreground">FuncHole</h1>
            <p className="text-xs text-muted">Control Plane</p>
          </div>
        </div>

        <form className="flex flex-col gap-4" onSubmit={handleSubmit}>
          <label className={fieldClass}>
            <span className={labelClass}>Username</span>
            <input
              type="text"
              required
              autoComplete="username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              className={inputClass}
            />
          </label>

          <label className={fieldClass}>
            <span className={labelClass}>Password</span>
            <input
              type="password"
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              className={inputClass}
            />
          </label>

          {error && (
            <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-600 dark:bg-rose-500/10 dark:text-rose-400">
              {error}
            </p>
          )}

          <Button type="submit" variant="primary" disabled={pending} className="mt-2 w-full">
            {pending ? "Signing in…" : "Sign in"}
          </Button>
        </form>
      </Panel>
    </div>
  );
}
